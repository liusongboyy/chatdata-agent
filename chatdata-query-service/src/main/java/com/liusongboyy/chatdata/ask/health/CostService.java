package com.liusongboyy.chatdata.ask.health;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.liusongboyy.chatdata.ask.audit.AskAuditLog;
import com.liusongboyy.chatdata.ask.audit.mapper.AskAuditMapper;
import com.liusongboyy.chatdata.engine.admin.AdminDomainService;
import com.liusongboyy.chatdata.engine.model.Cost;
import com.liusongboyy.chatdata.engine.model.CostAggregate;
import com.liusongboyy.chatdata.engine.model.ModelDef;
import com.liusongboyy.chatdata.engine.model.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 成本看板：把审计里的 token 记账换成钱，并按模型 / 域 / 用户拆开。
 *
 * <h2>为什么单独一个 Service，不塞进 {@link HealthService}</h2>
 *
 * <p>两者读的是同一张表，但**口径完全不同**：健康度看"成不成功"，
 * 成本看"花了多少"。混在一起的后果是那个类迟早要同时回答两个问题，
 * 任何一个改口径都会牵动另一个。而它们各自的失败处理也不一样——
 * 健康度算不出来是"页面空白"，成本算不出来是"给了个错的钱"，
 * 后者危险得多，值得有自己的 catch 与日志。
 *
 * <h2>金额的三种"没有"</h2>
 *
 * <ol>
 *   <li>模型没配单价 → 这一条算不出钱</li>
 *   <li>老数据没有 token → 这一条算不出钱</li>
 *   <li>没人提问 → 整个分组是 0 次，金额 null</li>
 * </ol>
 *
 * <p><b>一律不用 0 代替。</b>0 元是"免费"，而上面三种都是"不知道"。
 * 把不知道显示成 0，管理员会得出"成本很低"的结论——而这个结论是假的。
 */
@Service
public class CostService {

    private static final Logger log = LoggerFactory.getLogger(CostService.class);

    /**
     * 按用户统计最多返回几个。
     *
     * <p>不做分页：这个页面回答的是"钱主要花在谁身上"，
     * 前 20 个已经覆盖绝大多数情况。再多就该去审计表里查了。
     */
    private static final int TOP_USERS = 20;

    /** 与 HealthService 一致：超过这个行数给性能提示。 */
    private static final long SLOW_ROW_THRESHOLD = 50_000;

    /** 金额展示保留 4 位。单次调用 0.00xx 元，2 位会全被舍成 0.00。 */
    private static final int AMOUNT_SCALE = 4;

    /**
     * 累加用的精度：微元（1e-6）。
     *
     * <p><b>必须等于 {@link Cost} 产出的 scale，不能等于展示精度。</b>
     * 用 4 位累加会在第一条记录上就抛 {@code ArithmeticException: Rounding necessary}——
     * 因为单次调用的金额常常是 0.00216 这种 5 位小数。
     * 先按 6 位整数累加、输出时再舍到 4 位，既无精度损失也不会有累积误差。
     */
    private static final int ACCUMULATE_SCALE = 6;

    private final AskAuditMapper auditMapper;
    private final ModelService models;
    private final AdminDomainService domainService;

    public CostService(AskAuditMapper auditMapper, ModelService models,
                       AdminDomainService domainService) {
        this.auditMapper = auditMapper;
        this.models = models;
        this.domainService = domainService;
    }

    /**
     * 成本报表。
     *
     * @param days 时间窗口（天），1 表示今天
     */
    public CostAggregate.Report cost(int days) {
        int window = Math.max(1, Math.min(days, 90));
        LocalDateTime since = LocalDateTime.now().minusDays(window);

        // 只取需要的列。sql_text / dsl_json 是大字段，全查回来在数据量上来了之后
        // 会让这个页面明显变慢，而它们对成本毫无贡献
        List<AskAuditLog> logs = auditMapper.selectList(
                new QueryWrapper<AskAuditLog>()
                        .ge("created_at", since)
                        // 只算成功的：失败的请求既没产生价值，token 也常常采不到；
                        // 混进来会让"单次成本"这个数字被零头拉低
                        .eq("outcome", "SUCCESS")
                        .select("username", "domain_id", "model_key",
                                "input_tokens", "output_tokens", "cached_tokens"));

        Map<String, ModelDef> modelIndex = new LinkedHashMap<>();
        for (ModelDef m : models.all()) {
            modelIndex.put(m.key(), m);
        }

        Map<String, Acc> byModel = new LinkedHashMap<>();
        Map<String, Acc> byDomain = new LinkedHashMap<>();
        Map<String, Acc> byUser = new LinkedHashMap<>();
        Acc total = new Acc();

        for (AskAuditLog l : logs) {
            String modelKey = l.getModelKey() == null || l.getModelKey().isBlank()
                    ? UNKNOWN_MODEL : l.getModelKey();
            ModelDef def = modelIndex.get(modelKey);
            RowCost c = RowCost.of(def, l);

            total.add(c);
            byModel.computeIfAbsent(modelKey, k -> new Acc()).add(c);
            byDomain.computeIfAbsent(l.getDomainId() == null ? "" : l.getDomainId(),
                    k -> new Acc()).add(c);
            byUser.computeIfAbsent(l.getUsername() == null ? "" : l.getUsername(),
                    k -> new Acc()).add(c);
        }

        // 配了但没人用的模型要列出来。省略会让管理员以为配置没生效——
        // 而这正是管理页最该回答的问题之一
        for (String key : modelIndex.keySet()) {
            byModel.computeIfAbsent(key, k -> new Acc());
        }

        List<CostAggregate.Row> modelRows = new ArrayList<>();
        byModel.forEach((key, acc) -> modelRows.add(acc.toRow(key, modelLabel(key, modelIndex))));
        // 花钱多的排前面；零提问的沉底但要留着
        modelRows.sort(Comparator
                .comparing((CostAggregate.Row r) -> r.amount() == null ? BigDecimal.ZERO : r.amount())
                .reversed()
                .thenComparing(CostAggregate.Row::label));

        List<CostAggregate.Row> domainRows = new ArrayList<>();
        Map<String, String> domainNames = domainNames();
        byDomain.forEach((id, acc) -> domainRows.add(
                acc.toRow(id, domainNames.getOrDefault(id, id.isBlank() ? "（无数据域）" : id))));
        domainRows.sort(Comparator
                .comparing((CostAggregate.Row r) -> r.amount() == null ? BigDecimal.ZERO : r.amount())
                .reversed());

        List<CostAggregate.UserRow> userRows = new ArrayList<>();
        byUser.forEach((user, acc) -> userRows.add(new CostAggregate.UserRow(
                user, acc.asks, acc.priced, acc.amount(),
                acc.topModel())));
        userRows.sort(Comparator
                .comparing((CostAggregate.UserRow r) -> r.amount() == null ? BigDecimal.ZERO : r.amount())
                .reversed());
        List<CostAggregate.UserRow> topUsers = userRows.stream().limit(TOP_USERS).toList();

        BigDecimal totalAmount = total.amount();
        BigDecimal avg = (totalAmount == null || total.priced == 0)
                ? null
                : totalAmount.divide(BigDecimal.valueOf(total.priced), AMOUNT_SCALE,
                        RoundingMode.HALF_UP);

        return new CostAggregate.Report(window,
                new CostAggregate.Totals(total.asks, total.priced, totalAmount, avg,
                        total.input, total.cached, total.output, total.hitRate()),
                modelRows, domainRows, topUsers,
                slowNotice(logs.size(), window));
    }

    /** 模型没了（被删）时的占位键。**不拼到空串里**——那会让「历史模型」与「老数据」混成一行。 */
    private static final String UNKNOWN_MODEL = "（未记录）";

    private String modelLabel(String key, Map<String, ModelDef> index) {
        ModelDef def = index.get(key);
        return def == null ? key : def.displayName();
    }

    /** 域 id → 显示名。读不到就退回 id，不让整页挂掉。 */
    private Map<String, String> domainNames() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (var row : domainService.list()) {
                out.put(String.valueOf(row.id()), row.name());
            }
        } catch (RuntimeException e) {
            log.warn("读取域清单失败，成本看板将显示域 id：{}", e.getMessage());
        }
        return out;
    }

    /**
     * 数据量大时给一句提示。与健康度页同一取舍：**提醒比优化更重要**，
     * 没人会主动怀疑一个统计页面本身出了问题。
     */
    private static String slowNotice(long rows, int window) {
        if (rows < SLOW_ROW_THRESHOLD) {
            return null;
        }
        return "最近 " + window + " 天有 " + rows + " 条成功记录，这个页面是实时统计的，会明显变慢。"
                + "先把时间范围调小能立刻缓解。";
    }

    // ─────────────────────────────────────────────────────────────

    /**
     * 一条审计记录折算成的成本。
     *
     * @param amount 为 null 表示这一条算不出钱（没配单价 / 没采到 token）
     */
    private record RowCost(Long amountScaled, long input, long cached, long output,
                           String modelKey) {

        static RowCost of(ModelDef def, AskAuditLog l) {
            // token 全是 NULL = 没采到。**不能当 0**——那会让这条记录变成"零成本"
            if (l.getInputTokens() == null && l.getOutputTokens() == null) {
                return new RowCost(null, 0, 0, 0, l.getModelKey());
            }
            long in = nz(l.getInputTokens());
            long cached = nz(l.getCachedTokens());
            long out = nz(l.getOutputTokens());
            Cost cost = Cost.of(def, in, cached, out);
            return new RowCost(
                    cost == null
                            ? null
                            : cost.amount().setScale(ACCUMULATE_SCALE, RoundingMode.HALF_UP)
                                    .movePointRight(ACCUMULATE_SCALE).longValueExact(),
                    in, cached, out, l.getModelKey());
        }

        private static long nz(Integer v) {
            return v == null ? 0 : v;
        }
    }

    /** 累加器。用 BigDecimal 而不是 long：单价是 6 位小数，先截断再加会积累误差。 */
    private static final class Acc {
        long asks;
        long priced;
        long input;
        long cached;
        long output;
        /** 以 {@link #ACCUMULATE_SCALE}（微元）为单位的整数，避免浮点累加误差。 */
        long amountScaled;
        final Map<String, long[]> perModel = new LinkedHashMap<>();

        void add(RowCost c) {
            asks++;
            if (c.amountScaled() != null) {
                priced++;
                amountScaled += c.amountScaled();
            }
            input += c.input();
            cached += c.cached();
            output += c.output();
            if (c.modelKey() != null) {
                perModel.computeIfAbsent(c.modelKey(), k -> new long[1])[0]++;
            }
        }

        BigDecimal amount() {
            return priced == 0 ? null
                    : BigDecimal.valueOf(amountScaled)
                            .movePointLeft(ACCUMULATE_SCALE)
                            .setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        }

        Double hitRate() {
            if (input <= 0) {
                return null;
            }
            return Math.round(cached * 1000.0 / input) / 10.0;
        }

        /** 用得最多的模型键。并列时取字典序最小的，保证结果稳定可复现。 */
        String topModel() {
            return perModel.entrySet().stream()
                    .max(Comparator.<Map.Entry<String, long[]>>comparingLong(e -> e.getValue()[0])
                            .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                    .map(Map.Entry::getKey).orElse(null);
        }

        CostAggregate.Row toRow(String key, String label) {
            return new CostAggregate.Row(key, label, asks, priced, amount(),
                    input, cached, output, hitRate(), null);
        }
    }
}

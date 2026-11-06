package com.liusongboyy.chatdata.ask.health;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.liusongboyy.chatdata.ask.audit.AskAuditLog;
import com.liusongboyy.chatdata.ask.audit.mapper.AskAuditMapper;
import com.liusongboyy.chatdata.engine.admin.AdminDomainService;
import com.liusongboyy.chatdata.engine.startup.EngineConfigViolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 问数健康度：把审计日志与建模检查合到一页能看的地方。
 *
 * <h2>为什么要把两个数据源放一起</h2>
 *
 * <p>静态检查只看得出"配置形态缺失"，看不出它造成了多少真实失败；
 * 审计日志只知道"失败了"，不知道该怎么改。<b>两者的交集才是该优先修的。</b>
 *
 * <p>实测：静态检查说「缺双登记」，日志说「配了聚合方式不能作为维度」52 次——
 * 分开看是两个数字，放一起才知道那 52 次是同一条配置问题造成的。
 *
 * <h2>成功率的口径</h2>
 *
 * <p>{@code SUCCESS / (SUCCESS + FAILURE)}。<b>BLOCKED 不计入分母</b>——
 * 那是权限拒绝，是正确行为，不是质量问题。把它算成失败会让
 * 「权限收紧了」看起来像「系统变差了」。
 */
@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    /** 失败原因排行最多返回几个。多了看不出重点。 */
    private static final int TOP_FAILURES = 8;

    /**
     * 单次查询的行数警戒线。
     *
     * <p>这个页面是**实时全量扫描**窗口内的审计行——没有预聚合、没有缓存。
     * 按现在的数据量（7 天百余行）是毫秒级的，但它会随使用量线性变慢。
     *
     * <p>超过这条线时接口会带一个提示，让打开页面的人知道
     * "现在慢是有原因的"，而不是对着转圈猜。
     * <b>提醒比优化更重要</b>：没有人会主动怀疑一个健康度页面本身有问题。
     */
    private static final long SLOW_ROW_THRESHOLD = 50_000;

    private final AskAuditMapper auditMapper;
    private final AdminDomainService domainService;

    public HealthService(AskAuditMapper auditMapper, AdminDomainService domainService) {
        this.auditMapper = auditMapper;
        this.domainService = domainService;
    }

    // ─────────────────────────────────────────────────────────────
    // 返回结构
    // ─────────────────────────────────────────────────────────────

    /**
     * 一页健康度。
     *
     * @param summary  顶部概览
     * @param failures 失败原因排行
     * @param domains  逐域状态
     */
    public record Overview(Summary summary, List<FailureClassifier.Bucket> failures,
                           List<DomainHealth> domains, String notice) {
    }

    /**
     * 概览数字。
     *
     * @param questions  成功 + 失败。**不含 BLOCKED**（权限拒绝不是质量问题）
     * @param successRate 0–100，一位小数。无提问时为 null 而不是 0——
     *                    0% 和"还没有人用过"是两件事
     */
    public record Summary(int domains, long questions, Double successRate,
                          long pendingFixes, long pendingFeedback) {
    }

    /**
     * 单个域的运行状态。
     *
     * @param asks       提问数（成功+失败）
     * @param successRate 成功率；无提问为 null
     * @param unmodeled  覆盖度问题数；直连域为 null（不做这项检查）
     */
    public record DomainHealth(long id, String name, String mode,
                               long asks, Double successRate, Integer unmodeled) {
    }

    // ─────────────────────────────────────────────────────────────

    /**
     * 汇总。
     *
     * @param days 时间范围（天）。1 表示今天
     */
    public Overview overview(int days) {
        int window = Math.max(1, Math.min(days, 90));
        LocalDateTime since = LocalDateTime.now().minusDays(window);

        List<AskAuditLog> logs = auditMapper.selectList(
                new QueryWrapper<AskAuditLog>()
                        .ge("created_at", since)
                        // 只取需要的列：审计表有 sql_text / dsl_json 两个大字段，
                        // 全查回来在数据量大时是浪费
                        .select("domain_id", "outcome", "error_message"));

        // ① 按域聚合
        Map<String, long[]> perDomain = new LinkedHashMap<>();   // [success, failure]
        Map<String, String> failureMessages = new LinkedHashMap<>();
        long success = 0;
        long failure = 0;

        for (AskAuditLog l : logs) {
            String outcome = l.getOutcome();
            String domain = l.getDomainId() == null ? "" : l.getDomainId();
            long[] c = perDomain.computeIfAbsent(domain, k -> new long[2]);
            if ("SUCCESS".equals(outcome)) {
                c[0]++;
                success++;
            } else if ("FAILURE".equals(outcome)) {
                c[1]++;
                failure++;
                failureMessages.put(l.getErrorMessage(), null);
            }
            // BLOCKED 刻意不计数——见类注释
        }

        // ② 失败原因归类
        Map<String, long[]> buckets = new LinkedHashMap<>();   // key → [count]
        Map<String, String> bucketLabels = new LinkedHashMap<>();
        Map<String, String> bucketSamples = new LinkedHashMap<>();
        Map<String, FailureClassifier.Fixability> bucketFixable = new LinkedHashMap<>();
        for (AskAuditLog l : logs) {
            if (!"FAILURE".equals(l.getOutcome())) {
                continue;
            }
            var c = FailureClassifier.classify(l.getErrorMessage());
            buckets.computeIfAbsent(c.key(), k -> new long[1])[0]++;
            bucketLabels.putIfAbsent(c.key(), c.label());
            bucketSamples.putIfAbsent(c.key(), l.getErrorMessage());
            bucketFixable.putIfAbsent(c.key(), c.fixability());
        }
        List<FailureClassifier.Bucket> failures = new ArrayList<>();
        buckets.forEach((key, cnt) -> failures.add(new FailureClassifier.Bucket(
                key, bucketLabels.get(key), cnt[0],
                bucketFixable.getOrDefault(key, FailureClassifier.Fixability.NOT_CONFIG),
                bucketSamples.get(key))));
        failures.sort(Comparator.comparingLong(FailureClassifier.Bucket::count).reversed());
        List<FailureClassifier.Bucket> topFailures = failures.stream()
                .limit(TOP_FAILURES).toList();

        // ③ 逐域：把覆盖度也带上
        List<DomainHealth> domains = new ArrayList<>();
        long unmodeledTotal = 0;
        for (var row : domainService.list()) {
            String key = String.valueOf(row.id());
            long[] c = perDomain.getOrDefault(key, new long[2]);
            long asks = c[0] + c[1];
            Double rate = asks == 0 ? null : round(c[0] * 100.0 / asks);

            Integer unmodeled = null;
            // 直连域不做建模检查——后端 isRaw() 直接返回空，
            // 这里也不去调，省一次往返
            if (!"RAW".equals(row.mode())) {
                try {
                    List<EngineConfigViolation> vs = domainService.modelingCheck(row.id());
                    unmodeled = vs == null ? 0 : vs.size();
                    unmodeledTotal += unmodeled;
                } catch (RuntimeException e) {
                    // 单个域的检查失败不该让整页挂掉，也不该显示 0
                    // （0 会让管理员以为这个域没问题）
                    log.warn("域 {} 的覆盖度检查失败：{}", row.id(), e.getMessage());
                }
            }
            domains.add(new DomainHealth(row.id(), row.name(), row.mode(),
                    asks, rate, unmodeled));
        }
        // 提问多的排前面；没人用的域沉底但在页面上要能看见
        domains.sort(Comparator.comparingLong(DomainHealth::asks).reversed());

        long total = success + failure;
        Double overallRate = total == 0 ? null : round(success * 100.0 / total);

        return new Overview(
                new Summary(domains.size(), total, overallRate, unmodeledTotal, 0),
                topFailures, domains, slowNotice(logs.size(), window));
    }

    /**
     * 数据量大时给一句提示。
     *
     * <p>返回 {@code null} 表示正常。**不返回空串**——前端用 {@code v-if} 判断，
     * 空串会被当成"有提示"然后渲染一个空白块。
     *
     * <p>文案要给出**可执行的下一步**，不只是报告慢：告诉用户缩小时间范围
     * 能立刻缓解，同时说明长期该做什么。只说"数据量大"等于把问题丢回去。
     */
    private static String slowNotice(long rows, int window) {
        if (rows < SLOW_ROW_THRESHOLD) {
            return null;
        }
        return "最近 " + window + " 天有 " + rows + " 条审计记录，这个页面是实时统计的，"
                + "会明显变慢。先把时间范围调小能立刻缓解；"
                + "要长期解决需要改成预聚合（见 chatdata-agent-design/health-dashboard/spec.md）。";
    }

    // ─────────────────────────────────────────────────────────────
    // 问数质量：按问题类型看
    // ─────────────────────────────────────────────────────────────

    /**
     * 一个类型的统计。
     *
     * @param successRate 0–100，一位小数。无提问时为 0（类型统计里不会出现无提问）
     * @param sampleQuestion 这个类型里提问次数最多的那一问。
     *                       **名字是抽象的，看到一个具体问题才知道它长什么样**
     */
    public record TypeStat(String key, String label, long asks, long failures,
                           double successRate, long avgMs, String sampleQuestion) {
    }

    /**
     * 一个具体问法的统计。
     *
     * @param question 原问题（已截断，见 {@link #QUESTION_MAX_LEN}）
     */
    public record QuestionStat(String question, long asks, long failures,
                               double successRate, long avgMs,
                               FailureDiagnosis.Diagnosis diagnosis) {
    }

    /** 质量报告。 */
    public record QualityReport(int days, long totalAsks, long unknownAsks,
                                List<TypeStat> types,
                                Map<String, List<QuestionStat>> questionsByType,
                                String notice) {
    }

    /**
     * 问题文本的展示长度。
     *
     * <p>截断是为了让表格能对齐；完整问题在审计表里，要查随时能查。
     */
    private static final int QUESTION_MAX_LEN = 60;

    /**
     * 按问题类型做质量分析。
     *
     * <h2>为什么不复用 {@link #overview}</h2>
     *
     * <p>那个方法只取三列（domain_id / outcome / error_message），
     * 而这里需要 {@code dsl_json} 和 {@code question}、{@code duration_ms}——
     * 行数一样但列不同，硬塞进一个查询会让两边都拿到用不上的数据。
     *
     * @param minSamples 少于这个次数的问法不列出。**样本太少时成功率是噪声**，
     *                   显示出来会把管理员引去追一个偶然现象
     */
    public QualityReport quality(int days, int minSamples) {
        int window = Math.max(1, Math.min(days, 90));
        int min = Math.max(1, minSamples);
        LocalDateTime since = LocalDateTime.now().minusDays(window);

        // 每次调用新建缓存：配置改过之后诊断结论必须立刻变
        fieldCache = new java.util.HashMap<>();

        List<AskAuditLog> logs = auditMapper.selectList(
                new QueryWrapper<AskAuditLog>()
                        .ge("created_at", since)
                        .in("outcome", "SUCCESS", "FAILURE")
                        .select("question", "dsl_json", "outcome", "duration_ms", "domain_id"));

        // 按类型聚合
        Map<String, long[]> byType = new LinkedHashMap<>();     // key → [asks, failures, totalMs]
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, Map<String, long[]>> byQuestion = new LinkedHashMap<>();
        // 诊断需要「这条问题落在哪个域、失败原因是什么」。
        // 同一个问法可能跨多个域，取**失败次数最多**的那个域——
        // 那才是问题所在（问错域的情况本身也是一种诊断结论）
        Map<String, String> questionDomain = new LinkedHashMap<>();
        Map<String, String> questionError = new LinkedHashMap<>();

        for (AskAuditLog l : logs) {
            var t = QuestionTypeClassifier.classify(l.getDslJson(), l.getQuestion());
            long[] c = byType.computeIfAbsent(t.key(), k -> new long[3]);
            c[0]++;
            labels.putIfAbsent(t.key(), t.label());
            if ("FAILURE".equals(l.getOutcome())) {
                c[1]++;
            }
            c[2] += Math.max(0, l.getDurationMs());

            // 具体问法的统计。**空问题不统计**——那是脏数据，
            // 列出来只会让人去查一个查不到的问题
            String q = l.getQuestion() == null ? "" : l.getQuestion().trim();
            if (!q.isEmpty()) {
                String key = truncate(q, QUESTION_MAX_LEN);
                Map<String, long[]> qs = byQuestion.computeIfAbsent(t.key(),
                        k -> new LinkedHashMap<>());
                long[] qc = qs.computeIfAbsent(key, k -> new long[3]);
                qc[0]++;
                if ("FAILURE".equals(l.getOutcome())) {
                    qc[1]++;
                    // 记下失败时的域与原因
                    questionDomain.putIfAbsent(key, l.getDomainId());
                    questionError.putIfAbsent(key, l.getErrorMessage());
                }
                qc[2] += Math.max(0, l.getDurationMs());
            }
        }

        // 组装类型统计
        List<TypeStat> types = new ArrayList<>();
        long total = 0;
        long unknown = 0;
        for (var e : byType.entrySet()) {
            long[] c = e.getValue();
            total += c[0];
            if (QuestionTypeClassifier.Type.UNKNOWN.key().equals(e.getKey())) {
                unknown = c[0];
            }
            // 这个类型里提问最多的那一问，作为"它长什么样"的例子
            String sample = byQuestion.getOrDefault(e.getKey(), Map.of()).entrySet().stream()
                    .max(Comparator.comparingLong(x -> x.getValue()[0]))
                    .map(Map.Entry::getKey).orElse(null);
            types.add(new TypeStat(e.getKey(), labels.get(e.getKey()),
                    c[0], c[1], c[0] == 0 ? 0 : round(c[1] * 100.0 / c[0]),
                    c[0] == 0 ? 0 : c[2] / c[0], sample));
        }
        // 提问多的排前面——那是影响面大的
        types.sort(Comparator.comparingLong(TypeStat::asks).reversed());

        // 组装问法统计：只保留达到样本阈值的，按提问次数排序
        Map<String, List<QuestionStat>> questions = new LinkedHashMap<>();
        byQuestion.forEach((type, qs) -> {
            List<QuestionStat> list = new ArrayList<>();
            qs.forEach((q, c) -> {
                if (c[0] >= min) {
                    // **只给失败过的问法做诊断。** 全成功的问法不需要"怎么办"，
                    // 而对每条都算一遍诊断会白白拖慢页面
                    var diag = c[1] > 0
                            ? diagnoseFor(questionDomain.get(q), questionError.get(q), q)
                            : null;
                    list.add(new QuestionStat(q, c[0], c[1],
                            c[0] == 0 ? 0 : round(c[1] * 100.0 / c[0]),
                            c[0] == 0 ? 0 : c[2] / c[0], diag));
                }
            });
            // 按次数降序：只出现一次且失败的问题没有统计意义，
            // 出现 39 次失败 20 次的才是真在影响用户
            list.sort(Comparator.comparingLong(QuestionStat::asks).reversed());
            questions.put(type, list);
        });

        return new QualityReport(window, total, unknown, types, questions,
                slowNotice(logs.size(), window));
    }

    /**
     * 域字段清单缓存。
     *
     * <p>诊断要拿问题里的词去对域内字段，而每个问法都要这份清单。
     * 不缓存的话同一个域会被查几十次。
     *
     * <p>缓存生命周期 = 一次 {@link #quality} 调用。**不能做成跨请求缓存**——
     * 配置改了之后诊断结论必须立刻变，缓存过期会给出过时的建议。
     */
    private Map<String, List<FailureDiagnosis.Field>> fieldCache;

    /**
     * 给一条失败问法做诊断。
     *
     * @param domainId 失败时所在的域
     * @param error    审计表里的 error_message
     * @param question 问题
     */
    private FailureDiagnosis.Diagnosis diagnoseFor(String domainId, String error, String question) {
        List<FailureDiagnosis.Field> fields = List.of();
        if (domainId != null && !domainId.isBlank() && fieldCache != null) {
            fields = fieldCache.computeIfAbsent(domainId, this::loadFields);
        }
        return FailureDiagnosis.diagnose(error, question, fields);
    }

    /**
     * 读一个域的可见字段（表名 + 业务名）。
     *
     * <p>读不到时返回空列表——那会让诊断落到「无法判断」，
     * 而那是安全的方向：不给建议好过给错建议。
     */
    private List<FailureDiagnosis.Field> loadFields(String domainId) {
        try {
            long id = Long.parseLong(domainId);
            List<FailureDiagnosis.Field> out = new ArrayList<>();
            for (var t : domainService.listTables(id)) {
                if (!t.enabled() || t.columnsJson() == null) {
                    continue;
                }
                // columns_json 是列定义数组。这里只需要业务名，
                // 不值得为它引入完整的解析器——用正则取 alias 足够，
                // 取不到就跳过（诊断会因此落到"无法判断"）
                var m = ALIAS_PATTERN.matcher(t.columnsJson());
                String tableLabel = t.label() == null || t.label().isBlank() ? t.name() : t.label();
                while (m.find()) {
                    out.add(new FailureDiagnosis.Field(tableLabel, m.group(1)));
                }
            }
            return out;
        } catch (RuntimeException e) {
            log.debug("读域 {} 的字段清单失败，诊断将退回无法判断：{}", domainId, e.getMessage());
            return List.of();
        }
    }

    /** 从 columns_json 里取业务名。 */
    private static final java.util.regex.Pattern ALIAS_PATTERN =
            java.util.regex.Pattern.compile("\"alias\"\\s*:\\s*\"([^\"]+)\"");

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}

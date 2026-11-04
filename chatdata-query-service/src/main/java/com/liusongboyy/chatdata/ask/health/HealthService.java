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
                           List<DomainHealth> domains) {
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
        Map<String, Boolean> bucketFixable = new LinkedHashMap<>();
        for (AskAuditLog l : logs) {
            if (!"FAILURE".equals(l.getOutcome())) {
                continue;
            }
            var c = FailureClassifier.classify(l.getErrorMessage());
            buckets.computeIfAbsent(c.key(), k -> new long[1])[0]++;
            bucketLabels.putIfAbsent(c.key(), c.label());
            bucketSamples.putIfAbsent(c.key(), l.getErrorMessage());
            bucketFixable.putIfAbsent(c.key(), c.fixable());
        }
        List<FailureClassifier.Bucket> failures = new ArrayList<>();
        buckets.forEach((key, cnt) -> failures.add(new FailureClassifier.Bucket(
                key, bucketLabels.get(key), cnt[0],
                bucketFixable.getOrDefault(key, false), bucketSamples.get(key))));
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
                topFailures, domains);
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}

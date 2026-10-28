package com.liusongboyy.chatdata.engine.eval;

import com.liusongboyy.chatdata.engine.chat.AskFacade;
import com.liusongboyy.chatdata.engine.chat.AskRequest;
import com.liusongboyy.chatdata.engine.chat.AskResult;
import com.liusongboyy.chatdata.engine.chat.AskServiceFactory;
import com.liusongboyy.chatdata.engine.datasource.QueryResult;
import com.liusongboyy.chatdata.engine.datasource.SqlExecutor;
import com.liusongboyy.chatdata.engine.nlsql.RenderedSql;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 跑一个评测集：逐条提问 → 执行 gold SQL → 比对结果集 → 汇总。
 *
 * <h2>它不做的事</h2>
 *
 * <p><strong>不重试、不修正、不兜底。</strong>评测要测的是"用户问一次会得到什么"，
 * 任何为了提高分数而加的重试都属于改产品行为。若产品本身有重试（DSL 纠正重问），
 * 那是产品的一部分，会自然发生。
 *
 * <p><strong>不经过 HTTP / 登录。</strong>直接调 {@link AskServiceFactory}，
 * 与控制器走同一条服务链路，但不把鉴权、SSE 这些传输问题混进准确率。
 *
 * <h2>一个用例失败不中断整批</h2>
 *
 * <p>30 条用例里有一条把 SQL 写错了，应该得到"其余 29 条的成绩 + 这 1 条的 GOLD_ERROR"，
 * 而不是整批作废。评测跑一次要花几十次模型调用，中断的代价很高。
 */
public final class EvalRunner {

    private final AskServiceFactory factory;
    private final SqlExecutor sqlExecutor;
    private final Function<String, String> datasourceIdOf;

    /**
     * @param datasourceIdOf 域 id → 数据源 id。评测必须在**问数所用的同一个库**上跑 gold
     */
    public EvalRunner(AskServiceFactory factory, SqlExecutor sqlExecutor,
                      Function<String, String> datasourceIdOf) {
        this.factory = factory;
        this.sqlExecutor = sqlExecutor;
        this.datasourceIdOf = datasourceIdOf;
    }

    public EvalReport run(EvalSet set, Consumer<String> progress) {
        List<EvalReport.CaseResult> results = new ArrayList<>();
        int passed = 0;
        int containsPassed = 0;
        int empty = 0;
        int errors = 0;
        int goldErrors = 0;
        long[] durations = new long[set.cases().size()];
        int durationCount = 0;

        String datasourceId;
        try {
            datasourceId = datasourceIdOf.apply(set.domainId());
        } catch (RuntimeException e) {
            // 连数据源都解析不出来：整批都是环境问题，逐条记 ERROR 比抛出去更有信息量
            for (EvalCase c : set.cases()) {
                results.add(new EvalReport.CaseResult(c.id(), c.difficulty().name(), c.question(),
                        EvalReport.Status.ERROR, false, "无法解析域的数据源：" + e.getMessage(),
                        c.goldSql(), null, null, 0, 0, 0, c.tags()));
            }
            return build(set, results, 0, 0, 0, set.cases().size(), 0, durations, durationCount);
        }

        int index = 0;
        for (EvalCase c : set.cases()) {
            index++;
            progress.accept("[%d/%d] %s — %s".formatted(
                    index, set.cases().size(), c.id(), c.question()));

            // ① gold 先跑。它跑不起来说明用例本身有问题，没必要再花一次模型调用
            QueryResult gold;
            try {
                gold = sqlExecutor.execute(datasourceId,
                        new RenderedSql(c.goldSql(), List.of(), List.of(), null));
            } catch (RuntimeException e) {
                goldErrors++;
                results.add(new EvalReport.CaseResult(c.id(), c.difficulty().name(), c.question(),
                        EvalReport.Status.GOLD_ERROR, false, "gold SQL 执行失败：" + brief(e),
                        c.goldSql(), null, null, 0, 0, 0, c.tags()));
                progress.accept("    → GOLD_ERROR");
                continue;
            }

            // ② 问一次
            long t0 = System.currentTimeMillis();
            AskResult result;
            try {
                AskFacade service = factory.forDomain(set.domainId());
                AskRequest askRequest = new AskRequest(
                        // userId 决定权限（必须是能通过域授权的身份）；
                        // username 进审计，加上 eval: 前缀，便于把评测流量与真实流量分开
                        c.question(), set.userId(), "eval:" + set.name(), set.roles(),
                        "eval-" + set.domainId() + "-" + c.id(), set.assistantId());

                // 分析用例走编排入口；其余走单查询。
                // 两者最终都落在同一个 AskResult 上，故下面的记录逻辑只有一份
                result = c.analyze()
                        ? service.analyzeStreaming(askRequest, event -> { })
                        : service.askStreaming(askRequest, event -> { });
            } catch (Exception e) {
                errors++;
                durations[durationCount++] = System.currentTimeMillis() - t0;
                results.add(new EvalReport.CaseResult(c.id(), c.difficulty().name(), c.question(),
                        EvalReport.Status.ERROR, false, brief(e),
                        c.goldSql(), null, null, gold.rowCount(), 0,
                        durations[durationCount - 1], c.tags()));
                progress.accept("    → ERROR " + brief(e));
                continue;
            }

            long duration = System.currentTimeMillis() - t0;
            durations[durationCount++] = duration;

            // ③a 分析用例：不看结果集，看"事实是否命中 + 结论是否可信"
            if (c.analyze()) {
                AnalysisCaseChecker.Verdict verdict =
                        AnalysisCaseChecker.check(c.analysis(), result.analysis());
                boolean ok = verdict.ok();
                if (ok) {
                    passed++;
                    containsPassed++;
                } else if (result.analysis() == null) {
                    empty++;
                }
                results.add(new EvalReport.CaseResult(
                        c.id(), c.difficulty().name(), c.question(),
                        ok ? EvalReport.Status.PASS : EvalReport.Status.FAIL, ok, verdict.reason(),
                        c.goldSql(),
                        truncate(AnalysisCaseChecker.digest(result.analysis())), "ANALYSIS",
                        gold.rowCount(),
                        result.analysis() == null ? 0 : result.analysis().queryCount(),
                        duration, c.tags()));
                progress.accept("    → " + (ok ? "PASS" : "FAIL") + " " + verdict.reason()
                        + " (" + duration + "ms)");
                continue;
            }

            // ③b 比对。两种口径都算：
            //    严格 EX（列数必须一致，与公开基准一致）是主指标；
            //    包含式 EX 允许"多返回了列"，用来区分"答案错"与"话多"
            QueryResult actual = result.result();
            boolean anyOrder = c.columnOrder() == EvalCase.ColumnOrder.ANY;
            ResultSetComparator.Verdict strict = ResultSetComparator.compare(
                    gold, actual, c.ordered(), anyOrder, false);
            boolean contains = strict.equal()
                    || ResultSetComparator.compare(gold, actual, c.ordered(), anyOrder, true).equal();

            EvalReport.Status status;
            if (strict.equal()) {
                status = EvalReport.Status.PASS;
                passed++;
            } else if (actual != null && actual.rows().isEmpty() && !gold.rows().isEmpty()) {
                status = EvalReport.Status.EMPTY;
                empty++;
            } else {
                status = EvalReport.Status.FAIL;
            }
            if (contains) {
                containsPassed++;
            }

            results.add(new EvalReport.CaseResult(
                    c.id(), c.difficulty().name(), c.question(), status, contains, strict.reason(),
                    c.goldSql(), truncate(result.sqlText()), result.mode() == null ? null : result.mode().name(),
                    gold.rowCount(), actual == null ? 0 : actual.rowCount(),
                    duration, c.tags()));

            progress.accept("    → " + status + (contains && status != EvalReport.Status.PASS
                    ? "（数据正确，多返回了列）" : "") + " (" + duration + "ms)");
        }

        return build(set, results, passed, containsPassed, empty, errors, goldErrors, durations, durationCount);
    }

    private EvalReport build(EvalSet set, List<EvalReport.CaseResult> results,
                             int passed, int containsPassed, int empty, int errors, int goldErrors,
                             long[] durations, int durationCount) {
        int total = results.size();
        int denominator = total - goldErrors;
        int failed = denominator - passed;
        // 静默语义错误的判据用**包含式**：多返回列不算"答错"。
        // 若用严格口径，模型多带一列上下文就会被记成一次"错误但不报错"，
        // 那个指标会失去意义（它要抓的是数据错，不是话多）
        int containsFailed = denominator - containsPassed;
        int silent = Math.max(0, containsFailed - errors);

        double passRate = denominator == 0 ? 0.0 : (double) passed / denominator;
        double containsRate = denominator == 0 ? 0.0 : (double) containsPassed / denominator;
        double emptyRate = denominator == 0 ? 0.0 : (double) empty / denominator;
        double silentRate = denominator == 0 ? 0.0 : (double) silent / denominator;

        Map<String, EvalReport.DifficultyStat> byDifficulty = new LinkedHashMap<>();
        for (EvalCase.Difficulty d : EvalCase.Difficulty.values()) {
            int t = 0;
            int p = 0;
            for (EvalReport.CaseResult r : results) {
                if (d.name().equals(r.difficulty()) && r.status() != EvalReport.Status.GOLD_ERROR) {
                    t++;
                    if (r.status() == EvalReport.Status.PASS) {
                        p++;
                    }
                }
            }
            if (t > 0) {
                byDifficulty.put(d.name(), new EvalReport.DifficultyStat(t, p));
            }
        }

        String mode = null;
        for (EvalReport.CaseResult r : results) {
            if (r.mode() == null) {
                continue;
            }
            if (mode == null) {
                mode = r.mode();
            } else if (!mode.equals(r.mode())) {
                mode = "MIXED";
                break;
            }
        }

        long avg = 0;
        long p50 = 0;
        if (durationCount > 0) {
            long sum = 0;
            long[] used = new long[durationCount];
            for (int i = 0; i < durationCount; i++) {
                used[i] = durations[i];
                sum += durations[i];
            }
            java.util.Arrays.sort(used);
            avg = sum / durationCount;
            p50 = used[durationCount / 2];
        }

        return new EvalReport(
                set.name(), set.domainId(), mode, set.primaryMetric().name(),
                OffsetDateTime.now(ZoneOffset.ofHours(8)).toString(),
                total, passed, containsPassed, failed, empty, errors, goldErrors,
                passRate, containsRate, emptyRate, silentRate, avg, p50, byDifficulty, results);
    }

    private static String brief(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) {
            m = e.getClass().getSimpleName();
        }
        return m.length() <= 300 ? m : m.substring(0, 300) + "…";
    }

    private static String truncate(String sql) {
        if (sql == null) {
            return null;
        }
        return sql.length() <= 4000 ? sql : sql.substring(0, 4000) + "…（截断）";
    }
}

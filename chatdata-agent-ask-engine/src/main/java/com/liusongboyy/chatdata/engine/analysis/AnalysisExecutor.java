package com.liusongboyy.chatdata.engine.analysis;

import com.liusongboyy.chatdata.engine.datasource.QueryResult;
import com.liusongboyy.chatdata.engine.datasource.SqlExecutor;
import com.liusongboyy.chatdata.engine.datasource.dialect.SqlDialect;
import com.liusongboyy.chatdata.engine.nlsql.AskSqlRenderer;
import com.liusongboyy.chatdata.engine.nlsql.RenderedSql;
import com.liusongboyy.chatdata.engine.permission.PermissionDecision;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 逐步执行分析计划。
 *
 * <h2>每个步骤都走单查询那条路径</h2>
 *
 * <p>{@link AskSqlRenderer#render} 与 {@link SqlExecutor#execute} 与单查询完全同一份代码。
 * 这意味着行级权限、脱敏、只读、行数上限**不需要为编排再实现一遍**——
 * 它们会按步骤自动生效。这也正是"步骤必须是 DSL"的全部理由。
 *
 * <h2>失败是局部的</h2>
 *
 * <p>某一步失败只会让该步标记为失败，其余照常执行。整次分析失败才是更糟的结局：
 * 用户拿不到任何结果，而实际上大部分数据是查得到的。
 * 失败原因会进 {@link StepResult#error()}，最终由结论区明说。
 */
public final class AnalysisExecutor {

    /** 上限：一次分析最多花这么久。超过就停止后续步骤，已完成照常返回。 */
    public static final long DEFAULT_MAX_DURATION_MS = 30_000;
    /** 上限：所有步骤的行数合计。防止一次分析把结果攒成几十万行。 */
    public static final int DEFAULT_MAX_TOTAL_ROWS = 5_000;

    private final SemanticModel model;
    private final SqlDialect dialect;
    private final SqlExecutor sqlExecutor;
    private final long maxDurationMs;
    private final int maxTotalRows;

    public AnalysisExecutor(SemanticModel model, SqlDialect dialect, SqlExecutor sqlExecutor) {
        this(model, dialect, sqlExecutor, DEFAULT_MAX_DURATION_MS, DEFAULT_MAX_TOTAL_ROWS);
    }

    public AnalysisExecutor(SemanticModel model, SqlDialect dialect, SqlExecutor sqlExecutor,
                            long maxDurationMs, int maxTotalRows) {
        this.model = model;
        this.dialect = dialect;
        this.sqlExecutor = sqlExecutor;
        this.maxDurationMs = maxDurationMs;
        this.maxTotalRows = maxTotalRows;
    }

    /**
     * 执行计划。
     *
     * @param onStep 每开始一步时回调（步骤序号，从 1 开始，总数）
     */
    public AnalysisResult execute(AnalysisPlan plan, PermissionDecision decision,
                                  LocalDate today, java.util.function.BiConsumer<Integer, Integer> onStep) {
        long t0 = System.currentTimeMillis();
        List<StepResult> steps = new ArrayList<>(plan.steps().size());
        boolean truncated = false;
        int rowsSoFar = 0;
        int executed = 0;

        for (PlanStep step : plan.steps()) {
            if (System.currentTimeMillis() - t0 > maxDurationMs) {
                truncated = true;
                break;
            }
            if (onStep != null) {
                onStep.accept(executed + 1, plan.steps().size());
            }
            executed++;

            RenderedSql rendered = null;
            long stepStart = System.currentTimeMillis();
            try {
                rendered = AskSqlRenderer.render(step.dsl(), model, dialect, decision, today);
                QueryResult rows = sqlExecutor.execute(model.domain().datasourceId(), rendered);
                rowsSoFar += rows.rowCount();

                steps.add(StepResult.ok(step.id(), step.label(), step.purpose(), step.dsl(),
                        rendered, rows, maskedOf(decision),
                        System.currentTimeMillis() - stepStart));

                if (rowsSoFar > maxTotalRows) {
                    truncated = true;
                    break;
                }
            } catch (RuntimeException e) {
                // 单步失败不影响其余步骤。原因照原样带出去——单查询链路也是这么做的
                steps.add(StepResult.failed(step.id(), step.label(), step.purpose(),
                        step.dsl(), rendered, messageOf(e),
                        System.currentTimeMillis() - stepStart));
            }
        }

        List<Finding> findings = InsightCalculator.compute(steps, model);
        return new AnalysisResult(plan.intent(), steps, findings, null, executed, truncated);
    }

    private static java.util.Set<String> maskedOf(PermissionDecision decision) {
        return decision instanceof PermissionDecision.Allowed allowed
                ? allowed.maskedColumns() : java.util.Set.of();
    }

    private static String messageOf(RuntimeException e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
    }
}

package com.liusongboyy.chatdata.engine.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liusongboyy.chatdata.engine.datasource.QueryResult;
import com.liusongboyy.chatdata.engine.datasource.SqlExecutor;
import com.liusongboyy.chatdata.engine.datasource.dialect.SqlDialects;
import com.liusongboyy.chatdata.engine.nlsql.AskDsl;
import com.liusongboyy.chatdata.engine.nlsql.RenderedSql;
import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import com.liusongboyy.chatdata.engine.permission.PermissionContext;
import com.liusongboyy.chatdata.engine.permission.PermissionDecision;
import com.liusongboyy.chatdata.engine.permission.PermissionGuard;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;
import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分析执行器。
 *
 * <h2>这个类必须自己测，且不能连库</h2>
 *
 * <p>它决定的不是"SQL 对不对"（那是渲染器的测试），而是三条编排特有的性质：
 * 单步失败不拖垮整体、上限真的会截断、finding 只从成功步骤里算。
 * 这三条一旦坏了，表现是"结论少了一块"而不是报错。
 */
class AnalysisExecutorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    /** 只按 SQL 文本决定返回什么，用来模拟"某一步失败"。 */
    private static SqlExecutor fakeExecutor(Set<String> failingMetrics) {
        return new SqlExecutor(null, 1000, 30) {
            @Override
            public QueryResult execute(String datasourceId, RenderedSql sql) {
                if (sql.columns().stream()
                        .anyMatch(c -> failingMetrics.stream().anyMatch(m -> c.name().contains(m)))) {
                    throw new SqlExecutionException("模拟失败");
                }
                return new QueryResult(List.of("区域", "订单金额"), List.of(
                        List.of("华东", new BigDecimal("600")),
                        List.of("华南", new BigDecimal("400"))));
            }
        };
    }

    private static SemanticModel model() {
        RegisteredTable t = new RegisteredTable("t1", "f_order", "订单表",
                RegisteredTable.Mode.TABLE, null, true, List.of(
                new ColumnDef("region", "varchar", Aggregation.NONE, "区域", true),
                new ColumnDef("amount", "decimal", Aggregation.SUM, "订单金额", true),
                new ColumnDef("cnt", "bigint", Aggregation.COUNT, "订单数", true)));
        Domain d = new Domain("d1", "订单域", "ds1", "MYSQL5.7", true, List.of(t),
                List.of(), perm(), List.of(), List.of());
        return new SemanticModel(d);
    }

    private static DomainPermission perm() {
        try {
            return DomainPermission.from(new ObjectMapper().readTree(
                    "{\"v\":1,\"grantRoles\":[\"分析师\"],\"unrestrictedRoles\":[\"分析师\"]}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static PermissionDecision.Allowed allowed() {
        return (PermissionDecision.Allowed) PermissionGuard.check(
                null, perm(), PermissionContext.of("u1", Set.of("分析师")));
    }

    private static PlanStep step(String id, String metric) {
        return new PlanStep(id, id, "", new AskDsl(List.of(metric), List.of("区域"), null,
                List.of(), AskDsl.Granularity.DAY, List.of(), null, List.of(), null));
    }

    @Test
    @DisplayName("★ 某一步失败 → 其余步骤照常执行，失败那步带原因")
    void failedStepDoesNotAbort() {
        AnalysisPlan plan = new AnalysisPlan("x",
                List.of(step("s1", "订单金额"), step("s2", "订单数"), step("s3", "订单金额")));
        AnalysisExecutor executor = new AnalysisExecutor(model(), SqlDialects.of("MYSQL5.7"),
                fakeExecutor(Set.of("订单数")));

        AnalysisResult result = executor.execute(plan, allowed(), TODAY, (i, n) -> { });

        assertThat(result.steps()).hasSize(3);
        assertThat(result.steps().get(0).isOk()).isTrue();
        assertThat(result.steps().get(1).status()).isEqualTo(StepStatus.FAILED);
        assertThat(result.steps().get(1).error()).contains("模拟失败");
        assertThat(result.steps().get(2).isOk()).as("失败不中断后续").isTrue();
        assertThat(result.allFailed()).isFalse();
    }

    @Test
    @DisplayName("★ 全部失败 → allFailed，且每个都带原因")
    void allFailed() {
        AnalysisPlan plan = new AnalysisPlan("x",
                List.of(step("s1", "订单数"), step("s2", "订单数")));
        AnalysisResult result = new AnalysisExecutor(model(), SqlDialects.of("MYSQL5.7"),
                fakeExecutor(Set.of("订单数"))).execute(plan, allowed(), TODAY, (i, n) -> { });

        assertThat(result.allFailed()).isTrue();
        assertThat(result.steps()).allSatisfy(s -> {
            assertThat(s.status()).isEqualTo(StepStatus.FAILED);
            assertThat(s.error()).isNotBlank();
        });
        assertThat(result.findings()).isEmpty();
    }

    @Test
    @DisplayName("finding 只从成功步骤里算，失败的跳过")
    void findingsFromOkStepsOnly() {
        AnalysisPlan plan = new AnalysisPlan("x",
                List.of(step("s1", "订单金额"), step("s2", "订单数")));
        AnalysisResult result = new AnalysisExecutor(model(), SqlDialects.of("MYSQL5.7"),
                fakeExecutor(Set.of("订单数"))).execute(plan, allowed(), TODAY, (i, n) -> { });

        assertThat(result.findings())
                .isNotEmpty()
                .allSatisfy(f -> assertThat(f.stepId()).isEqualTo("s1"));
    }

    @Test
    @DisplayName("★ 行数超过总上限 → 截断并标记，不继续跑后续步骤")
    void truncatesOnRowCap() {
        AnalysisPlan plan = new AnalysisPlan("x", List.of(
                step("s1", "订单金额"), step("s2", "订单金额"), step("s3", "订单金额")));
        // 每步返回 2 行；上限设 3，第二步就会超
        AnalysisResult result = new AnalysisExecutor(model(), SqlDialects.of("MYSQL5.7"),
                fakeExecutor(Set.of()), 30_000, 3).execute(plan, allowed(), TODAY, (i, n) -> { });

        assertThat(result.truncated()).isTrue();
        assertThat(result.steps()).hasSize(2);
        assertThat(result.queryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("步骤回调按顺序给出 (i, n)")
    void reportsProgress() {
        AnalysisPlan plan = new AnalysisPlan("x", List.of(
                step("s1", "订单金额"), step("s2", "订单金额")));
        List<String> calls = new ArrayList<>();

        new AnalysisExecutor(model(), SqlDialects.of("MYSQL5.7"), fakeExecutor(Set.of()))
                .execute(plan, allowed(), TODAY, (i, n) -> calls.add(i + "/" + n));

        assertThat(calls).containsExactly("1/2", "2/2");
    }
}

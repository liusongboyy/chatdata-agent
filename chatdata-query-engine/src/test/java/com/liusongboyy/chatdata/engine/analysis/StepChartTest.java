package com.liusongboyy.chatdata.engine.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liusongboyy.chatdata.engine.chat.ChartSpec;
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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分析步骤的图表推荐。
 *
 * <h2>为什么每步各自推荐</h2>
 *
 * <p>一次分析里的步骤通常分别是「趋势」「构成」「对比」。用同一种图会把三种意图
 * 压成一个形状：时间序列画成柱看不出走势，分类构成画成折线则毫无意义。
 *
 * <p>更要紧的是**单值步骤不能画图**：一根柱子是把数字包装成图，比直接写数字更难读。
 */
class StepChartTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    private static SemanticModel model() {
        RegisteredTable t = new RegisteredTable("t1", "ins_policy", "保单表",
                RegisteredTable.Mode.TABLE, null, true, List.of(
                new ColumnDef("effect_date", "date", Aggregation.NONE, "生效日期", true),
                new ColumnDef("region", "varchar", Aggregation.NONE, "区域", true),
                new ColumnDef("premium", "decimal", Aggregation.SUM, "保费", true)));
        Domain d = new Domain("d1", "保险域", "ds1", "MYSQL5.7", true, List.of(t),
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

    private static AnalysisResult run(AskDsl dsl, QueryResult rows) {
        SqlExecutor executor = new SqlExecutor(null, 1000, 30) {
            @Override
            public QueryResult execute(String datasourceId, RenderedSql sql) {
                return rows;
            }
        };
        AnalysisPlan plan = new AnalysisPlan("x",
                List.of(new PlanStep("s1", "步骤", "", dsl)));
        return new AnalysisExecutor(model(), SqlDialects.of("MYSQL5.7"), executor)
                .execute(plan, allowed(), TODAY, (i, n) -> { });
    }

    @Test
    @DisplayName("★ 时间序列步骤 → 折线")
    void temporalStepIsLine() {
        AskDsl dsl = new AskDsl(List.of("保费"), List.of("生效日期"), null, List.of(),
                AskDsl.Granularity.MONTH, List.of(), null, List.of(), null);

        ChartSpec chart = run(dsl, new QueryResult(List.of("生效日期", "保费"), List.of(
                List.of("2025-01", new BigDecimal("100")),
                List.of("2025-02", new BigDecimal("120")),
                List.of("2025-03", new BigDecimal("90"))))).steps().get(0).chart();

        assertThat(chart).isNotNull();
        assertThat(chart.form()).isEqualTo(ChartSpec.Form.LINE);
    }

    @Test
    @DisplayName("★ 分类构成步骤 → 柱")
    void categoryStepIsBar() {
        AskDsl dsl = new AskDsl(List.of("保费"), List.of("区域"), null, List.of(),
                AskDsl.Granularity.DAY, List.of(), null, List.of(), null);

        ChartSpec chart = run(dsl, new QueryResult(List.of("区域", "保费"), List.of(
                List.of("华东", new BigDecimal("600")),
                List.of("华南", new BigDecimal("400"))))).steps().get(0).chart();

        assertThat(chart.form()).isEqualTo(ChartSpec.Form.BAR);
    }

    @Test
    @DisplayName("★ 单值步骤 → 数字卡，不画图（一根柱子是把数字包装成图）")
    void scalarStepIsStat() {
        AskDsl dsl = new AskDsl(List.of("保费"), List.of(), null, List.of(),
                AskDsl.Granularity.DAY, List.of(), null, List.of(), null);

        ChartSpec chart = run(dsl, new QueryResult(List.of("保费"),
                List.of(List.of(new BigDecimal("1234"))))).steps().get(0).chart();

        assertThat(chart.form()).isEqualTo(ChartSpec.Form.STAT);
    }

    @Test
    @DisplayName("失败步骤没有图表")
    void failedStepHasNoChart() {
        AnalysisResult result = new AnalysisExecutor(model(), SqlDialects.of("MYSQL5.7"),
                new SqlExecutor(null, 1000, 30) {
                    @Override
                    public QueryResult execute(String datasourceId, RenderedSql sql) {
                        throw new SqlExecutor.SqlExecutionException("炸了");
                    }
                }).execute(new AnalysisPlan("x", List.of(new PlanStep("s1", "步骤", "",
                        new AskDsl(List.of("保费"), List.of("区域"), null, List.of())))),
                allowed(), TODAY, (i, n) -> { });

        assertThat(result.steps().get(0).isOk()).isFalse();
        assertThat(result.steps().get(0).chart()).isNull();
    }
}

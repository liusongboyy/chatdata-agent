package com.liusongboyy.chatdata.engine.chat;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liusongboyy.chatdata.engine.agent.DslGenerationException;
import com.liusongboyy.chatdata.engine.analysis.AnalysisPlan;
import com.liusongboyy.chatdata.engine.analysis.AnalysisPlanSource;
import com.liusongboyy.chatdata.engine.agent.Generated;
import com.liusongboyy.chatdata.engine.agent.TokenUsage;
import com.liusongboyy.chatdata.engine.analysis.NarrativeSource;
import com.liusongboyy.chatdata.engine.analysis.PlanStep;
import com.liusongboyy.chatdata.engine.datasource.QueryResult;
import com.liusongboyy.chatdata.engine.datasource.SqlExecutor;
import com.liusongboyy.chatdata.engine.datasource.dialect.SqlDialects;
import com.liusongboyy.chatdata.engine.nlsql.AskDsl;
import com.liusongboyy.chatdata.engine.nlsql.RenderedSql;
import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;
import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 编排服务：叙述、白名单、审计、降级。
 *
 * <h2>这里测的是"编排特有的决策"</h2>
 *
 * <p>取数与计算由 {@code AnalysisExecutorTest} / {@code InsightCalculatorTest} 覆盖。
 * 这里只关心几件只有 {@link AnalysisService} 会做的判断：
 * 叙述什么时候被采纳、什么时候被退回、降级是否带说明。
 */
class AnalysisServiceTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static SemanticModel model() {
        RegisteredTable t = new RegisteredTable("t1", "f_order", "订单表",
                RegisteredTable.Mode.TABLE, null, true, List.of(
                new ColumnDef("region", "varchar", Aggregation.NONE, "区域", true),
                new ColumnDef("amount", "decimal", Aggregation.SUM, "订单金额", true)));
        Domain d = new Domain("d1", "订单域", "ds1", "MYSQL5.7", true, List.of(t),
                List.of(), perm(), List.of(), List.of());
        return new SemanticModel(d);
    }

    private static DomainPermission perm() {
        try {
            return DomainPermission.from(M.readTree(
                    "{\"v\":1,\"grantRoles\":[\"分析师\"],\"unrestrictedRoles\":[\"分析师\"]}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SqlExecutor executor() {
        return new SqlExecutor(null, 1000, 30) {
            @Override
            public QueryResult execute(String datasourceId, RenderedSql sql) {
                return new QueryResult(List.of("区域", "订单金额"), List.of(
                        List.of("华东", new BigDecimal("600")),
                        List.of("华南", new BigDecimal("400"))));
            }
        };
    }

    private static AnalysisPlan plan() {
        AskDsl dsl = new AskDsl(List.of("订单金额"), List.of("区域"), null, List.of(),
                AskDsl.Granularity.DAY, List.of(), null, List.of(), null);
        return new AnalysisPlan("各区域订单金额构成",
                List.of(new PlanStep("s1", "各区域订单金额", "看构成", dsl)));
    }

    private static AskRequest request() {
        return new AskRequest("各区域订单金额怎么分", "u1", "u1",
                Set.of("分析师"), "sess", null);
    }

    private static AnalysisService service(NarrativeSource narrative) {
        return new AnalysisService(model(), SqlDialects.of("MYSQL5.7"), executor(), null,
                (q, p, u, s) -> Generated.of(plan()), narrative);
    }

    @Test
    @DisplayName("★ 叙述引用了 facts 里的数字 → 采纳")
    void acceptsGroundedNarrative() {
        // 600/1000 = 60.0%、400/1000 = 40.0%
        // 注意：**只引用算过的数字**。40% 是"其余"，计算器没单独产出它，
        // 所以写进来同样会被白名单挡下——这正是它该有的严格程度
        AskResult result = service((p, u, s) -> Generated.of("华东占 60%，集中度较高。"))
                .analyze(request(), e -> { });

        assertThat(result.analysis()).isNotNull();
        assertThat(result.analysis().narrative()).isEqualTo("华东占 60%，集中度较高。");
        assertThat(result.analysis().findings()).isNotEmpty();
    }

    @Test
    @DisplayName("★ 叙述编了数字 → 退回确定性文本，且每个数字可溯源")
    void rejectsFabricatedNumber() {
        AskResult result = service((p, u, s) -> Generated.of("华东占 93%，其余分散在多个区域。"))
                .analyze(request(), e -> { });

        String narrative = result.analysis().narrative();
        assertThat(narrative).doesNotContain("93");
        assertThat(narrative).contains("60.0%");
    }

    @Test
    @DisplayName("叙述为空 / 抛异常 → 退回确定性文本，分析本身仍然成功")
    void narrativeFailureDoesNotFailAnalysis() {
        AskResult empty = service((p, u, s) -> Generated.of(null)).analyze(request(), e -> { });
        assertThat(empty.analysis().narrative()).contains("60.0%");

        AskResult boom = service((p, u, s) -> {
            throw new IllegalStateException("模型挂了");
        }).analyze(request(), e -> { });
        assertThat(boom.analysis().steps().get(0).isOk()).isTrue();
        assertThat(boom.analysis().narrative()).contains("60.0%");
    }

    @Test
    @DisplayName("★ 计划生成失败 → 抛 DslGenerationException（由 AskService 回退单查询）")
    void planFailurePropagates() {
        AnalysisService failing = new AnalysisService(model(), SqlDialects.of("MYSQL5.7"),
                executor(), null,
                (q, p, u, s) -> {
                    throw new DslGenerationException("拆不出来");
                }, null);

        assertThatThrownBy(() -> failing.analyze(request(), e -> { }))
                .isInstanceOf(DslGenerationException.class)
                .hasMessageContaining("拆不出来");
    }

    @Test
    @DisplayName("结果里第一个成功步骤填充旧字段，未适配的前端也看得到一张表")
    void primaryStepFillsLegacyFields() {
        AskResult result = service(null).analyze(request(), e -> { });

        assertThat(result.result().rowCount()).as("旧字段填了第一张表").isEqualTo(2);
        assertThat(result.sqlText()).isNotBlank();
        assertThat(result.understanding()).isNotNull();
    }

    @Test
    @DisplayName("发出 PLANING / EXECUTING / SYNTHESIZING 三个阶段")
    void emitsAnalysisStages() {
        List<AskEvent.Stage> stages = new java.util.ArrayList<>();

        service(null).analyze(request(), e -> {
            if (e instanceof AskEvent.Progress p) {
                stages.add(p.stage());
            }
        });

        assertThat(stages).contains(
                AskEvent.Stage.CHECKING_PERMISSION,
                AskEvent.Stage.PLANNING,
                AskEvent.Stage.EXECUTING,
                AskEvent.Stage.SYNTHESIZING);
    }

    @Test
    @DisplayName("每一步都真的执行了，queryCount 与步骤数一致")
    void countsQueries() {
        AskResult result = service(null).analyze(request(), e -> { });

        assertThat(result.analysis().queryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("没有计划来源时 available() 为 false，AskService 不会走编排")
    void notAvailableWithoutPlanSource() {
        AnalysisService s = new AnalysisService(model(), SqlDialects.of("MYSQL5.7"),
                executor(), null, null, null);

        assertThat(s.available()).isFalse();
    }
}

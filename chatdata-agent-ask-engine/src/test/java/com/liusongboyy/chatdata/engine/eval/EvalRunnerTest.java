package com.liusongboyy.chatdata.engine.eval;

import com.liusongboyy.chatdata.engine.chat.AskEvent;
import com.liusongboyy.chatdata.engine.chat.AskFacade;
import com.liusongboyy.chatdata.engine.chat.AskRequest;
import com.liusongboyy.chatdata.engine.chat.AskResult;
import com.liusongboyy.chatdata.engine.chat.AskServiceFactory;
import com.liusongboyy.chatdata.engine.chat.ChartSpec;
import com.liusongboyy.chatdata.engine.datasource.QueryResult;
import com.liusongboyy.chatdata.engine.datasource.SqlExecutor;
import com.liusongboyy.chatdata.engine.nlsql.RenderedSql;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 评测编排：一条用例失败不能拖垮整批，且各类失败的归类必须正确。
 *
 * <p>**不调模型、不连库**：这里验的是"分数怎么算"，不是"模型答得对不对"。
 * 后者由真跑评测集回答。
 */
class EvalRunnerTest {

    private AskServiceFactory factory;
    private SqlExecutor executor;
    private AskFacade facade;

    @BeforeEach
    void setUp() {
        factory = mock(AskServiceFactory.class);
        executor = mock(SqlExecutor.class);
        facade = mock(AskFacade.class);
        when(factory.forDomain("17")).thenReturn(facade);
    }

    private static AskResult result(QueryResult data) {
        return new AskResult(null, null, "SELECT ...", data, Set.of(), 5,
                ChartSpec.table("test"), null, null, Domain.Mode.RAW);
    }

    private static EvalSet set(List<EvalCase> cases) {
        return new EvalSet("test-set", "17", "admin", Set.of("管理员"), null, cases);
    }

    private static EvalCase c(String id, String question, String goldSql) {
        return new EvalCase(id, EvalCase.Difficulty.MEDIUM, question, goldSql,
                false, null, List.of(), null);
    }

    @Test
    @DisplayName("★ 一条用例失败不中断整批；PASS/FAIL/EMPTY/ERROR/GOLD_ERROR 各归各位")
    void classifiesEveryOutcome() {
        EvalSet set = set(List.of(
                c("pass", "PASS 问题", "SELECT 3 /* pass */"),
                c("fail", "FAIL 问题", "SELECT 3 /* fail */"),
                c("empty", "EMPTY 问题", "SELECT 3 /* empty */"),
                c("error", "ERROR 问题", "SELECT 3 /* error */"),
                c("gold", "PASS 问题", "SELECT 3 /* gold-bad */")));

        QueryResult three = new QueryResult(List.of("cnt"), List.of(List.of(3)));
        QueryResult empty = new QueryResult(List.of("cnt"), List.of());

        when(executor.execute(eq("17"), any(RenderedSql.class))).thenAnswer(inv -> {
            RenderedSql sql = inv.getArgument(1);
            if (sql.sql().contains("gold-bad")) {
                throw new IllegalStateException("gold SQL 写错了");
            }
            return three;
        });

        when(facade.askStreaming(any(AskRequest.class), any())).thenAnswer(inv -> {
            AskRequest req = inv.getArgument(0);
            ((Consumer<AskEvent>) inv.getArgument(1)).accept(new AskEvent.Started(req.question()));
            if (req.question().contains("ERROR")) {
                throw new IllegalStateException("模型超时");
            }
            if (req.question().contains("EMPTY")) {
                return result(empty);
            }
            if (req.question().contains("FAIL")) {
                return result(new QueryResult(List.of("cnt"), List.of(List.of(2))));
            }
            return result(three);
        });

        EvalReport report = new EvalRunner(factory, executor, id -> "17")
                .run(set, line -> {
                });

        assertThat(report.total()).isEqualTo(5);
        assertThat(report.passed()).isEqualTo(1);
        assertThat(report.containsPassed()).isEqualTo(1);
        assertThat(report.goldErrors()).isEqualTo(1);
        assertThat(report.errors()).isEqualTo(1);
        assertThat(report.empty()).isEqualTo(1);
        // 分母扣掉 gold 错误：4 条
        assertThat(report.passRate()).isEqualTo(0.25);
        // 静默错误 = 执行成功但答案不对（FAIL + EMPTY），不含执行异常
        assertThat(report.silentErrorRate()).isEqualTo(0.5);

        assertThat(report.cases()).extracting(EvalReport.CaseResult::status)
                .containsExactly(
                        EvalReport.Status.PASS,
                        EvalReport.Status.FAIL,
                        EvalReport.Status.EMPTY,
                        EvalReport.Status.ERROR,
                        EvalReport.Status.GOLD_ERROR);

        // 报告要能定位失败：生成的 SQL 与 gold 都在
        EvalReport.CaseResult fail = report.cases().get(1);
        assertThat(fail.generatedSql()).isEqualTo("SELECT ...");
        assertThat(fail.goldSql()).contains("fail");
    }

    @Test
    @DisplayName("多返回列：严格 EX 失败、包含式 EX 通过，两者分开计数")
    void extraColumnsAreCountedSeparately() {
        EvalSet set = set(List.of(c("extra", "EXTRA 问题", "SELECT 3 /* extra */")));

        QueryResult verbose = new QueryResult(
                List.of("cnt", "note"), List.of(List.of(3, "说明")));
        when(executor.execute(eq("17"), any(RenderedSql.class)))
                .thenReturn(new QueryResult(List.of("cnt"), List.of(List.of(3))));
        when(facade.askStreaming(any(AskRequest.class), any())).thenAnswer(inv -> result(verbose));

        EvalReport report = new EvalRunner(factory, executor, id -> "17")
                .run(set, line -> {
                });

        assertThat(report.passed()).isZero();
        assertThat(report.containsPassed()).isEqualTo(1);
        assertThat(report.passRate()).isZero();
        assertThat(report.containsRate()).isEqualTo(1.0);
        assertThat(report.cases()).singleElement()
                .satisfies(r -> {
                    assertThat(r.status()).isEqualTo(EvalReport.Status.FAIL);
                    assertThat(r.containsPassed()).isTrue();
                });
    }

    @Test
    @DisplayName("分档统计扣除 gold 错误，且按难度分别报告")
    void difficultyStatsExcludeGoldErrors() {
        EvalSet set = new EvalSet("test-set", "17", "admin", Set.of("管理员"), null, List.of(
                new EvalCase("s1", EvalCase.Difficulty.SIMPLE, "PASS 简单", "SELECT 1",
                        false, null, List.of(), null),
                new EvalCase("s2", EvalCase.Difficulty.SIMPLE, "FAIL 简单", "SELECT 1",
                        false, null, List.of(), null),
                new EvalCase("h1", EvalCase.Difficulty.HARD, "PASS 困难", "SELECT 1 /* bad */",
                        false, null, List.of(), null)));

        QueryResult one = new QueryResult(List.of("cnt"), List.of(List.of(1)));
        when(executor.execute(eq("17"), any(RenderedSql.class))).thenAnswer(inv -> {
            RenderedSql sql = inv.getArgument(1);
            if (sql.sql().contains("bad")) {
                throw new IllegalStateException("boom");
            }
            return one;
        });
        when(facade.askStreaming(any(AskRequest.class), any())).thenAnswer(inv -> {
            AskRequest req = inv.getArgument(0);
            return req.question().contains("FAIL")
                    ? result(new QueryResult(List.of("cnt"), List.of(List.of(9))))
                    : result(one);
        });

        EvalReport report = new EvalRunner(factory, executor, id -> "17")
                .run(set, line -> {
                });

        assertThat(report.byDifficulty()).containsKeys("SIMPLE");
        assertThat(report.byDifficulty()).doesNotContainKey("HARD");
        assertThat(report.byDifficulty().get("SIMPLE").total()).isEqualTo(2);
        assertThat(report.byDifficulty().get("SIMPLE").passed()).isEqualTo(1);
    }

    @Test
    @DisplayName("数据源解析失败时整批记 ERROR，而不是抛出去")
    void datasourceFailureMarksAllErrors() {
        EvalSet set = set(List.of(c("one", "问题", "SELECT 1")));

        EvalReport report = new EvalRunner(factory, executor, id -> {
            throw new IllegalStateException("域未绑定数据源");
        }).run(set, line -> {
        });

        assertThat(report.errors()).isEqualTo(1);
        assertThat(report.cases()).singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo(EvalReport.Status.ERROR));
    }
}

package com.liusongboyy.chatdata.engine.analysis;

import com.liusongboyy.chatdata.engine.datasource.QueryResult;
import com.liusongboyy.chatdata.engine.nlsql.AskDsl;
import com.liusongboyy.chatdata.engine.nlsql.RenderedSql;
import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;
import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确定性洞察的计算。
 *
 * <h2>为什么每条都要断言具体数字</h2>
 *
 * <p>这些数字会直接进结论文本。算错时**不报错**，只是结论里写着一个错的百分比——
 * 而用户没有别的途径核对。所以测试断言到文本与 evidence 的数值，而不是"有产出"。
 *
 * <p>另一个重点是**不该算的时候不算**：脱敏列、多维度、合计为负、点太少，
 * 都应安静地不产出事实，而不是硬凑一条。
 */
class InsightCalculatorTest {

    private static SemanticModel model() {
        RegisteredTable orders = new RegisteredTable("t1", "f_order", "订单表",
                RegisteredTable.Mode.TABLE, null, true, List.of(
                new ColumnDef("order_date", "date", Aggregation.NONE, "统计日期", true),
                new ColumnDef("region", "varchar", Aggregation.NONE, "区域", true),
                new ColumnDef("amount", "decimal", Aggregation.SUM, "订单金额", true)));
        Domain domain = new Domain("d1", "订单域", "ds1", "MYSQL5.7", true,
                List.of(orders), List.of(), DomainPermission.from(null), List.of(), List.of());
        return new SemanticModel(domain);
    }

    private static RenderedSql sql(List<String> columns) {
        return new RenderedSql("SELECT 1",
                List.of(),
                columns.stream().map(c -> new RenderedSql.OutputColumn(c, c, false)).toList(),
                "t1");
    }

    private static StepResult step(String id, AskDsl dsl, List<String> columns,
                                   List<List<Object>> rows, Set<String> masked) {
        return StepResult.ok(id, id, "", dsl, sql(columns),
                new QueryResult(columns, rows), masked);
    }

    private static AskDsl dsl(List<String> metrics, List<String> dims,
                              AskDsl.Granularity g, AskDsl.Compare compare) {
        return new AskDsl(metrics, dims, null, List.of(), g,
                List.of(), null, List.of(), compare);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Nested
    @DisplayName("时间序列")
    class Trend {

        private StepResult monthly() {
            AskDsl dsl = dsl(List.of("订单金额"), List.of("统计日期"),
                    AskDsl.Granularity.MONTH, null);
            return step("s1", dsl, List.of("统计日期", "订单金额"), List.of(
                    List.of("2025-01", bd("100")),
                    List.of("2025-02", bd("120")),
                    List.of("2025-03", bd("90")),
                    List.of("2025-04", bd("150"))), Set.of());
        }

        @Test
        @DisplayName("★ 首末变化：100 → 150，+50%")
        void overallTrend() {
            Finding f = InsightCalculator.compute(List.of(monthly()), model()).get(0);

            assertThat(f.kind()).isEqualTo(Finding.Kind.TREND);
            assertThat(f.text()).contains("「2025-01」").contains("「2025-04」").contains("+50.0%");
            assertThat(f.evidence()).containsEntry("change", bd("50"));
        }

        @Test
        @DisplayName("★ 最大单期变化只找与整体同向的那一期：整体在涨 → 涨得最猛的是 4 月")
        void biggestSwing() {
            Finding f = InsightCalculator.compute(List.of(monthly()), model()).get(1);

            assertThat(f.text()).contains("2025-04").contains("66.7").contains("升");
            assertThat(f.evidence()).containsEntry("changePct", bd("66.666667"));
        }

        @Test
        @DisplayName("整体在跌时，找跌得最猛的那一期")
        void biggestDrop() {
            AskDsl dsl = dsl(List.of("订单金额"), List.of("统计日期"),
                    AskDsl.Granularity.MONTH, null);
            StepResult s = step("s1", dsl, List.of("统计日期", "订单金额"), List.of(
                    List.of("2025-01", bd("200")),
                    List.of("2025-02", bd("180")),
                    List.of("2025-03", bd("90")),
                    List.of("2025-04", bd("50"))), Set.of());

            Finding f = InsightCalculator.compute(List.of(s), model()).get(1);

            assertThat(f.text()).contains("2025-03").contains("50.0").contains("降");
        }

        @Test
        @DisplayName("行序被打乱也按时间排序——DB 不保证顺序")
        void sortsByPeriod() {
            AskDsl dsl = dsl(List.of("订单金额"), List.of("统计日期"),
                    AskDsl.Granularity.MONTH, null);
            StepResult shuffled = step("s1", dsl, List.of("统计日期", "订单金额"), List.of(
                    List.of("2025-03", bd("90")),
                    List.of("2025-01", bd("100")),
                    List.of("2025-04", bd("150")),
                    List.of("2025-02", bd("120"))), Set.of());

            Finding f = InsightCalculator.compute(List.of(shuffled), model()).get(0);
            assertThat(f.text()).contains("「2025-01」").contains("「2025-04」");
        }

        @Test
        @DisplayName("少于 3 个点不谈趋势")
        void tooFewPoints() {
            AskDsl dsl = dsl(List.of("订单金额"), List.of("统计日期"),
                    AskDsl.Granularity.MONTH, null);
            StepResult two = step("s1", dsl, List.of("统计日期", "订单金额"), List.of(
                    List.of("2025-01", bd("100")),
                    List.of("2025-02", bd("120"))), Set.of());

            assertThat(InsightCalculator.compute(List.of(two), model())).isEmpty();
        }

        @Test
        @DisplayName("★ 离群点：9 个点里的尖峰被抓出来")
        void outliers() {
            AskDsl dsl = dsl(List.of("订单金额"), List.of("统计日期"),
                    AskDsl.Granularity.MONTH, null);
            List<List<Object>> rows = new java.util.ArrayList<>();
            for (int i = 1; i <= 9; i++) {
                rows.add(List.of("2025-0" + i, bd(i == 5 ? "900" : "100")));
            }
            StepResult s = step("s1", dsl, List.of("统计日期", "订单金额"), rows, Set.of());

            assertThat(InsightCalculator.compute(List.of(s), model()))
                    .anySatisfy(f -> {
                        assertThat(f.kind()).isEqualTo(Finding.Kind.OUTLIER);
                        assertThat(f.text()).contains("2025-05").contains("900");
                    });
        }
    }

    @Nested
    @DisplayName("分类构成")
    class Contribution {

        private StepResult regions() {
            AskDsl dsl = dsl(List.of("订单金额"), List.of("区域"), AskDsl.Granularity.DAY, null);
            return step("s2", dsl, List.of("区域", "订单金额"), List.of(
                    List.of("华东", bd("600")),
                    List.of("华南", bd("250")),
                    List.of("华北", bd("100")),
                    List.of("西南", bd("50"))), Set.of());
        }

        @Test
        @DisplayName("★ 头部占比：华东 600 / 1000 = 60.0%")
        void topShare() {
            Finding f = InsightCalculator.compute(List.of(regions()), model()).get(0);

            assertThat(f.kind()).isEqualTo(Finding.Kind.CONTRIBUTION);
            assertThat(f.text()).contains("华东").contains("60.0%").contains("600").contains("1000");
            assertThat(f.evidence()).containsEntry("sharePct", bd("60.000000"));
        }

        @Test
        @DisplayName("超过 3 项时给前 3 合计占比")
        void topThree() {
            Finding f = InsightCalculator.compute(List.of(regions()), model()).get(1);

            assertThat(f.text()).contains("前 3 项").contains("华东、华南、华北").contains("95.0%");
        }

        @Test
        @DisplayName("合计为 0 或负数时不算占比——那会得出没有意义的百分比")
        void nonPositiveTotal() {
            AskDsl dsl = dsl(List.of("订单金额"), List.of("区域"), AskDsl.Granularity.DAY, null);
            StepResult s = step("s2", dsl, List.of("区域", "订单金额"), List.of(
                    List.of("华东", bd("100")),
                    List.of("华南", bd("-100"))), Set.of());

            assertThat(InsightCalculator.compute(List.of(s), model())).isEmpty();
        }
    }

    @Nested
    @DisplayName("同环比")
    class Compare {

        @Test
        @DisplayName("★ 无维度：本期 1100 比上期 1000 增长 10%")
        void scalarCompare() {
            AskDsl dsl = dsl(List.of("订单金额"), List.of(), AskDsl.Granularity.DAY,
                    AskDsl.Compare.yoy());
            StepResult s = step("s3", dsl,
                    List.of("订单金额", "订单金额上期", "订单金额同比"),
                    List.of(List.of(bd("1100"), bd("1000"), bd("0.1"))), Set.of());

            Finding f = InsightCalculator.compute(List.of(s), model()).get(0);

            assertThat(f.kind()).isEqualTo(Finding.Kind.COMPARE);
            assertThat(f.text()).contains("增长").contains("10.0%")
                    .contains("1100").contains("1000");
        }

        @Test
        @DisplayName("★ 有维度：找变化最大的一项")
        void biggestMover() {
            AskDsl dsl = dsl(List.of("订单金额"), List.of("区域"), AskDsl.Granularity.DAY,
                    AskDsl.Compare.yoy());
            StepResult s = step("s3", dsl,
                    List.of("区域", "订单金额", "订单金额上期", "订单金额同比"),
                    List.of(List.of("华东", bd("100"), bd("120"), bd("-0.166667")),
                            List.of("华南", bd("200"), bd("180"), bd("0.111111"))), Set.of());

            Finding f = InsightCalculator.compute(List.of(s), model()).get(0);

            assertThat(f.text()).contains("华东").contains("下降").contains("16.7%");
        }
    }

    @Nested
    @DisplayName("不该算的时候不算")
    class Guards {

        @Test
        @DisplayName("★ 指标被脱敏 → 整步不产出事实")
        void maskedMetricSkipped() {
            AskDsl dsl = dsl(List.of("订单金额"), List.of("区域"), AskDsl.Granularity.DAY, null);
            StepResult s = step("s2", dsl, List.of("区域", "订单金额"),
                    List.of(List.of("华东", "***"), List.of("华南", "***")), Set.of("订单金额"));

            assertThat(InsightCalculator.compute(List.of(s), model())).isEmpty();
        }

        @Test
        @DisplayName("多维度 → 不猜（既不是序列也不是构成）")
        void multiDimensionSkipped() {
            AskDsl dsl = dsl(List.of("订单金额"), List.of("统计日期", "区域"),
                    AskDsl.Granularity.MONTH, null);
            StepResult s = step("s1", dsl, List.of("统计日期", "区域", "订单金额"), List.of(
                    List.of("2025-01", "华东", bd("100")),
                    List.of("2025-02", "华南", bd("120"))), Set.of());

            assertThat(InsightCalculator.compute(List.of(s), model())).isEmpty();
        }

        @Test
        @DisplayName("失败的步骤直接跳过")
        void failedStepSkipped() {
            AskDsl dsl = dsl(List.of("订单金额"), List.of("区域"), AskDsl.Granularity.DAY, null);
            StepResult failed = StepResult.failed("s2", "s2", "", dsl, null, "字段不存在");

            assertThat(InsightCalculator.compute(List.of(failed), model())).isEmpty();
        }
    }
}

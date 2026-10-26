package com.liusongboyy.chatdata.engine.startup;

import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;
import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.ConditionTerm;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.MetricCondition;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import com.liusongboyy.chatdata.engine.semantic.Term;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 建模覆盖度检查。
 *
 * <h2>它防的是"不报错的错"</h2>
 *
 * <p>缺 AVG、缺去重计数、多个日期列没声明时间口径——这些都不会让查询失败，
 * 只会让模型拿现有的凑一个形态不对的答案。实测补齐后保险域基线从 74.2% 涨到 83.3%，
 * 代码一行未改。
 *
 * <p>所以这些用例断言的是**提示有没有出现、指向对不对**，而不是"能不能启动"。
 */
class ModelingCoverageCheckTest {

    private static ColumnDef col(String col, String type, Aggregation agg, String alias) {
        return new ColumnDef(col, type, agg, alias, true);
    }

    private static Domain domain(ColumnDef... cols) {
        return domain(List.of(), cols);
    }

    private static List<Term> noTerms() {
        return List.of();
    }

    private static Domain domain(List<Term> terms, ColumnDef... cols) {
        RegisteredTable t = new RegisteredTable("t1", "ins_policy", "保单表",
                RegisteredTable.Mode.TABLE, null, true, List.of(cols));
        return new Domain("d1", "保险域", "ds1", "MYSQL5.7", true, List.of(t),
                List.of(), DomainPermission.from(null), terms, List.of());
    }

    private static List<EngineConfigViolation> check(Domain d) {
        return ModelingCoverageCheck.check(d, new SemanticModel(d));
    }

    @Nested
    @DisplayName("聚合覆盖")
    class Aggregations {

        @Test
        @DisplayName("★ 有数值列但没有 AVG → 提示补一条，并点名具体列")
        void missingAvg() {
            var out = check(domain(
                    col("premium", "decimal", Aggregation.SUM, "保费"),
                    col("region", "varchar", Aggregation.NONE, "区域")));

            // 两个提示都会出现（缺 AVG、缺去重计数），这里只关心 AVG 那条
            assertThat(out)
                    .filteredOn(v -> v.message().contains("AVG"))
                    .singleElement()
                    .satisfies(v -> {
                        assertThat(v.category()).isEqualTo(
                                EngineConfigViolation.Category.THIN_MODELING);
                        assertThat(v.message()).contains("保费");
                    });
        }

        @Test
        @DisplayName("★ 有分类列但没有去重计数 → 提示「一共涉及多少种」会答不对")
        void missingDistinct() {
            var out = check(domain(
                    col("province", "varchar", Aggregation.NONE, "省份"),
                    col("premium", "decimal", Aggregation.AVG, "平均保费")));

            assertThat(out)
                    .anySatisfy(v -> assertThat(v.message())
                            .contains("COUNT_DISTINCT").contains("省份"));
        }

        @Test
        @DisplayName("两种聚合都有 → 不提示")
        void fullCoverageIsQuiet() {
            var out = check(domain(
                    col("premium", "decimal", Aggregation.AVG, "平均保费"),
                    col("province", "varchar", Aggregation.COUNT_DISTINCT, "省份数"),
                    col("region", "varchar", Aggregation.NONE, "区域")));

            assertThat(out).isEmpty();
        }
    }

    @Nested
    @DisplayName("指标/维度双登记")
    class DualRole {

        @Test
        @DisplayName("★ 数值列只有指标没有维度 → 提示「等于某个值」的问法会失败")
        void metricWithoutDimension() {
            var out = check(domain(
                    col("term_years", "int", Aggregation.AVG, "平均保障年限"),
                    col("province", "varchar", Aggregation.COUNT_DISTINCT, "省份数"),
                    col("region", "varchar", Aggregation.NONE, "区域")));

            assertThat(out)
                    .anySatisfy(v -> assertThat(v.message())
                            .contains("term_years").contains("不聚合"));
        }

        @Test
        @DisplayName("已经双登记 → 不提示")
        void dualRegisteredIsQuiet() {
            var out = check(domain(
                    col("term_years", "int", Aggregation.AVG, "平均保障年限"),
                    col("term_years", "int", Aggregation.NONE, "保障年限"),
                    col("province", "varchar", Aggregation.COUNT_DISTINCT, "省份数"),
                    col("region", "varchar", Aggregation.NONE, "区域")));

            assertThat(out).isEmpty();
        }

        @Test
        @DisplayName("纯维度列不提示——它本来就只用来分组")
        void dimensionOnlyIsQuiet() {
            var out = check(domain(
                    col("premium", "decimal", Aggregation.AVG, "平均保费"),
                    col("province", "varchar", Aggregation.COUNT_DISTINCT, "省份数"),
                    col("region", "varchar", Aggregation.NONE, "区域")));

            assertThat(out).noneSatisfy(v -> assertThat(v.message()).contains("「region」"));
        }
    }

    @Nested
    @DisplayName("时间口径")
    class TimeTerms {

        @Test
        @DisplayName("★ 多个日期列却没有 time 术语 → 提示会猜错列")
        void multipleDatesWithoutTerm() {
            var out = check(domain(
                    col("premium", "decimal", Aggregation.AVG, "平均保费"),
                    col("province", "varchar", Aggregation.COUNT_DISTINCT, "省份数"),
                    col("effect_date", "date", Aggregation.NONE, "生效日期"),
                    col("report_date", "date", Aggregation.NONE, "报案日期")));

            assertThat(out)
                    .anySatisfy(v -> assertThat(v.message())
                            .contains("日期列").contains("apply_kind=time"));
        }

        @Test
        @DisplayName("只有一个日期列 → 不用声明，猜也不会错")
        void singleDateIsFine() {
            var out = check(domain(
                    col("premium", "decimal", Aggregation.AVG, "平均保费"),
                    col("province", "varchar", Aggregation.COUNT_DISTINCT, "省份数"),
                    col("effect_date", "date", Aggregation.NONE, "生效日期")));

            assertThat(out).isEmpty();
        }

        @Test
        @DisplayName("声明了 time 术语 → 不提示")
        void declaredTermIsQuiet() {
            List<Term> terms = List.of(new Term("生效日期", List.of(), "保单生效",
                    Term.ApplyKind.TIME));
            var out = check(domain(terms,
                    col("premium", "decimal", Aggregation.AVG, "平均保费"),
                    col("province", "varchar", Aggregation.COUNT_DISTINCT, "省份数"),
                    col("effect_date", "date", Aggregation.NONE, "生效日期"),
                    col("report_date", "date", Aggregation.NONE, "报案日期")));

            assertThat(out).isEmpty();
        }
    }

    @Nested
    @DisplayName("自相矛盾")
    class Contradictions {

        @Test
        @DisplayName("★ 统计条件引用了它自己 → 循环定义，必须提示")
        void selfReferencingCondition() {
            ColumnDef bad = new ColumnDef("ticket_no", "varchar", Aggregation.COUNT,
                    "已解决工单数", true, null,
                    new MetricCondition(MetricCondition.Logic.AND,
                            List.of(new ConditionTerm("已解决工单数", ">", "0", null))));
            var out = check(domain(bad,
                    col("province", "varchar", Aggregation.COUNT_DISTINCT, "省份数")));

            assertThat(out)
                    .anySatisfy(v -> {
                        assertThat(v.category()).isEqualTo(
                                EngineConfigViolation.Category.CONTRADICTORY_MODELING);
                        assertThat(v.message()).contains("循环定义");
                    });
        }
    }

    @Test
    @DisplayName("直连域跳过这套检查——它没有列定义")
    void rawDomainSkipped() {
        RegisteredTable t = new RegisteredTable("t1", "ticket", "工单表",
                RegisteredTable.Mode.TABLE, null, true, List.of(
                col("amount", "decimal", Aggregation.SUM, "金额")));
        Domain d = new Domain("d1", "直连域", "ds1", "MYSQL5.7", true, List.of(t),
                List.of(), DomainPermission.from(null), noTerms(), List.of(), Domain.Mode.RAW);

        assertThat(check(d)).isEmpty();
    }
}

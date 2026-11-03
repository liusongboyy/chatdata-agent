package com.liusongboyy.chatdata.engine.modeling;

import com.liusongboyy.chatdata.engine.datasource.MetaReader.ColumnMeta;
import com.liusongboyy.chatdata.engine.modeling.ColumnInferrer.Confidence;
import com.liusongboyy.chatdata.engine.modeling.ColumnInferrer.Inference;
import com.liusongboyy.chatdata.engine.modeling.ColumnInferrer.Role;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 列角色推断规则。
 *
 * <p>断言的是「为什么这么推」和「推出来的东西对不对」两件事——
 * 因为草稿要给人看，`reason` 空泛等于没给。
 */
class ColumnInferrerTest {

    private static ColumnMeta col(String name, String type, boolean pk) {
        return new ColumnMeta(name, type, true, pk, "");
    }

    private static ColumnMeta col(String name, String type, boolean pk, String comment) {
        return new ColumnMeta(name, type, true, pk, comment);
    }

    @Test
    @DisplayName("★ 主键 → 维度，高置信")
    void primaryKeyIsDimension() {
        Inference r = ColumnInferrer.infer(col("id", "bigint", true), -1);

        assertThat(r.role()).isEqualTo(Role.DIMENSION);
        assertThat(r.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(r.reason()).contains("主键");
    }

    @Test
    @DisplayName("★ 金额列 → 维度+指标双登记，SUM 与 AVG 都有")
    void amountColumnGetsBothRoles() {
        Inference r = ColumnInferrer.infer(col("total_due", "decimal(10,2)", false), -1);

        // 双登记是关键：只当指标就没法写「金额 > 1000」
        assertThat(r.role()).isEqualTo(Role.DIMENSION_AND_METRIC);
        assertThat(r.aggregations()).containsExactlyInAnyOrder(Aggregation.SUM, Aggregation.AVG);
        assertThat(r.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(r.reason()).contains("维度").contains("筛选");
    }

    @Test
    @DisplayName("★ 中文度量词也认：保费、订单金额")
    void chineseMeasureWords() {
        assertThat(ColumnInferrer.infer(col("premium", "decimal(10,2)", false, "保费"), -1).role())
                .isEqualTo(Role.DIMENSION_AND_METRIC);
        assertThat(ColumnInferrer.infer(col("order_amt", "decimal", false), -1).role())
                .as("amt 不在英文词表里，但有中英混合场景需要覆盖")
                .isEqualTo(Role.DIMENSION);
    }

    @Test
    @DisplayName("★ 数值列但名称不像度量 → 仅维度（年龄求和没意义）")
    void numericWithoutMeasureNameIsDimensionOnly() {
        Inference r = ColumnInferrer.infer(col("age", "int", false), -1);

        assertThat(r.role()).isEqualTo(Role.DIMENSION);
        assertThat(r.aggregations()).isEmpty();
        assertThat(r.reason()).contains("没有意义");
    }

    @Test
    @DisplayName("★ 日期列 → 时间口径候选")
    void temporalColumn() {
        Inference r = ColumnInferrer.infer(col("order_date", "datetime", false), -1);

        assertThat(r.role()).isEqualTo(Role.TEMPORAL);
        assertThat(r.confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    @DisplayName("★ 低基数文本 → 维度 + COUNT_DISTINCT")
    void lowCardinalityText() {
        Inference r = ColumnInferrer.infer(col("status", "varchar(20)", false), 4);

        assertThat(r.role()).isEqualTo(Role.DIMENSION_AND_METRIC);
        assertThat(r.aggregations()).containsExactly(Aggregation.COUNT_DISTINCT);
        assertThat(r.reason()).contains("4");
    }

    @Test
    @DisplayName("★ 高基数文本 → 仍补 COUNT_DISTINCT，但置信度降到 LOW")
    void highCardinalityTextIsLowConfidence() {
        Inference r = ColumnInferrer.infer(col("description", "text", false), 150);

        assertThat(r.role()).isEqualTo(Role.DIMENSION_AND_METRIC);
        // 基数偏高时可能其实是自由文本，不该给高置信
        assertThat(r.confidence()).isEqualTo(Confidence.LOW);
        assertThat(r.reason()).contains("自由文本");
    }

    @Test
    @DisplayName("★ 极高基数文本 → 只当维度，不补聚合")
    void veryHighCardinalityIsDimensionOnly() {
        Inference r = ColumnInferrer.infer(col("remark", "text", false), 5000);

        assertThat(r.role()).isEqualTo(Role.DIMENSION);
        assertThat(r.aggregations()).isEmpty();
    }

    @Test
    @DisplayName("布尔 → 维度 + COUNT")
    void booleanColumn() {
        Inference r = ColumnInferrer.infer(col("is_closed", "bit", false), -1);

        assertThat(r.role()).isEqualTo(Role.DIMENSION);
        assertThat(r.aggregations()).containsExactly(Aggregation.COUNT);
    }

    @Test
    @DisplayName("★ 敏感列优先于其他所有规则")
    void sensitiveWinsOverEverything() {
        // 即使是数值 + 度量词，命中敏感词也要关掉
        Inference r = ColumnInferrer.infer(col("phone_number", "varchar(20)", false), 100);

        assertThat(r.role()).isEqualTo(Role.SENSITIVE);
        assertThat(r.aggregations()).isEmpty();
        assertThat(r.reason()).contains("默认关闭");
    }

    @Test
    @DisplayName("未采样（基数 -1）时数值列不补 COUNT_DISTINCT")
    void noCardinalityForNumeric() {
        Inference r = ColumnInferrer.infer(col("qty", "int", false), -1);

        assertThat(r.aggregations()).doesNotContain(Aggregation.COUNT_DISTINCT);
    }

    @Test
    @DisplayName("★ 标识类列不当指标：SalesPersonID 含 sales 但不该 SUM")
    void identifierColumnsAreNotMetrics() {
        // AdventureWorks 实测踩到：SalesPersonID 里有 "sales"，命中度量词表后
        // 被建议 SUM —— 而「销售员 ID 求和」没有任何业务含义
        Inference r = ColumnInferrer.infer(col("SalesPersonID", "int", false), -1);

        assertThat(r.role()).isEqualTo(Role.DIMENSION);
        assertThat(r.aggregations()).isEmpty();
        assertThat(r.reason()).contains("标识");
    }

    @Test
    @DisplayName("★ RevisionNumber 同理：Number 结尾不当指标")
    void revisionNumberIsNotMetric() {
        Inference r = ColumnInferrer.infer(col("RevisionNumber", "tinyint", false), -1);

        assertThat(r.aggregations()).doesNotContain(Aggregation.SUM);
    }

    @Test
    @DisplayName("★ 比率列只给 AVG 不给 SUM：折扣率求和没有意义")
    void ratioColumnsGetAvgOnly() {
        // AdventureWorks 实测踩到：UnitPriceDiscount 被建议 SUM + AVG
        Inference r = ColumnInferrer.infer(col("UnitPriceDiscount", "decimal", false), -1);

        assertThat(r.aggregations()).containsExactly(Aggregation.AVG);
        assertThat(r.aggregations()).doesNotContain(Aggregation.SUM);
        assertThat(r.reason()).contains("没有意义");
    }

    @Test
    @DisplayName("★ 真正的度量列不受影响：SubTotal 仍是 SUM + AVG")
    void realMeasuresStillWork() {
        Inference r = ColumnInferrer.infer(col("SubTotal", "decimal", false), -1);

        assertThat(r.aggregations()).containsExactlyInAnyOrder(Aggregation.SUM, Aggregation.AVG);
    }

    @Test
    @DisplayName("★ 业务名候选：有注释用注释，没注释用列名并要求确认")
    void aliasCandidate() {
        assertThat(ColumnInferrer.aliasCandidate(col("total", "decimal", false, "订单金额")))
                .isEqualTo("订单金额");
        assertThat(ColumnInferrer.aliasCandidate(col("total", "decimal", false)))
                .isEqualTo("total");

        assertThat(ColumnInferrer.aliasNeedsConfirmation(col("t", "int", false, "有注释"))).isFalse();
        assertThat(ColumnInferrer.aliasNeedsConfirmation(col("t", "int", false))).isTrue();
    }

    @Test
    @DisplayName("每条推断都必须有 reason")
    void everyInferenceHasReason() {
        var samples = java.util.List.of(
                col("id", "bigint", true),
                col("amount", "decimal", false),
                col("age", "int", false),
                col("d", "date", false),
                col("s", "varchar", false),
                col("phone", "varchar", false),
                col("flag", "bit", false));
        for (ColumnMeta c : samples) {
            Inference r = ColumnInferrer.infer(c, 5);
            assertThat(r.reason())
                    .as("列 %s 的推断缺少 reason", c.name())
                    .isNotBlank();
        }
    }
}

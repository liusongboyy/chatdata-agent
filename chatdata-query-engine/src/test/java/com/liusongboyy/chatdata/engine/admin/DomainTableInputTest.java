package com.liusongboyy.chatdata.engine.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liusongboyy.chatdata.engine.semantic.DerivedMetric;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 管理台保存列定义时的入参校验与序列化。
 *
 * <h2>为什么派生指标要在这里测</h2>
 *
 * <p>{@code columns_json} 是管理台与引擎之间**唯一**的契约。以前列定义只有
 * 列名/聚合两件事，现在多了派生指标：若序列化时丢掉 {@code derived} 块，
 * 表现为"保存成功、指标消失"——管理员改个业务名，派生指标就没了，且不报错。
 */
class DomainTableInputTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static DomainTableInput input(DomainTableInput.ColumnInput... cols) {
        return new DomainTableInput("ins_claim", "理赔表", "TABLE", null, List.of(cols), true, 0);
    }

    private static DomainTableInput.ColumnInput plain(String col, String alias, String agg) {
        return new DomainTableInput.ColumnInput(col, "decimal", agg, alias, true, null);
    }

    private static DomainTableInput.ColumnInput ratio(String alias) {
        return new DomainTableInput.ColumnInput(null, "decimal", null, alias, true,
                new DomainTableInput.DerivedInput("ratio", "赔付金额", "申请金额", null, null, null));
    }

    @Nested
    @DisplayName("普通列")
    class Plain {

        @Test
        @DisplayName("列名必填")
        void columnNameRequired() {
            assertThatThrownBy(() -> input(plain("", "保费", "SUM")).validated())
                    .isInstanceOf(AdminException.class)
                    .hasMessageContaining("列名");
        }

        @Test
        @DisplayName("不写 derived 时，序列化结果里没有 derived 键")
        void noDerivedKey() throws Exception {
            JsonNode col = M.readTree(input(plain("premium", "保费", "SUM")).columnsJson()).get(0);
            assertThat(col.has("derived")).isFalse();
        }
    }

    @Nested
    @DisplayName("统计条件")
    class Condition {

        /** 与常量比较的一项。 */
        private JsonNode leaf(String field, String op, String value) {
            ObjectNode n = M.createObjectNode();
            n.put("field", field);
            n.put("op", op);
            n.put("value", value);
            return n;
        }

        /** 与另一个字段比较的一项。 */
        private JsonNode fieldTerm(String field, String op, String rightField) {
            ObjectNode n = M.createObjectNode();
            n.put("field", field);
            n.put("op", op);
            n.put("rightField", rightField);
            return n;
        }

        private JsonNode group(String logic, JsonNode... terms) {
            ObjectNode n = M.createObjectNode();
            n.put("logic", logic);
            ArrayNode arr = n.putArray("conditions");
            for (JsonNode t : terms) {
                arr.add(t);
            }
            return n;
        }

        private DomainTableInput.ColumnInput conditional(String alias, String op) {
            return new DomainTableInput.ColumnInput("ticket_no", "varchar", "COUNT", alias, true,
                    null, leaf("状态", op, "RESOLVED"));
        }

        @Test
        @DisplayName("★ 单条件写成扁平形态，且能按运行时同一份规则读回")
        void serializesFlatCondition() throws Exception {
            DomainTableInput v = input(conditional("已解决工单数", "=")).validated();

            JsonNode cv = M.readTree(v.columnsJson()).get(0).path("condition");
            assertThat(cv.path("field").asText()).isEqualTo("状态");
            assertThat(cv.path("op").asText()).isEqualTo("=");
            assertThat(cv.path("value").asText()).isEqualTo("RESOLVED");
            assertThat(cv.has("conditions")).as("单条件不包一层").isFalse();

            com.liusongboyy.chatdata.engine.semantic.MetricCondition back =
                    com.liusongboyy.chatdata.engine.semantic.MetricCondition.from(cv);
            assertThat(back.terms()).hasSize(1);
        }

        @Test
        @DisplayName("多个条件写成 logic + conditions，AND/OR 都保留")
        void serializesGroup() throws Exception {
            DomainTableInput v = input(new DomainTableInput.ColumnInput(
                    "ticket_no", "varchar", "COUNT", "高优已解决工单数", true, null,
                    group("AND", leaf("状态", "=", "RESOLVED"), leaf("优先级", ">", "2")))).validated();

            JsonNode cv = M.readTree(v.columnsJson()).get(0).path("condition");
            assertThat(cv.path("logic").asText()).isEqualTo("and");
            assertThat(cv.path("conditions")).hasSize(2);
        }

        @Test
        @DisplayName("与字段比较写成 rightField，不写 value")
        void serializesFieldComparison() throws Exception {
            DomainTableInput v = input(new DomainTableInput.ColumnInput(
                    "ticket_no", "varchar", "COUNT", "超时关闭工单数", true, null,
                    fieldTerm("关闭时间", ">", "SLA截止时间"))).validated();

            JsonNode cv = M.readTree(v.columnsJson()).get(0).path("condition");
            assertThat(cv.path("rightField").asText()).isEqualTo("SLA截止时间");
            assertThat(cv.has("value")).isFalse();
        }

        @Test
        @DisplayName("运算符写法被规范化：<> → !=")
        void normalizesOperator() throws Exception {
            DomainTableInput v = input(conditional("未解决工单数", "<>")).validated();

            assertThat(M.readTree(v.columnsJson()).get(0).path("condition").path("op").asText())
                    .isEqualTo("!=");
        }

        @Test
        @DisplayName("维度加条件 → 拒绝（会让分组静默少行）")
        void dimensionConditionRejected() {
            DomainTableInput.ColumnInput c = new DomainTableInput.ColumnInput(
                    "status", "varchar", "", "状态", true, null, leaf("状态", "=", "已解决"));

            assertThatThrownBy(() -> input(c).validated())
                    .isInstanceOf(AdminException.class)
                    .hasMessageContaining("条件只对指标有意义");
        }

        @Test
        @DisplayName("不认识的运算符 → 保存时就拒绝")
        void unknownOperatorRejected() {
            assertThatThrownBy(() -> input(conditional("X", "contains")).validated())
                    .isInstanceOf(AdminException.class)
                    .hasMessageContaining("运算符不受支持");
        }

        @Test
        @DisplayName("没有条件时不写 condition 键")
        void noConditionKey() throws Exception {
            DomainTableInput v = input(plain("premium", "保费", "SUM")).validated();
            assertThat(M.readTree(v.columnsJson()).get(0).has("condition")).isFalse();
        }
    }

    @Nested
    @DisplayName("派生指标")
    class Derived {

        @Test
        @DisplayName("★ 没有物理列也能保存，且 derived 块被写进 columns_json")
        void serializesDerived() throws Exception {
            DomainTableInput v = input(
                    plain("settle_amount", "赔付金额", "SUM"),
                    plain("claim_amount", "申请金额", "SUM"),
                    ratio("赔付率")).validated();

            JsonNode cols = M.readTree(v.columnsJson());
            JsonNode col = cols.get(2);
            assertThat(col.path("col").asText()).isEmpty();
            assertThat(col.path("agg").asText()).isEmpty();

            JsonNode dv = col.path("derived");
            assertThat(dv.path("kind").asText()).isEqualTo("ratio");
            assertThat(dv.path("left").asText()).isEqualTo("赔付金额");
            assertThat(dv.path("right").asText()).isEqualTo("申请金额");

            // 引擎侧解析回来的必须是同一个定义——这是两边唯一的契约
            DerivedMetric back = DerivedMetric.from(dv);
            assertThat(back.kind()).isEqualTo(DerivedMetric.Kind.RATIO);
            assertThat(back.left()).isEqualTo("赔付金额");
        }

        @Test
        @DisplayName("kind 统一成小写、unit 统一成大写")
        void normalizesKindAndUnit() throws Exception {
            DomainTableInput v = input(new DomainTableInput.ColumnInput(null, "bigint", "", "平均解决时长", true,
                    new DomainTableInput.DerivedInput("DURATION", null, null,
                            "创建时间", "解决时间", "hour"))).validated();

            JsonNode dv = M.readTree(v.columnsJson()).get(0).path("derived");
            assertThat(dv.path("kind").asText()).isEqualTo("duration");
            assertThat(dv.path("unit").asText()).isEqualTo("HOUR");
            assertThat(dv.has("left")).isFalse();
        }

        @Test
        @DisplayName("业务名必填——没有名字就永远匹配不上")
        void aliasRequired() {
            assertThatThrownBy(() -> input(new DomainTableInput.ColumnInput(null, "decimal", "", "", true,
                    new DomainTableInput.DerivedInput("ratio", "a", "b", null, null, null))).validated())
                    .isInstanceOf(AdminException.class)
                    .hasMessageContaining("业务名");
        }

        @Test
        @DisplayName("再叠聚合方式 → 拒绝（会渲染成 SUM(SUM(a)/SUM(b))）")
        void rejectsAggregation() {
            assertThatThrownBy(() -> input(new DomainTableInput.ColumnInput(null, "decimal", "SUM", "赔付率", true,
                    new DomainTableInput.DerivedInput("ratio", "a", "b", null, null, null))).validated())
                    .isInstanceOf(AdminException.class)
                    .hasMessageContaining("聚合");
        }

        @Test
        @DisplayName("填了物理列 → 拒绝：派生指标没有物理列")
        void rejectsPhysicalColumn() {
            assertThatThrownBy(() -> input(new DomainTableInput.ColumnInput("settle_amount", "decimal", "", "赔付率", true,
                    new DomainTableInput.DerivedInput("ratio", "a", "b", null, null, null))).validated())
                    .isInstanceOf(AdminException.class)
                    .hasMessageContaining("列名请留空");
        }

        @Test
        @DisplayName("kind 非法 → 拒绝并列出可用取值")
        void rejectsUnknownKind() {
            assertThatThrownBy(() -> input(new DomainTableInput.ColumnInput(null, "decimal", "", "X", true,
                    new DomainTableInput.DerivedInput("sum", "a", "b", null, null, null))).validated())
                    .isInstanceOf(AdminException.class)
                    .hasMessageContaining("ratio / diff / duration");
        }

        @Test
        @DisplayName("时长缺结束列 → 拒绝")
        void durationNeedsBothEnds() {
            assertThatThrownBy(() -> input(new DomainTableInput.ColumnInput(null, "bigint", "", "平均时长", true,
                    new DomainTableInput.DerivedInput("duration", null, null, "创建时间", null, "HOUR"))).validated())
                    .isInstanceOf(AdminException.class)
                    .hasMessageContaining("start 与 end");
        }

        @Test
        @DisplayName("时长单位非法 → 拒绝，不回落成小时")
        void rejectsUnknownUnit() {
            assertThatThrownBy(() -> input(new DomainTableInput.ColumnInput(null, "bigint", "", "平均时长", true,
                    new DomainTableInput.DerivedInput("duration", null, null, "a", "b", "WEEK"))).validated())
                    .isInstanceOf(AdminException.class)
                    .hasMessageContaining("时长单位非法");
        }
    }
}

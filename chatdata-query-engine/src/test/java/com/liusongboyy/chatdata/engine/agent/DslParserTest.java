package com.liusongboyy.chatdata.engine.agent;

import com.liusongboyy.chatdata.engine.nlsql.AskDsl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DSL 解析的测试。**不调模型**——直接喂字符串。
 *
 * <p>解析器是"模型输出"与"内部结构"之间的唯一关口，而模型输出天然不规范。
 * 故这里重点测**容错**：格式的小偏差要接受，非法取值要拒绝。
 */
class DslParserTest {

    @Nested
    @DisplayName("标准形态")
    class Standard {

        @Test
        @DisplayName("完整的 DSL")
        void fullDsl() {
            AskDsl dsl = DslParser.parse("""
                    {"metrics":["保费"],"dimensions":["区域"],
                     "timeRange":{"kind":"上个月","start":null,"end":null},
                     "filters":[{"field":"状态","op":"=","value":"生效"}]}""");

            assertThat(dsl.metrics()).containsExactly("保费");
            assertThat(dsl.dimensions()).containsExactly("区域");
            assertThat(dsl.timeRange().kind()).isEqualTo("上个月");
            assertThat(dsl.filters()).hasSize(1);
            assertThat(dsl.filters().get(0).field()).isEqualTo("状态");
        }

        @Test
        @DisplayName("字段全空时 isEmpty 为真 —— 用于触发「没听懂」")
        void emptyDsl() {
            AskDsl dsl = DslParser.parse("{\"metrics\":[],\"dimensions\":[],\"filters\":[]}");

            assertThat(dsl.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("容错 —— 模型输出的小偏差要接受")
    class Tolerance {

        @Test
        @DisplayName("套了 markdown 代码块也能解析 —— 这是很常见的输出习惯")
        void markdownFence() {
            AskDsl dsl = DslParser.parse("""
                    ```json
                    {"metrics":["保费"],"dimensions":[],"filters":[]}
                    ```""");

            assertThat(dsl.metrics()).containsExactly("保费");
        }

        @Test
        @DisplayName("前后有解释文字也能解析")
        void surroundingText() {
            AskDsl dsl = DslParser.parse(
                    "好的，这是解析结果：{\"metrics\":[\"保费\"],\"dimensions\":[],\"filters\":[]} 希望有帮助");

            assertThat(dsl.metrics()).containsExactly("保费");
        }

        @Test
        @DisplayName("★ 数组写成裸字符串也接受 —— 模型常这么干")
        void bareStringInsteadOfArray() {
            AskDsl dsl = DslParser.parse("""
                    {"metrics":"保费","dimensions":"区域","filters":[]}""");

            assertThat(dsl.metrics()).containsExactly("保费");
            assertThat(dsl.dimensions()).containsExactly("区域");
        }

        @Test
        @DisplayName("timeRange 写成裸字符串也接受")
        void bareStringTimeRange() {
            AskDsl dsl = DslParser.parse("""
                    {"metrics":["保费"],"dimensions":[],"timeRange":"上个月","filters":[]}""");

            assertThat(dsl.timeRange().kind()).isEqualTo("上个月");
        }

        @Test
        @DisplayName("字段缺失时降级为空，不抛异常")
        void missingFields() {
            assertThat(DslParser.parse("{}").isEmpty()).isTrue();
            assertThat(DslParser.parse("{\"metrics\":[\"保费\"]}").dimensions()).isEmpty();
            assertThat(DslParser.parse("{\"metrics\":[\"保费\"]}").timeRange()).isNull();
        }

        @Test
        @DisplayName("op 缺失时默认为 =")
        void defaultOperator() {
            AskDsl dsl = DslParser.parse("""
                    {"metrics":["保费"],"dimensions":[],
                     "filters":[{"field":"区域","value":"华东"}]}""");

            assertThat(dsl.filters().get(0).op()).isEqualTo("=");
        }

        @Test
        @DisplayName("★ 没有字段名的筛选条件被跳过，不让整条解析失败")
        void filterWithoutFieldSkipped() {
            AskDsl dsl = DslParser.parse("""
                    {"metrics":["保费"],"dimensions":[],"filters":[{"op":"=","value":"华东"}]}""");

            assertThat(dsl.filters()).isEmpty();
            assertThat(dsl.metrics()).containsExactly("保费");
        }

        @Test
        @DisplayName("字符串数组形态的筛选条件也能解析")
        void inlineStringFilters() {
            AskDsl dsl = DslParser.parse("""
                    {"metrics":["保费"],"dimensions":[],"filters":["区域 = 华东","状态 != 退保"]}""");

            assertThat(dsl.filters()).hasSize(2);
            assertThat(dsl.filters().get(0).field()).isEqualTo("区域");
            assertThat(dsl.filters().get(0).value()).isEqualTo("华东");
            assertThat(dsl.filters().get(1).op()).isEqualTo("!=");
        }

        @Test
        @DisplayName("扁平 map 形态的筛选条件也能解析")
        void mapFilters() {
            AskDsl dsl = DslParser.parse("""
                    {"metrics":["保费"],"dimensions":[],"filters":{"区域":"华东"}}""");

            assertThat(dsl.filters()).hasSize(1);
            assertThat(dsl.filters().get(0).field()).isEqualTo("区域");
            assertThat(dsl.filters().get(0).value()).isEqualTo("华东");
        }
    }

    @Nested
    @DisplayName("失败要报清楚")
    class Failures {

        @Test
        @DisplayName("不是 JSON 时报错，并带上原文片段")
        void notJson() {
            assertThatThrownBy(() -> DslParser.parse("我觉得这个问题很难回答"))
                    .isInstanceOf(DslGenerationException.class)
                    .hasMessageContaining("不是合法 JSON");
        }

        @Test
        @DisplayName("空输入报错")
        void blank() {
            assertThatThrownBy(() -> DslParser.parse(null))
                    .isInstanceOf(DslGenerationException.class);
            assertThatThrownBy(() -> DslParser.parse("   "))
                    .isInstanceOf(DslGenerationException.class);
        }

        @Test
        @DisplayName("★ 超长输出在报错里被截断 —— 否则整段模型输出会灌进日志")
        void longOutputTruncated() {
            String huge = "x".repeat(5000);

            assertThatThrownBy(() -> DslParser.parse(huge))
                    .isInstanceOf(DslGenerationException.class)
                    .satisfies(e -> assertThat(e.getMessage().length())
                            .as("报错信息不该把整段模型输出原样带进日志")
                            .isLessThan(500));
        }
    }

    @Test
    @DisplayName("★ granularity 必须解析出来（曾整个字段被丢弃，恒为 DAY）")
    void parsesGranularity() {
        AskDsl month = DslParser.parse("""
                {"metrics":["保费"],"dimensions":["生效日期"],"filters":[],"granularity":"month"}""");
        assertThat(month.granularity())
                .as("模型填了 month 却被丢掉，SQL 就会按天分组")
                .isEqualTo(AskDsl.Granularity.MONTH);

        AskDsl year = DslParser.parse("""
                {"metrics":["保费"],"dimensions":["生效日期"],"filters":[],"granularity":"year"}""");
        assertThat(year.granularity()).isEqualTo(AskDsl.Granularity.YEAR);
    }

    @Test
    @DisplayName("未填或填了不认识的值时按 DAY（不截断是安全默认）")
    void granularityDefaultsToDay() {
        assertThat(DslParser.parse("""
                {"metrics":["保费"],"dimensions":["生效日期"],"filters":[]}""").granularity())
                .isEqualTo(AskDsl.Granularity.DAY);
        assertThat(DslParser.parse("""
                {"metrics":["保费"],"dimensions":["生效日期"],"filters":[],"granularity":"quarter"}""")
                .granularity())
                .as("猜成按月会让「按季度」静默变成「按月」，比不截断更糟")
                .isEqualTo(AskDsl.Granularity.DAY);
    }

    @Nested
    @DisplayName("排序 / 占比 / 同环比")
    class Analytics {

        @Test
        @DisplayName("对象数组形态：字段 + 方向")
        void sortObjects() {
            AskDsl dsl = DslParser.parse("""
                    {"metrics":["保费"],"dimensions":["区域"],"filters":[],
                     "sort":[{"field":"保费","direction":"desc"}],"limit":5}""");

            assertThat(dsl.sort()).hasSize(1);
            assertThat(dsl.sort().get(0).field()).isEqualTo("保费");
            assertThat(dsl.sort().get(0).direction()).isEqualTo("desc");
            assertThat(dsl.limit()).isEqualTo(5);
        }

        @Test
        @DisplayName("★ 漏写方向时按 desc —— SQL 的缺省是 asc，会给'最差的 5 个'")
        void directionDefaultsToDesc() {
            assertThat(DslParser.parse("""
                    {"metrics":["保费"],"dimensions":["区域"],"filters":[],
                     "sort":[{"field":"保费"}]}""").sort().get(0).direction())
                    .isEqualTo("desc");
        }

        @Test
        @DisplayName("内联写法「保费 desc」也认")
        void inlineSort() {
            AskDsl dsl = DslParser.parse("""
                    {"metrics":["保费"],"dimensions":["区域"],"filters":[],"sort":["保费 asc"]}""");

            assertThat(dsl.sort().get(0).field()).isEqualTo("保费");
            assertThat(dsl.sort().get(0).direction()).isEqualTo("asc");
        }

        @Test
        @DisplayName("limit 写成字符串也认；写不出数字就当没填")
        void limitTolerance() {
            assertThat(DslParser.parse("""
                    {"metrics":["保费"],"dimensions":[],"filters":[],"limit":"10"}""").limit())
                    .isEqualTo(10);
            assertThat(DslParser.parse("""
                    {"metrics":["保费"],"dimensions":[],"filters":[],"limit":"十个"}""").limit())
                    .isNull();
        }

        @Test
        @DisplayName("shareOf 与 compare")
        void shareAndCompare() {
            AskDsl dsl = DslParser.parse("""
                    {"metrics":["保费","保单数"],"dimensions":["区域"],"filters":[],
                     "shareOf":["保费"],"compare":"yoy"}""");

            assertThat(dsl.shareOf()).containsExactly("保费");
            assertThat(dsl.compare().mode()).isEqualTo(AskDsl.Compare.Mode.YOY);
        }

        @Test
        @DisplayName("★ 筛选条件可以与另一个字段比：rightField；同时给 value 时以它为准")
        void filterRightField() {
            AskDsl dsl = DslParser.parse("""
                    {"metrics":["工单数"],"dimensions":[],"filters":[
                      {"field":"关闭时间","op":">","rightField":"SLA截止时间"},
                      {"field":"状态","op":"=","value":"CLOSED","rightField":"状态"}]}""");

            assertThat(dsl.filters().get(0).comparesWithField()).isTrue();
            assertThat(dsl.filters().get(0).rightField()).isEqualTo("SLA截止时间");
            assertThat(dsl.filters().get(0).value()).isNull();

            assertThat(dsl.filters().get(1).rightField()).as("rightField 优先").isEqualTo("状态");
            assertThat(dsl.filters().get(1).value()).isNull();
        }

        @Test
        @DisplayName("自定义对比期：对象形态，字符串形态也兼容")
        void customCompare() {
            AskDsl dsl = DslParser.parse("""
                    {"metrics":["保费"],"dimensions":["生效日期"],"filters":[],
                     "compare":{"mode":"custom","start":"2023-01-01","end":"2023-12-31"}}""");

            assertThat(dsl.compare().mode()).isEqualTo(AskDsl.Compare.Mode.CUSTOM);
            assertThat(dsl.compare().start()).isEqualTo("2023-01-01");
            assertThat(dsl.compare().end()).isEqualTo("2023-12-31");

            // 只给 start/end 不写 mode，按自定义处理
            AskDsl noMode = DslParser.parse("""
                    {"metrics":["保费"],"dimensions":[],"filters":[],
                     "compare":{"start":"2023-01-01","end":"2023-12-31"}}""");
            assertThat(noMode.compare().mode()).isEqualTo(AskDsl.Compare.Mode.CUSTOM);
        }

        @Test
        @DisplayName("★ 自定义对比期缺日期 → 当没填（不整条作废），日期写错 → 仍然拒绝")
        void invalidCustomCompare() {
            // 缺一端：模型常在没有明确对比期时随手填 custom。为它整条作废代价太大，
            // 故当作"没有对比"，其余部分照常解析
            AskDsl missing = DslParser.parse("""
                    {"metrics":["保费"],"dimensions":[],"filters":[],
                     "compare":{"mode":"custom","start":"2023-01-01"}}""");
            assertThat(missing.compare()).isNull();
            assertThat(missing.metrics()).containsExactly("保费");

            // 写了日期但格式不合法：那是明确的错误，不能悄悄当成没填
            assertThatThrownBy(() -> DslParser.parse("""
                    {"metrics":["保费"],"dimensions":[],"filters":[],
                     "compare":{"mode":"custom","start":"2023-13-01","end":"2023-12-31"}}"""))
                    .isInstanceOf(DslGenerationException.class)
                    .hasMessageContaining("yyyy-MM-dd");
        }

        @Test
        @DisplayName("不是排名字段/方向时拒绝，走重问而不是静默算错")
        void invalidValuesRejected() {
            assertThatThrownBy(() -> DslParser.parse("""
                    {"metrics":["保费"],"dimensions":[],"filters":[],
                     "sort":[{"field":"保费","direction":"ascending"}]}"""))
                    .isInstanceOf(DslGenerationException.class)
                    .hasMessageContaining("asc");

            assertThatThrownBy(() -> DslParser.parse("""
                    {"metrics":["保费"],"dimensions":[],"filters":[],"compare":"quarter"}"""))
                    .isInstanceOf(DslGenerationException.class)
                    .hasMessageContaining("compare");
        }
    }
}

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
}

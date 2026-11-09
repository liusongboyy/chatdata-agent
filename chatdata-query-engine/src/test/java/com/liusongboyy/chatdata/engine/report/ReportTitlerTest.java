package com.liusongboyy.chatdata.engine.report;

import com.liusongboyy.chatdata.engine.agent.Generated;
import com.liusongboyy.chatdata.engine.agent.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报告标题生成。
 *
 * <h2>这里守的是「标题里不能有数字」</h2>
 *
 * <p>正文的数字全部可溯源，而标题里的数字**没法校验**——
 * 我们不知道它是从 facts 里抄的还是模型自己算的。
 *
 * <p>「保费下滑 15%」一旦写错，读者第一眼就看到错的信息，
 * 而正文可能完全正确——那种报告比没有报告更糟。
 *
 * <p>所以规则是"一个都不许"，而不是"必须能在 facts 里找到"。
 * 后者需要把数字传给模型，而给了数字模型就会用。
 */
class ReportTitlerTest {

    /** 固定返回一段文本的假模型。 */
    private static ReportTitler.TitleSource fake(String output) {
        return (prompt, sys, u, s) -> new Generated<>(output, new TokenUsage(10, 5, 0, 0, 0));
    }

    /** 抛异常的假模型。 */
    private static ReportTitler.TitleSource broken() {
        return (prompt, sys, u, s) -> {
            throw new IllegalStateException("模型不可用");
        };
    }

    @Nested
    @DisplayName("数字校验")
    class Numbers {

        @Test
        @DisplayName("★ 标题含阿拉伯数字 → 退回降级")
        void rejectsArabicNumber() {
            var got = ReportTitler.title(fake("保费下滑 15% 的归因"), "保费为什么下降",
                    List.of("各月保费"), "u1", "s1");

            assertThat(got.value()).as("含数字的标题一律拒绝")
                    .isEqualTo("保费为什么下降");
        }

        @Test
        @DisplayName("★ 标题含小数 → 拒绝")
        void rejectsDecimal() {
            var got = ReportTitler.title(fake("保费下降 46.7%"), "保费趋势",
                    List.of(), "u1", "s1");

            assertThat(got.value()).isEqualTo("保费趋势");
        }

        @Test
        @DisplayName("★ 标题含金额 → 拒绝")
        void rejectsMoney() {
            var got = ReportTitler.title(fake("总保费 ¥882821"), "保费分析",
                    List.of(), "u1", "s1");

            assertThat(got.value()).isEqualTo("保费分析");
        }

        @Test
        @DisplayName("★ 中文数字量词 → 拒绝（「下滑三成」同样是编的）")
        void rejectsChineseNumber() {
            var got = ReportTitler.title(fake("保费下滑三成"), "保费分析",
                    List.of(), "u1", "s1");

            assertThat(got.value()).isEqualTo("保费分析");
        }

        @Test
        @DisplayName("★ 没有数字的标题 → 采纳")
        void acceptsCleanTitle() {
            var got = ReportTitler.title(fake("保费下滑归因"), "保费为什么下降",
                    List.of("各月保费", "区域分布"), "u1", "s1");

            assertThat(got.value()).isEqualTo("保费下滑归因");
        }

        @Test
        @DisplayName("普通词里的「一」「三」不算数字——「统一口径」不该被误伤")
        void doesNotFlagPlainWords() {
            var got = ReportTitler.title(fake("统一口径的保费分析"), "保费",
                    List.of(), "u1", "s1");

            assertThat(got.value()).isEqualTo("统一口径的保费分析");
        }
    }

    @Nested
    @DisplayName("清洗")
    class Cleaning {

        @Test
        @DisplayName("去掉「标题：」前缀")
        void stripsPrefix() {
            var got = ReportTitler.title(fake("标题：保费下滑归因"), "保费为什么下降",
                    List.of(), "u1", "s1");

            assertThat(got.value()).isEqualTo("保费下滑归因");
        }

        @Test
        @DisplayName("去掉包裹的引号与书名号")
        void stripsQuotes() {
            assertThat(ReportTitler.title(fake("「保费下滑归因」"), "x", List.of(), "u", "s").value())
                    .isEqualTo("保费下滑归因");
            assertThat(ReportTitler.title(fake("\"保费下滑归因\""), "x", List.of(), "u", "s").value())
                    .isEqualTo("保费下滑归因");
        }

        @Test
        @DisplayName("★ 只取第一行——模型有时会输出标题 + 解释")
        void takesFirstLine() {
            var got = ReportTitler.title(fake("保费下滑归因\n这份报告分析了……"), "x",
                    List.of(), "u", "s");

            assertThat(got.value()).isEqualTo("保费下滑归因");
        }

        @Test
        @DisplayName("超长输出（>60 字）→ 退回降级，说明模型没照规则写")
        void tooLongFallsBack() {
            String longTitle = "这是一个非常非常长的标题".repeat(6);
            var got = ReportTitler.title(fake(longTitle), "原始意图",
                    List.of(), "u", "s");

            assertThat(got.value()).isEqualTo("原始意图");
        }
    }

    @Nested
    @DisplayName("降级")
    class Fallback {

        @Test
        @DisplayName("★ 没有模型 → 直接用 intent，不报错")
        void noModelUsesIntent() {
            var got = ReportTitler.title(null, "保费为什么下降", List.of(), "u", "s");

            assertThat(got.value()).isEqualTo("保费为什么下降");
            assertThat(got.usage().known()).isFalse();
        }

        @Test
        @DisplayName("★ 模型抛异常 → 退回降级，报告仍能生成")
        void brokenModelFallsBack() {
            var got = ReportTitler.title(broken(), "保费为什么下降", List.of(), "u", "s");

            assertThat(got.value()).as("标题难看可以接受，生成不出来不行")
                    .isEqualTo("保费为什么下降");
        }

        @Test
        @DisplayName("模型返回空 → 退回降级")
        void emptyOutputFallsBack() {
            assertThat(ReportTitler.title(fake(""), "原始意图", List.of(), "u", "s").value())
                    .isEqualTo("原始意图");
            assertThat(ReportTitler.title(fake("   "), "原始意图", List.of(), "u", "s").value())
                    .isEqualTo("原始意图");
        }

        @Test
        @DisplayName("intent 超长时截断到 40 字")
        void longIntentTruncated() {
            String longIntent = "为什么 2025 年华东区保费比 2024 年下降，具体是哪些险种和客户等级造成的";
            var got = ReportTitler.title(null, longIntent, List.of(), "u", "s");

            assertThat(got.value()).endsWith("…");
            assertThat(got.value().length()).isLessThanOrEqualTo(41);
        }

        @Test
        @DisplayName("intent 也为空 → 「分析报告」")
        void noIntentAtAll() {
            assertThat(ReportTitler.title(null, null, List.of(), "u", "s").value())
                    .isEqualTo("分析报告");
        }
    }

    @Nested
    @DisplayName("章节标题")
    class SectionTitles {

        @Test
        @DisplayName("★ 章节标题同样不许有数字")
        void sectionRejectsNumbers() {
            var got = ReportTitler.sectionTitle(fake("各区域保费占比 48.9%"), "报告主题",
                    "各区域中客户等级的保费占比", "u", "s");

            assertThat(got.value()).isEqualTo("各区域中客户等级的保费占比");
        }

        @Test
        @DisplayName("没有模型时用 label")
        void noModelUsesLabel() {
            var got = ReportTitler.sectionTitle(null, "主题", "各月保费走势", "u", "s");

            assertThat(got.value()).isEqualTo("各月保费走势");
        }

        @Test
        @DisplayName("label 为空时退回 intent")
        void emptyLabelUsesIntent() {
            var got = ReportTitler.sectionTitle(null, "报告意图", null, "u", "s");

            assertThat(got.value()).isEqualTo("报告意图");
        }
    }
}

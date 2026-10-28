package com.liusongboyy.chatdata.engine.agent;

import com.liusongboyy.chatdata.engine.analysis.AnalysisPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分析计划的解析。
 *
 * <p>重点不是"能解析"，而是**坏计划一律整条拒绝**：跳过坏步骤执行剩余的，
 * 会得出一个看起来完整、实则少了一块的结论。
 */
class AnalysisPlanParserTest {

    @Test
    @DisplayName("★ 两步计划：每步的 DSL 由同一个 DslParser 解析")
    void parsesTwoSteps() {
        AnalysisPlan plan = AnalysisPlanParser.parse("""
                {"intent":"2025 年保费为什么下降",
                 "steps":[
                   {"id":"s1","label":"两年保费总额",
                    "dsl":{"metrics":["保费"],"dimensions":[],"filters":[],
                           "compare":{"mode":"yoy"},"timeRange":{"kind":"2025年"}}},
                   {"id":"s2","label":"各险种保费",
                    "dsl":{"metrics":["保费"],"dimensions":["险种类型"],"filters":[],
                           "timeRange":{"kind":"2025年"}}}
                 ]}""");

        assertThat(plan.intent()).isEqualTo("2025 年保费为什么下降");
        assertThat(plan.steps()).hasSize(2);
        assertThat(plan.steps().get(1).label()).isEqualTo("各险种保费");
        assertThat(plan.steps().get(1).dsl().dimensions()).containsExactly("险种类型");
        assertThat(plan.steps().get(0).dsl().compare().mode())
                .isEqualTo(com.liusongboyy.chatdata.engine.nlsql.AskDsl.Compare.Mode.YOY);
    }

    @Test
    @DisplayName("省略 id 时按顺序生成，label 缺省用 id")
    void defaultsIdAndLabel() {
        AnalysisPlan plan = AnalysisPlanParser.parse("""
                {"intent":"看看","steps":[{"dsl":{"metrics":["保费"],"dimensions":[],"filters":[]}}]}""");

        assertThat(plan.steps().get(0).id()).isEqualTo("s1");
        assertThat(plan.steps().get(0).label()).isEqualTo("s1");
    }

    @Test
    @DisplayName("★ 某一步的 DSL 不合法 → 整条计划作废，不跳过")
    void brokenStepRejectsWholePlan() {
        assertThatThrownBy(() -> AnalysisPlanParser.parse("""
                {"intent":"x","steps":[
                  {"id":"s1","dsl":{"metrics":["保费"],"dimensions":[],"filters":[]}},
                  {"id":"s2","dsl":{
                     "metrics":["保费"],"dimensions":[],"filters":[],
                     "sort":[{"field":"保费","direction":"上去"}]}}]}"""))
                .isInstanceOf(DslGenerationException.class)
                .hasMessageContaining("步骤「s2」");
    }

    @Test
    @DisplayName("★ 两个步骤是同一个查询 → 拒绝：白烧一次查询与一次模型调用")
    void duplicateStepRejected() {
        String dsl = "{\"metrics\":[\"保费\"],\"dimensions\":[],\"filters\":[]}";
        assertThatThrownBy(() -> AnalysisPlanParser.parse("""
                {"intent":"x","steps":[
                  {"id":"s1","dsl":%s},{"id":"s2","dsl":%s}]}""".formatted(dsl, dsl)))
                .isInstanceOf(DslGenerationException.class)
                .hasMessageContaining("同一个查询");
    }

    @Test
    @DisplayName("id 重复 → 拒绝（结论里引用步骤时会指错）")
    void duplicateIdRejected() {
        assertThatThrownBy(() -> AnalysisPlanParser.parse("""
                {"intent":"x","steps":[
                  {"id":"s1","dsl":{"metrics":["保费"],"dimensions":[],"filters":[]}},
                  {"id":"s1","dsl":{"metrics":["保单数"],"dimensions":[],"filters":[]}}]}"""))
                .isInstanceOf(DslGenerationException.class)
                .hasMessageContaining("id 重复");
    }

    @Test
    @DisplayName("超过 4 步 → 拒绝（成本与延迟的闸门）")
    void tooManyStepsRejected() {
        StringBuilder steps = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            if (i > 1) {
                steps.append(',');
            }
            steps.append("{\"id\":\"s").append(i)
                    .append("\",\"dsl\":{\"metrics\":[\"指标").append(i)
                    .append("\"],\"dimensions\":[],\"filters\":[]}}");
        }
        assertThatThrownBy(() -> AnalysisPlanParser.parse(
                "{\"intent\":\"x\",\"steps\":[" + steps + "]}"))
                .isInstanceOf(DslGenerationException.class)
                .hasMessageContaining("最多 4 步");
    }

    @Test
    @DisplayName("★ 同比缺 timeRange → 从问题里捞出年份补上，不丢掉对比")
    void infersTimeRangeForCompare() {
        AnalysisPlan plan = AnalysisPlanParser.parse("""
                {"intent":"同比","steps":[{"id":"s1","dsl":{
                   "metrics":["保费"],"dimensions":[],"filters":[],
                   "compare":{"mode":"yoy"}}}]}""", "2025 年保费比 2024 年下降了吗，2025 年的情况？");

        assertThat(plan.steps().get(0).dsl().timeRange().kind()).isEqualTo("2025年");
        assertThat(plan.steps().get(0).dsl().compare()).isNotNull();
    }

    @Test
    @DisplayName("★ 同比按天分组 → 提到按月，而不是丢掉对比")
    void bumpsGranularityForYearOverYear() {
        AnalysisPlan plan = AnalysisPlanParser.parse("""
                {"intent":"同比","steps":[{"id":"s1","dsl":{
                   "metrics":["保费"],"dimensions":["生效日期"],"filters":[],
                   "granularity":"day","timeRange":{"kind":"2025年"},
                   "compare":{"mode":"yoy"}}}]}""", "2025 年各月保费同比");

        assertThat(plan.steps().get(0).dsl().granularity())
                .isEqualTo(com.liusongboyy.chatdata.engine.nlsql.AskDsl.Granularity.MONTH);
        assertThat(plan.steps().get(0).dsl().compare()).isNotNull();
    }

    @Test
    @DisplayName("问题里没有明确年份、compare 又缺 timeRange → 才丢弃对比")
    void dropsCompareOnlyWhenUnfixable() {
        AnalysisPlan plan = AnalysisPlanParser.parse("""
                {"intent":"x","steps":[{"id":"s1","dsl":{
                   "metrics":["保费"],"dimensions":[],"filters":[],
                   "compare":{"mode":"yoy"}}}]}""", "保费同比怎么样");

        assertThat(plan.steps().get(0).dsl().compare()).isNull();
    }

    @Test
    @DisplayName("套了 markdown 代码块也能解析")
    void markdownFence() {
        AnalysisPlan plan = AnalysisPlanParser.parse("""
                ```json
                {"intent":"x","steps":[{"dsl":{"metrics":["保费"],"dimensions":[],"filters":[]}}]}
                ```""");
        assertThat(plan.steps()).hasSize(1);
    }
}

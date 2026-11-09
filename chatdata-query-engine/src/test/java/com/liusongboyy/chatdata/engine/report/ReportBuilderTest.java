package com.liusongboyy.chatdata.engine.report;

import com.liusongboyy.chatdata.engine.chat.ChartSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 报告拼装。
 *
 * <h2>为什么这里要测得这么细</h2>
 *
 * <p>合并逻辑是整个报告功能里**最容易错**的地方：口径分组、章节切分、
 * 冲突检测——每一条错了都不会报错，只会让报告看起来"怪怪的"。
 * 而报告是**要发出去**的东西，怪怪的等于不可信。
 *
 * <p>它是纯函数，所以能测透。
 */
class ReportBuilderTest {

    private static ReportScope scope(String time, String... filters) {
        return new ReportScope(List.of("保费"), List.of("区域"), time, List.of(filters));
    }

    /** 一轮深入分析。 */
    private static ReportSource analysis(String msgId, String intent, String narrative,
                                         ReportScope scope) {
        StepSnapshot step = new StepSnapshot("各月保费走势", "确认是否真的在下降",
                List.of("月份", "保费"),
                List.of(List.of("2026-01", 51873.67), List.of("2026-05", 27634.11)),
                ChartSpec.table("趋势"), "SELECT 1", scope, 2);
        return new ReportSource(msgId, intent,
                new ReportSource.AnalysisPayload(intent, List.of(step),
                        List.of(new FindingView("TREND", "保费从 51873.67 降到 27634.11（-46.7%）",
                                java.util.Map.of("changePct", -46.7))),
                        narrative),
                List.of(), List.of(), null, null, scope);
    }

    /** 一轮单查询。 */
    private static ReportSource single(String msgId, String question, ReportScope scope) {
        return new ReportSource(msgId, question, null,
                List.of("区域", "保费"),
                List.of(List.of("华东", 1000), List.of("华南", 2000)),
                ChartSpec.table("柱状"), "SELECT 2", scope);
    }

    @Nested
    @DisplayName("章节切分")
    class Sections {

        @Test
        @DisplayName("★ 有分析 → 分析章节；单查询 → 数据章节，两者不混")
        void splitsByKind() {
            var report = ReportBuilder.build("c1", "u1", List.of(
                    analysis("m1", "保费为什么下降", "因为 4 月暴跌", scope("今年")),
                    single("m2", "各区域保费", scope("今年"))));

            assertThat(report.body().sections()).as("分析章节").hasSize(1);
            assertThat(report.body().sections().get(0).kind())
                    .isEqualTo(ReportSection.Kind.ANALYSIS);
            assertThat(report.body().dataSections()).as("数据章节").hasSize(1);
            assertThat(report.body().dataSections().get(0).kind())
                    .isEqualTo(ReportSection.Kind.DATA);
        }

        @Test
        @DisplayName("★ 单查询章节**没有 findings**——它本来就没有结论")
        void dataSectionHasNoFindings() {
            var report = ReportBuilder.build("c1", "u1",
                    List.of(single("m2", "各区域保费", scope("今年"))));

            assertThat(report.body().dataSections().get(0).findings()).isEmpty();
        }

        @Test
        @DisplayName("全是单查询也能出报告——只是整份都是「数据」")
        void allSingleQueriesIsValid() {
            var report = ReportBuilder.build("c1", "u1", List.of(
                    single("m1", "各区域保费", scope("今年")),
                    single("m2", "各险种保费", scope("今年"))));

            assertThat(report.body().sections()).isEmpty();
            assertThat(report.body().dataSections()).hasSize(2);
            assertThat(report.body().sectionCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("空的轮次列表 → 明确报错，不生成空报告")
        void emptySourcesRejected() {
            assertThatThrownBy(() -> ReportBuilder.build("c1", "u1", List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("至少要选一条");
        }
    }

    @Nested
    @DisplayName("事实去重")
    class FindingDedup {

        @Test
        @DisplayName("★ 两节都提到同一个事实 → 只在第一节保留")
        void sameFactAcrossSectionsKeptOnce() {
            // 实测：一个会话里两轮分析都在讲「普通客户占 33.9%」——
            // 同一个事实重复两遍，读者会以为是两件不同的事
            var f = new FindingView("CONTRIBUTION", "整体上「普通」的保费占 33.9%",
                    java.util.Map.of("pct", 33.9));

            StepSnapshot s1 = new StepSnapshot("第一步", null, List.of("a","b"),
                    List.of(List.of("x", 1)), ChartSpec.table("t"), "SQL", scope("今年"), 1);
            StepSnapshot s2 = new StepSnapshot("第二步", null, List.of("a","b"),
                    List.of(List.of("y", 2)), ChartSpec.table("t"), "SQL", scope("今年"), 1);

            ReportSource a1 = new ReportSource("m1", "q1",
                    new ReportSource.AnalysisPayload("意图一", List.of(s1), List.of(f), "n1"),
                    List.of(), List.of(), null, null, scope("今年"));
            ReportSource a2 = new ReportSource("m2", "q2",
                    new ReportSource.AnalysisPayload("意图二", List.of(s2), List.of(f), "n2"),
                    List.of(), List.of(), null, null, scope("今年"));

            var report = ReportBuilder.build("c1", "u1", List.of(a1, a2));

            assertThat(report.body().sections().get(0).findings()).hasSize(1);
            assertThat(report.body().sections().get(1).findings())
                    .as("第二节的同一个事实应当被去掉")
                    .isEmpty();
        }

        @Test
        @DisplayName("不同事实都保留")
        void differentFactsKept() {
            var f1 = new FindingView("TREND", "保费下降 46.7%", java.util.Map.of());
            var f2 = new FindingView("CONTRIBUTION", "普通客户占 33.9%", java.util.Map.of());

            StepSnapshot s1 = new StepSnapshot("一", null, List.of("a","b"),
                    List.of(List.of("x", 1)), ChartSpec.table("t"), "SQL", scope("今年"), 1);
            StepSnapshot s2 = new StepSnapshot("二", null, List.of("a","b"),
                    List.of(List.of("y", 2)), ChartSpec.table("t"), "SQL", scope("今年"), 1);

            ReportSource a1 = new ReportSource("m1", "q1",
                    new ReportSource.AnalysisPayload("i1", List.of(s1), List.of(f1), null),
                    List.of(), List.of(), null, null, scope("今年"));
            ReportSource a2 = new ReportSource("m2", "q2",
                    new ReportSource.AnalysisPayload("i2", List.of(s2), List.of(f2), null),
                    List.of(), List.of(), null, null, scope("今年"));

            var report = ReportBuilder.build("c1", "u1", List.of(a1, a2));

            assertThat(report.body().sections().get(0).findings()).hasSize(1);
            assertThat(report.body().sections().get(1).findings()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("章节标题降级")
    class SectionTitle {

        @Test
        @DisplayName("★ 用第一步的 label，不用 intent")
        void usesStepLabelNotIntent() {
            // 实测：intent 常常是一整句分析目标
            // （「看除西南外其他区域里各客户等级的保费构成，找出还有哪些区域偏离整体水平」），
            // 当章节名太长，而且说的是"要做什么"不是"这节讲什么"
            String longIntent = "看除「西南」外其他区域里各客户等级的保费构成，找出还有哪些区域偏离整体水平";
            StepSnapshot step = new StepSnapshot("各区域中客户等级的保费占比", "对比各区域",
                    List.of("区域", "客户等级", "保费"),
                    List.of(List.of("华东", "普通", 100)),
                    ChartSpec.table("t"), "SQL", scope("今年"), 1);
            ReportSource src = new ReportSource("m1", "问题", 
                    new ReportSource.AnalysisPayload(longIntent, List.of(step), List.of(), null),
                    List.of(), List.of(), null, null, scope("今年"));

            var report = ReportBuilder.build("c1", "u1", List.of(src));

            assertThat(report.body().sections().get(0).title())
                    .isEqualTo("各区域中客户等级的保费占比");
        }

        @Test
        @DisplayName("没有 label 才退回 intent")
        void fallsBackToIntent() {
            StepSnapshot step = new StepSnapshot(null, null,
                    List.of("a", "b"), List.of(List.of("x", 1)),
                    ChartSpec.table("t"), "SQL", scope("今年"), 1);
            ReportSource src = new ReportSource("m1", "问题",
                    new ReportSource.AnalysisPayload("这是意图", List.of(step), List.of(), null),
                    List.of(), List.of(), null, null, scope("今年"));

            var report = ReportBuilder.build("c1", "u1", List.of(src));

            assertThat(report.body().sections().get(0).title()).isEqualTo("这是意图");
        }
    }

    @Nested
    @DisplayName("去重")
    class Dedup {

        @Test
        @DisplayName("★ 同一个问题问了 4 次 → 报告里只留 1 张表")
        void repeatedQuestionKeptOnce() {
            // 真实场景：一个 6 轮会话里 4 轮是同一句「按客户等级看保费」
            // （刷新、重试、忘了问过）。不去重的话报告里会有 4 张一模一样的表
            var report = ReportBuilder.build("c1", "u1", List.of(
                    single("m1", "按客户等级看保费", scope("今年")),
                    single("m2", "按客户等级看保费", scope("今年")),
                    single("m3", "按客户等级看保费", scope("今年")),
                    single("m4", "按客户等级看保费", scope("今年"))));

            assertThat(report.body().dataSections()).hasSize(1);
            assertThat(report.sourceMessageIds())
                    .as("只记去重后真正进了报告的那条")
                    .containsExactly("m1");
        }

        @Test
        @DisplayName("★ 保留最早那次——报告是分析过程的记录")
        void keepsFirst() {
            var report = ReportBuilder.build("c1", "u1", List.of(
                    single("m1", "问 A", scope("今年")),
                    single("m2", "问 A", scope("今年"))));

            assertThat(report.sourceMessageIds()).containsExactly("m1");
        }

        @Test
        @DisplayName("★ 不同问题不去重")
        void differentQuestionsKept() {
            var report = ReportBuilder.build("c1", "u1", List.of(
                    single("m1", "按客户等级看保费", scope("今年")),
                    single("m2", "按区域看保费", scope("今年"))));

            assertThat(report.body().dataSections()).hasSize(2);
        }

        @Test
        @DisplayName("★ 同问题但结果行数不同 → 不去重（是两次有价值的快照）")
        void sameQuestionDifferentResultKept() {
            ReportSource a = new ReportSource("m1", "看保费", null,
                    List.of("区域", "保费"),
                    List.of(List.of("华东", 1000)),
                    ChartSpec.table("t"), "SQL", scope("今年"));
            ReportSource b = new ReportSource("m2", "看保费", null,
                    List.of("区域", "保费"),
                    List.of(List.of("华东", 1000), List.of("华南", 2000)),
                    ChartSpec.table("t"), "SQL", scope("今年"));

            var report = ReportBuilder.build("c1", "u1", List.of(a, b));

            assertThat(report.body().dataSections()).hasSize(2);
        }

        @Test
        @DisplayName("去重后全空 → 仍能出报告（只剩一条）")
        void dedupToOneStillWorks() {
            var report = ReportBuilder.build("c1", "u1", List.of(
                    single("m1", "同一句", scope("今年")),
                    single("m2", "同一句", scope("今年"))));

            assertThat(report.body().sectionCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("口径冲突")
    class ScopeConflict {

        @Test
        @DisplayName("★ 时间不同 → 冲突，必须提示")
        void differentTimeIsConflict() {
            var report = ReportBuilder.build("c1", "u1", List.of(
                    analysis("m1", "今年趋势", "n1", scope("今年")),
                    single("m2", "上个月明细", scope("上个月"))));

            assertThat(report.body().scopeConflict())
                    .as("两张不同时间范围的图并排，读者会误以为可比")
                    .isTrue();
            assertThat(report.body().scopeGroups()).hasSize(2);
        }

        @Test
        @DisplayName("★ 筛选不同 → 冲突")
        void differentFiltersIsConflict() {
            var report = ReportBuilder.build("c1", "u1", List.of(
                    single("m1", "华东", scope("今年", "区域 = 华东")),
                    single("m2", "全部", scope("今年"))));

            assertThat(report.body().scopeConflict()).isTrue();
        }

        @Test
        @DisplayName("★ 筛选顺序不同**不算**冲突——同一组条件而已")
        void filterOrderDoesNotMatter() {
            var report = ReportBuilder.build("c1", "u1", List.of(
                    single("m1", "a", scope("今年", "区域 = 华东", "状态 = 生效")),
                    single("m2", "b", scope("今年", "状态 = 生效", "区域 = 华东"))));

            assertThat(report.body().scopeConflict())
                    .as("顺序不同判成冲突，会让几乎每份报告都弹提示，用户学会无视它")
                    .isFalse();
            assertThat(report.body().scopeGroups()).hasSize(1);
        }

        @Test
        @DisplayName("★ 指标/维度不同**不算**冲突——那是分析的不同侧面")
        void differentMetricsNotConflict() {
            var s1 = new ReportScope(List.of("保费"), List.of("区域"), "今年", List.of());
            var s2 = new ReportScope(List.of("保单数"), List.of("险种"), "今年", List.of());

            var report = ReportBuilder.build("c1", "u1", List.of(
                    single("m1", "a", s1), single("m2", "b", s2)));

            assertThat(report.body().scopeConflict()).isFalse();
        }

        @Test
        @DisplayName("口径相同时只有一组")
        void sameScopeIsOneGroup() {
            var report = ReportBuilder.build("c1", "u1", List.of(
                    single("m1", "a", scope("今年")),
                    single("m2", "b", scope("今年"))));

            assertThat(report.body().scopeGroups()).hasSize(1);
            assertThat(report.body().scopeGroups().get(0).stepCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("结论与标题")
    class Conclusion {

        @Test
        @DisplayName("★ 结论 = 各轮 narrative 按顺序拼接，不重新生成")
        void mergesNarratives() {
            var report = ReportBuilder.build("c1", "u1", List.of(
                    analysis("m1", "i1", "第一轮结论", scope("今年")),
                    analysis("m2", "i2", "第二轮结论", scope("今年"))));

            assertThat(report.body().conclusion())
                    .isEqualTo("第一轮结论\n\n第二轮结论");
        }

        @Test
        @DisplayName("单查询没有 narrative，不参与结论")
        void singleQueriesContributeNoNarrative() {
            var report = ReportBuilder.build("c1", "u1", List.of(
                    analysis("m1", "i1", "有结论", scope("今年")),
                    single("m2", "没结论", scope("今年"))));

            assertThat(report.body().conclusion()).isEqualTo("有结论");
        }

        @Test
        @DisplayName("全都没有 narrative → 结论为 null，不编造")
        void noNarrativeMeansNull() {
            var report = ReportBuilder.build("c1", "u1",
                    List.of(single("m1", "各区域保费", scope("今年"))));

            assertThat(report.body().conclusion()).isNull();
        }

        @Test
        @DisplayName("★ 标题降级用 intent，且截断到 40 字")
        void titleFallsBackToIntent() {
            String longIntent = "为什么 2025 年华东区保费比 2024 年下降，具体是哪些险种和客户等级造成的";
            var report = ReportBuilder.build("c1", "u1",
                    List.of(analysis("m1", longIntent, "n", scope("今年"))));

            assertThat(report.title()).endsWith("…");
            assertThat(report.title().length()).isLessThanOrEqualTo(41);
            assertThat(report.modelKey()).as("降级路径没调模型").isNull();
        }
    }

    @Nested
    @DisplayName("快照截断")
    class Truncation {

        @Test
        @DisplayName("★ 超过 50 行要截断，但记住总行数")
        void capsRows() {
            List<List<Object>> many = java.util.stream.IntStream.range(0, 200)
                    .mapToObj(i -> List.<Object>of("r" + i, i)).toList();
            StepSnapshot big = new StepSnapshot("大表", null,
                    List.of("k", "v"), many, ChartSpec.table("t"), "SQL", scope("今年"), 200);

            StepSnapshot capped = big.capped();

            assertThat(capped.rows()).hasSize(StepSnapshot.MAX_ROWS);
            assertThat(capped.rowCount()).as("总行数要留着，附录据此说明「仅显示前 50 行」")
                    .isEqualTo(200);
            assertThat(capped.truncated()).isTrue();
        }

        @Test
        @DisplayName("没超限就不截断")
        void smallIsNotTruncated() {
            var report = ReportBuilder.build("c1", "u1",
                    List.of(single("m1", "小表", scope("今年"))));

            StepSnapshot step = report.body().dataSections().get(0).steps().get(0);
            assertThat(step.truncated()).isFalse();
            assertThat(step.rows()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("记录来源")
    class Provenance {

        @Test
        @DisplayName("★ 记下勾了哪几条消息——报告要能追溯到原问答")
        void recordsSourceMessageIds() {
            var report = ReportBuilder.build("c1", "u1", List.of(
                    analysis("m1", "i1", "n1", scope("今年")),
                    single("m2", "q2", scope("今年"))));

            assertThat(report.sourceMessageIds()).containsExactly("m1", "m2");
            assertThat(report.conversationId()).isEqualTo("c1");
            assertThat(report.username()).isEqualTo("u1");
        }

        @Test
        @DisplayName("生成时间必须写死——报告是历史文件")
        void generatedAtIsSet() {
            var report = ReportBuilder.build("c1", "u1",
                    List.of(single("m1", "q", scope("今年"))));

            assertThat(report.generatedAt()).isNotNull();
        }
    }
}

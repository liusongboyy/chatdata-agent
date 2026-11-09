package com.liusongboyy.chatdata.engine.report;

import java.util.List;

/**
 * 报告的一节。
 *
 * <h2>「分析」与「数据」是两种节，不能混</h2>
 *
 * <p>深入分析的轮次有 {@code findings} 与 {@code narrative}——那是**结论**。
 * 而单查询只有一张表和一张图——那是**数据**，没有结论。
 *
 * <p>把单查询放进「分析」章节，读者会以为那节有结论，翻到最后发现只有一张表。
 * 所以两种节分开列，并在界面上用不同的标题前缀（「二、分析」/「三、数据」）。
 *
 * @param kind    节的类型
 * @param title   章节名。**模型生成**；失败时降级为原始 label
 * @param purpose 这一步为什么查。来自 {@code StepResult.purpose}，
 *                它解释了「查这个是想验证什么」，是报告里最像分析师的文字
 * @param steps   该节的查询快照
 * @param findings 确定性计算的事实；「数据」节为空
 */
public record ReportSection(
        Kind kind,
        String title,
        String purpose,
        List<StepSnapshot> steps,
        List<FindingView> findings
) {

    public enum Kind {
        /** 深入分析：有 findings 与结论。 */
        ANALYSIS,
        /**
         * 单查询：只有数据。
         *
         * <p><b>刻意不与 ANALYSIS 合并。</b>它没有 findings，也没有结论——
         * 混进分析章节会让读者以为那节有观点，而实际上只有一张表。
         */
        DATA
    }

    public ReportSection {
        steps = steps == null ? List.of() : List.copyOf(steps);
        findings = findings == null ? List.of() : List.copyOf(findings);
        title = (title == null || title.isBlank()) ? "未命名" : title.trim();
    }

    /** 这一节涉及的所有口径。一节里可能有多步，每步口径可能不同。 */
    public List<ReportScope> scopes() {
        return steps.stream().map(StepSnapshot::scope).toList();
    }
}

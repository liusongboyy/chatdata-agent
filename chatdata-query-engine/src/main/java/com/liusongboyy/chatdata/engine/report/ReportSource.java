package com.liusongboyy.chatdata.engine.report;

import java.util.List;

/**
 * 一份待合并的问答记录。
 *
 * <p>它由上层从 {@code ask_message} 反序列化出来——引擎层不认识那张表。
 *
 * @param messageId  {@code ask_message.id}，报告要记下勾了哪几条
 * @param question   用户问题
 * @param analysis   深入分析的结果；**为 null 表示这是单查询**
 * @param columns    结果列（单查询时用它）
 * @param rows       结果行（单查询时用它）
 * @param chart      图表规格（单查询时用它）
 * @param sqlDisplay 展示用 SQL
 * @param scope      口径
 */
public record ReportSource(
        String messageId,
        String question,
        AnalysisPayload analysis,
        List<String> columns,
        List<List<Object>> rows,
        com.liusongboyy.chatdata.engine.chat.ChartSpec chart,
        String sqlDisplay,
        ReportScope scope
) {

    public ReportSource {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
        scope = scope == null ? new ReportScope(List.of(), List.of(), null, List.of()) : scope;
    }

    /**
     * 深入分析的结果。**只保留报告需要的部分。**
     *
     * <p>不直接复用 {@code AnalysisResult}：那个带 {@code RenderedSql}、
     * {@code QueryResult} 等引擎类型，而报告要能独立序列化进数据库。
     *
     * @param intent   这次分析想解决什么
     * @param steps    每步的快照
     * @param findings 确定性事实
     * @param narrative 模型写的结论
     */
    public record AnalysisPayload(
            String intent,
            List<StepSnapshot> steps,
            List<FindingView> findings,
            String narrative
    ) {
        public AnalysisPayload {
            intent = intent == null ? "" : intent.trim();
            steps = steps == null ? List.of() : List.copyOf(steps);
            findings = findings == null ? List.of() : List.copyOf(findings);
            narrative = (narrative == null || narrative.isBlank()) ? null : narrative.trim();
        }
    }

    /** 这一轮有没有分析。 */
    public boolean hasAnalysis() {
        return analysis != null && !analysis.steps().isEmpty();
    }
}

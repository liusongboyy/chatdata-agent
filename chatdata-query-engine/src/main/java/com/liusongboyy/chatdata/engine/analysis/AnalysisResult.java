package com.liusongboyy.chatdata.engine.analysis;

import java.util.List;

/**
 * 一次分析的完整结果。
 *
 * @param intent     用户想解决的问题
 * @param steps      每个步骤的结果（含失败的）
 * @param findings   确定性计算出来的事实
 * @param narrative  模型写的结论（1–3 句）。**可为空**：模型不可用时界面仍展示步骤与事实
 * @param queryCount 实际执行的查询次数
 * @param truncated  是否因上限被截断（行数/耗时/步骤）。截断必须在界面上说明
 */
public record AnalysisResult(
        String intent,
        List<StepResult> steps,
        List<Finding> findings,
        String narrative,
        int queryCount,
        boolean truncated
) {

    public AnalysisResult {
        steps = steps == null ? List.of() : List.copyOf(steps);
        findings = findings == null ? List.of() : List.copyOf(findings);
        intent = intent == null ? "" : intent.trim();
        narrative = (narrative == null || narrative.isBlank()) ? null : narrative.trim();
    }

    public boolean allFailed() {
        return !steps.isEmpty() && steps.stream().noneMatch(StepResult::isOk);
    }
}

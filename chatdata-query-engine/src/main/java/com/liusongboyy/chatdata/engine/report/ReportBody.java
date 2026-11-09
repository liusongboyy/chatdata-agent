package com.liusongboyy.chatdata.engine.report;

import java.util.List;

/**
 * 报告的正文。
 *
 * @param conclusion    汇总结论。**模型写**，数字全部来自 findings
 * @param sections      分析章节（有 findings）
 * @param dataSections  数据章节（单查询，无 findings）
 * @param scopeConflict 口径是否冲突。true 时前端在顶部显眼位置提示
 * @param scopeGroups   按口径分组的说明。冲突时每组单列，不合并成一句
 */
public record ReportBody(
        String conclusion,
        List<ReportSection> sections,
        List<ReportSection> dataSections,
        boolean scopeConflict,
        List<ScopeGroup> scopeGroups
) {

    /**
     * 一组口径相同的数据。
     *
     * @param scope     口径
     * @param stepCount 这一组里有几步
     * @param labels    这些步的标签，让读者知道「哪几张图是同一范围」
     */
    public record ScopeGroup(ReportScope scope, int stepCount, List<String> labels) {
        public ScopeGroup {
            labels = labels == null ? List.of() : List.copyOf(labels);
        }
    }

    public ReportBody {
        conclusion = (conclusion == null || conclusion.isBlank()) ? null : conclusion.trim();
        sections = sections == null ? List.of() : List.copyOf(sections);
        dataSections = dataSections == null ? List.of() : List.copyOf(dataSections);
        scopeGroups = scopeGroups == null ? List.of() : List.copyOf(scopeGroups);
    }

    /** 报告里一共几节（含数据节）。 */
    public int sectionCount() {
        return sections.size() + dataSections.size();
    }

    public boolean empty() {
        return sectionCount() == 0;
    }
}

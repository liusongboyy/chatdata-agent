package com.liusongboyy.chatdata.engine.analysis;

import com.liusongboyy.chatdata.engine.nlsql.AskDsl;

/**
 * 分析计划里的一步。
 *
 * <p><strong>每一步都是一个完整的问数 DSL</strong>，与单查询用的结构完全一样。
 * 这是编排能安全存在的前提：行级权限、脱敏、只读、行数上限不需要为编排再实现一遍，
 * 它们由渲染器逐步施加。
 *
 * @param id      稳定标识（s1 / s2…）。结论里引用它，界面上也靠它对应表格
 * @param label   人话标签，如「各险种的保费」。界面上作为小标题
 * @param purpose 这一步想回答什么，给叙述阶段做上下文；可为空
 * @param dsl     问数 DSL
 */
public record PlanStep(String id, String label, String purpose, AskDsl dsl) {

    public PlanStep {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("分析步骤必须有 id");
        }
        if (dsl == null) {
            throw new IllegalArgumentException("分析步骤「" + id + "」缺少 DSL");
        }
        id = id.trim();
        label = (label == null || label.isBlank()) ? id : label.trim();
        purpose = purpose == null ? "" : purpose.trim();
    }
}

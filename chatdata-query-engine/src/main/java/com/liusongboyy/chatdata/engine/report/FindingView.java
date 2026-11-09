package com.liusongboyy.chatdata.engine.report;

import java.util.Map;

/**
 * 一条确定性事实，报告里可直接引用。
 *
 * <h2>与 {@code analysis.Finding} 的关系</h2>
 *
 * <p>那个是**计算产物**（带 evidence 明细、带 stepId），这个是**展示形态**。
 * 分开是因为报告要长期存进数据库——把引擎内部类型写进报告的快照格式，
 * 等于让报告格式跟着引擎重构一起变，而报告一旦生成就不该变。
 *
 * @param kind     事实类型（TREND / CONTRIBUTION / COMPARE / OUTLIER / SCALAR）
 * @param text     给人看的一句话。**里面每个数字都经过 NarrativeGuard 校验**
 * @param evidence 证据。给想深究的读者看，也用于数字白名单校验
 */
public record FindingView(
        String kind,
        String text,
        Map<String, Object> evidence
) {

    public FindingView {
        text = text == null ? "" : text.trim();
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }
}

package com.liusongboyy.chatdata.engine.analysis;

/**
 * 把确定性事实写成 1–3 句结论。
 *
 * <p><strong>它只负责措辞。</strong>输入里的数字全部由 {@link InsightCalculator}
 * 算好，模型不允许引入新的数字——由 {@link NarrativeGuard} 在写回前校验。
 */
public interface NarrativeSource {

    /**
     * @return 结论文本（可能为 null 表示模型没给）+ 本次调用消耗的 token
     */
    com.liusongboyy.chatdata.engine.agent.Generated<String> narrate(
            String prompt, String userId, String sessionId);
}

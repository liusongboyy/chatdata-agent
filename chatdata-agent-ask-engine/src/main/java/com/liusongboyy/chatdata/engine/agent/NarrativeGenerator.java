package com.liusongboyy.chatdata.engine.agent;

import com.liusongboyy.chatdata.engine.analysis.NarrativeSource;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;

import java.util.List;

/**
 * 让模型把确定性事实写成结论。
 *
 * <p><strong>不给它看表格，只给它看 findings。</strong>看了几十行原始数字，
 * 模型会开始"自己发现规律"——而那些规律既没算过、也进不了审计。
 */
public final class NarrativeGenerator implements NarrativeSource {

    private final Model model;
    /** 固定系统提示：这一次它只需要写句子，不需要任何工具与上下文。 */
    private static final String SYS = "你是一个严谨的数据分析助手，只根据给定的事实写结论，不编造数字。";

    public NarrativeGenerator(Model model) {
        this.model = model;
    }

    @Override
    public String narrate(String prompt, String userId, String sessionId) {
        ReActAgent agent = ReActAgent.builder()
                .name("narrative")
                .sysPrompt(SYS)
                .model(model)
                .build();

        RuntimeContext ctx = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .build();

        // **只传 UserMessage。** 系统提示已经由 builder.sysPrompt 给了；
        // 再往 messages 里塞一条 SystemMessage，agentscope 会直接拒绝：
        // 「Hooks must not inject SYSTEM messages into PreCallEvent.inputMessages」。
        // 这个错误此前是静默的——异常被 AnalysisService 吞掉、退回确定性文本，
        // 所以"模型写结论"看似一直正常，实际从未成功过一次。
        Msg reply = agent.call(List.of(new UserMessage(prompt)), ctx).block();
        if (reply == null) {
            return null;
        }
        String text = reply.getTextContent();
        return (text == null || text.isBlank()) ? null : text.trim();
    }
}

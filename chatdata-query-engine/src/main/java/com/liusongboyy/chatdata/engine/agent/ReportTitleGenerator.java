package com.liusongboyy.chatdata.engine.agent;

import com.liusongboyy.chatdata.engine.report.ReportTitler;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;

import java.util.List;

/**
 * 用模型给报告写标题。
 *
 * <h2>与 {@link NarrativeGenerator} 的区别</h2>
 *
 * <p>那个写「结论」，这个写「标签」。两者的 prompt、约束、失败处理都不同：
 *
 * <table>
 *   <tr><th></th><th>结论</th><th>标题</th></tr>
 *   <tr><td>长度</td><td>1–3 句</td><td>≤ 20 字</td></tr>
 *   <tr><td>数字</td><td>必须有，且必须来自 facts</td><td><b>一个都不许有</b></td></tr>
 *   <tr><td>失败</td><td>退回确定性文本</td><td>退回 intent / label</td></tr>
 * </table>
 *
 * <p>合成一个类会让实现里出现 {@code if (isTitle)} 分支——而那种分支
 * 迟早会在某次改动中把两者的约束弄混。
 *
 * <h2>为什么不用 streamEvents</h2>
 *
 * <p>标题只调一次模型（不是 ReAct 循环），{@code call()} 的返回消息上的 usage
 * 就是全部消耗。这里与 DSL 生成不同——那个会多轮重问，必须累加。
 */
public final class ReportTitleGenerator implements ReportTitler.TitleSource {

    private final Model model;

    public ReportTitleGenerator(Model model) {
        this.model = model;
    }

    @Override
    public Generated<String> title(String prompt, String sys, String userId, String sessionId) {
        ReActAgent agent = ReActAgent.builder()
                .name("report-titler")
                .sysPrompt(sys)
                .model(model)
                .build();

        RuntimeContext ctx = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .build();

        // **只传 UserMessage。** 系统提示已经由 builder.sysPrompt 给了；
        // 再塞一条 SystemMessage 会被 agentscope 拒绝
        // （见 NarrativeGenerator 里记的那个坑）
        Msg reply = agent.call(List.of(new UserMessage(prompt)), ctx).block();
        if (reply == null) {
            return Generated.of(null);
        }
        String text = reply.getTextContent();
        return new Generated<>(text, UsageCollector.of(reply));
    }
}

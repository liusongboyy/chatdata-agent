package com.liusongboyy.chatdata.mesh.a2a;

import com.liusongboyy.chatdata.mesh.config.MeshProperties;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * A2A 的 Agent 实现。
 *
 * <h2>本步骤（第 2 步）只验证端点能通</h2>
 *
 * <p>{@link #streamEvents} 现在返回一条固定答复，**不接 ask、不换令牌**。
 * 目的是先把「Nacos 能发现、A2A 能调通」这条链路验证掉，
 * 再把真实查询接进来（第 4 步）。
 *
 * <p>这样拆的好处：如果 A2A 调不通，问题一定在协议层（AgentCard 字段、
 * transport 配置、路径），而不会跟身份或问数的问题混在一起。
 *
 * <h2>AgentRunner 只有四个方法</h2>
 *
 * <p>适配层很薄是刻意的：A2A 的协议细节（task 生命周期、artifact 组装）
 * 由 starter 处理，这里只管「输入文本 → 输出结果」。
 */
@Component
public class AskAgentRunner implements AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AskAgentRunner.class);

    private final MeshProperties properties;

    public AskAgentRunner(MeshProperties properties) {
        this.properties = properties;
    }

    @Override
    public String getAgentName() {
        return properties.getA2a().getAgentName();
    }

    @Override
    public String getAgentDescription() {
        return properties.getA2a().getAgentDescription();
    }

    /**
     * 处理一次 A2A 调用。
     *
     * @param messages 对话消息。取最后一条的文本作为问题
     * @param options  task id / session id / **userId**。
     *                 注意：{@code userId} 是「代表谁」的官方位置，
     *                 不是 agent 自己的身份
     */
    @Override
    public Flux<AgentEvent> streamEvents(List<Msg> messages, AgentRequestOptions options) {
        String question = lastText(messages);
        log.info("A2A 调用：agent={} taskId={} userId={} q={}",
                getAgentName(), options.getTaskId(), options.getUserId(), question);

        // 第 2 步：先返回固定答复，验证协议链路。
        // 第 4 步会换成「解析 userId → 换令牌 → 调 ask → 组装 artifact」
        // 构造器签名：(sessionId, replyId, name)。
        // replyId 由 starter 用来把后续事件归到同一条回复上，这里给 null 表示由它生成
        AgentEvent start = new AgentStartEvent(
                options.getSessionId(), null, getAgentName());

        Msg reply = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .name(getAgentName())
                .content(List.<ContentBlock>of(TextBlock.builder()
                        .text("【占位】A2A 链路已通。问数接入在第 4 步完成。\n"
                                + "收到的问题：" + question + "\n"
                                + "被代理用户：" + options.getUserId())
                        .build()))
                .build();

        return Flux.just(start, new AgentResultEvent(reply));
    }

    /**
     * 停止一个 task。
     *
     * <p>问数是「一次查询、有明确终点」的，没有长驻任务需要中断。
     * 这里只记日志——将来若接了长时间的分析编排，再实现真正的取消。
     */
    @Override
    public void stop(String taskId) {
        log.info("收到 stop 请求（当前无长驻任务）：taskId={}", taskId);
    }

    /** 取最后一条消息的纯文本。 */
    private static String lastText(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        Msg last = messages.get(messages.size() - 1);
        if (last.getContent() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : last.getContent()) {
            if (block instanceof TextBlock text && text.getText() != null) {
                sb.append(text.getText());
            }
        }
        return sb.toString().trim();
    }
}

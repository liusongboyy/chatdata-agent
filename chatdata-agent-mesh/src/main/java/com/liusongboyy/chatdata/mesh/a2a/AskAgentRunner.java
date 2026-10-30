package com.liusongboyy.chatdata.mesh.a2a;

import com.liusongboyy.chatdata.mesh.config.MeshProperties;
import com.liusongboyy.chatdata.mesh.identity.ProxyTargetResolver;
import com.liusongboyy.chatdata.mesh.identity.ProxyTokenService;
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
    private final ProxyTargetResolver resolver;
    private final ProxyTokenService tokens;

    public AskAgentRunner(MeshProperties properties,
                          ProxyTargetResolver resolver,
                          ProxyTokenService tokens) {
        this.properties = properties;
        this.resolver = resolver;
        this.tokens = tokens;
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

        // 第 3 步：接上身份解析与换令牌，验证「代表谁」这条链路。
        // 第 4 步再接 ask 的真实查询。
        //
        // 失败时返回一条明确的错误说明，而不是 Flux.error——A2A 的 error 事件
        // 在客户端往往只显示成"任务失败"，调用方看不到原因。
        // 授权问题是**调用方能处理的**（去走授权流程），必须让它看见
        String clientId = "";   // 第 4 步会从请求头取，本期先留空走白名单留空的路径
        var target = resolver.resolve(options.getUserId(), clientId);
        if (target.isEmpty()) {
            return errorReply("无法确定代表谁：" + options.getUserId()
                    + "。userId 必须是 user:<username> 形式，且客户端在白名单内。");
        }
        String username = target.get();

        String token;
        try {
            token = tokens.tokenFor(clientId.isEmpty() ? "report-bot" : clientId, username);
        } catch (ProxyTokenService.TokenUnavailableException e) {
            return errorReply(e.getMessage());
        }
        log.info("A2A 身份解析成功：user={} tokenLen={}", username, token.length());
        // 构造器签名：(sessionId, replyId, name)。
        // replyId 由 starter 用来把后续事件归到同一条回复上，这里给 null 表示由它生成
        AgentEvent start = new AgentStartEvent(
                options.getSessionId(), null, getAgentName());

        Msg reply = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .name(getAgentName())
                .content(List.<ContentBlock>of(TextBlock.builder()
                        .text("【第 3 步】身份链路已通。问数接入在第 4 步完成。\n"
                                + "收到的问题：" + question + "\n"
                                + "被代理用户：" + username + "\n"
                                + "已换到令牌（" + token.length() + " 字符）")
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

    /** 失败时也返回一条可读的说明，而不是 Flux.error。 */
    private Flux<AgentEvent> errorReply(String message) {
        Msg reply = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .name(getAgentName())
                .content(List.<ContentBlock>of(TextBlock.builder().text(message).build()))
                .build();
        return Flux.just(new AgentStartEvent(null, null, getAgentName()),
                new AgentResultEvent(reply));
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

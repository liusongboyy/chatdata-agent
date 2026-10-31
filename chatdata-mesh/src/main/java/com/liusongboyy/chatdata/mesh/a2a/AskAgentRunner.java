package com.liusongboyy.chatdata.mesh.a2a;

import com.liusongboyy.chatdata.mesh.config.MeshProperties;
import com.liusongboyy.chatdata.mesh.ask.AskClient;
import com.liusongboyy.chatdata.mesh.ask.AskResult;
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
import java.util.Map;

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
    private final AskClient ask;
    private final ArtifactBuilder artifacts;

    public AskAgentRunner(MeshProperties properties,
                          ProxyTargetResolver resolver,
                          ProxyTokenService tokens,
                          AskClient ask,
                          ArtifactBuilder artifacts) {
        this.properties = properties;
        this.resolver = resolver;
        this.tokens = tokens;
        this.ask = ask;
        this.artifacts = artifacts;
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
        // 调用方标识。
        //
        // 原计划从 HTTP 头 X-Agent-Client 取，但实测拿不到：
        // A2A starter 在**异步线程池**（pool-N-thread-M）上调用 streamEvents，
        // 请求线程的 ThreadLocal 传不过去；而 starter 也不把请求头放进 metadata。
        //
        // 所以改用与 userId 相同的通道：message metadata。
        // 语义上更清楚——调用方在一个请求里说清「我是谁、代表谁」，
        // 而不是一半在头、一半在体里。
        //
        // 这仍是软校验：metadata 由调用方填。真正的边界是 IAM 的授权表。
        // 客户端标识从 userId 的组合写法里取（user:<client>:<user>）。
        // 单独用 metadata 键传不进来——starter 只保留 userId 一个键。
        String clientId = "";
        var target = resolver.resolve(options.getUserId(), clientId);
        if (target.isEmpty()) {
            return errorReply("无法确定代表谁：" + options.getUserId()
                    + "。userId 必须是 user:<username> 形式，且客户端在白名单内。");
        }
        String username = target.get().username();
        // 客户端标识要用 resolver 解析出来的那个——组合写法里它在 userId 内，
        // 不是外面那个空串。传空会让 IAM 的 agent_client 为空而拒绝
        String agentClient = target.get().agentClient();

        String token;
        try {
            token = tokens.tokenFor(agentClient, username);
        } catch (ProxyTokenService.TokenUnavailableException e) {
            // 授权问题调用方是能处理的（去走授权流程），必须让它看见原因
            return errorReply(e.getMessage());
        }

        // 第 4 步：真实查询。
        // analyze 由问题前缀决定（与 channel 的 #分析 一致），
        // 但 A2A 调用方更可能是程序，不一定会打前缀——所以暂不做前缀解析，
        // 一律走单查询。分析编排留到有调用方真的需要时再加。
        AskResult result;
        try {
            result = ask.ask(token, question, options.getSessionId(), false);
        } catch (AskClient.AskFailedException e) {
            return errorReply(e.getMessage());
        }

        log.info("A2A 问数完成：user={} rows={} durationMs={}",
                username, result.rowCount(), result.durationMs());

        AgentEvent startEvent = new AgentStartEvent(
                options.getSessionId(), null, getAgentName());

        // 文本摘要 + 结构化 JSON 一起给。
        //
        // 为什么不用 DataBlock：它的模型是 (Source, id, name)——Source 是
        // "数据从哪来"的引用，不是"数据本身"。把表格塞进去要额外构造 Source，
        // 而那套语义是给 RAG/文件用的，不是给表格用的。
        //
        // 退一步：把 JSON 作为第二个 TextBlock。能解析的调用方自己 JSON.parse，
        // 只读文本的只取第一段。少一个抽象，少一处对不齐。
        String summary = artifacts.textSummary(question, result);
        String dataJson = artifacts.dataJson(question, result);

        Msg reply = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .name(getAgentName())
                .content(List.<ContentBlock>of(
                        TextBlock.builder().text(summary).build(),
                        TextBlock.builder().text(dataJson).build()))
                .build();

        return Flux.just(startEvent, new AgentResultEvent(reply));
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

    /**
     * 从最后一条消息的 metadata 里取一个字符串值。
     *
     * <p>取不到返回空串，由调用方决定这算不算错——对 {@code agentClient}
     * 来说，白名单留空时它可以是空的。
     */
    private static String messageMetadata(List<Msg> messages, String key) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        Msg last = messages.get(messages.size() - 1);
        Map<String, Object> md = last.getMetadata();
        if (md == null) {
            return "";
        }
        Object v = md.get(key);
        return v == null ? "" : String.valueOf(v).trim();
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

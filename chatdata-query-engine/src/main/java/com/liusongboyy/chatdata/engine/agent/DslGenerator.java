package com.liusongboyy.chatdata.engine.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.liusongboyy.chatdata.engine.nlsql.AskDsl;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ConflictPolicy;

import java.util.List;

/**
 * DSL 生成：把用户问题翻译成 {@link AskDsl}。
 *
 * <h2>用 JsonNode 手写 schema，不用 {@code call(..., AskDsl.class, ...)}</h2>
 *
 * <p>agentscope 支持把 Java 类型交给它自动生成 schema 并自动反序列化。
 * **那会让 filters 字段被系统性丢弃**——P0.5 spike 实测 10/10 全败，
 * 而换手写 schema 后 13/13 全对。两者走同一条代码路径、同模型、同温度，
 * 唯一差别是 schema 内容。
 *
 * <p>详见 {@link com.liusongboyy.chatdata.engine.nlsql.AskDslSchema} 的说明与
 * {@code .scratch/self-built-chatbi/spike-p0-results.md}。
 *
 * <h2>产出后必须做语义校验</h2>
 *
 * <p>模型可能把整段文本塞进一个字段，或（更糟）**丢掉问题里的限定词**——
 * 后者不报错，只是查出来的数字不对。故 {@link AskDsl} 的字段会在解析时做完整性检查，
 * 由上层决定是否重问。
 */
public final class DslGenerator implements DslSource {

    private final Model model;
    /** 手写 schema，每次调用返回新实例。 */
    private final JsonNode schema;
    /** 会话状态存储。为 null 表示不做多轮——每次提问都是独立会话。 */
    private final AgentStateStore stateStore;
    private final ConflictPolicy conflictPolicy;

    /** 单轮模式：不保留会话上下文。多轮追问在 {@link AskSessionProperties} 里配。 */
    public DslGenerator(Model model) {
        this(model, null, null);
    }

    /**
     * @param stateStore     会话状态存储，可为 null（单轮模式）
     * @param conflictPolicy 同会话并发写的冲突策略，null 时用 agentscope 默认值
     */
    public DslGenerator(Model model, AgentStateStore stateStore, ConflictPolicy conflictPolicy) {
        this.model = model;
        this.schema = com.liusongboyy.chatdata.engine.nlsql.AskDslSchema.jsonSchema();
        this.stateStore = stateStore;
        this.conflictPolicy = conflictPolicy;
    }

    /**
     * 生成 DSL。
     *
     * <p><strong>每次新建 agent，这是刻意的，而且挂了状态存储之后更是必须的。</strong>
     * 会话状态不在 agent 实例上，而在 {@link #stateStore} 里，按
     * {@code (userId, sessionId)} 存取。agent 因此退回成一个纯函数式的执行器：
     * 拿到 key → 读状态 → 跑一轮 → 写回。这样"同一个用户的两次提问"与
     * "同一时刻两个用户的提问"走的是同一条路径，不需要为并发隔离额外论证什么。
     *
     * <p>spike E6 曾实测单例复用也不串台，但复用只会把状态悄悄留在实例上，
     * 反而让上面这条性质不再成立——**能复用不等于该复用**。
     *
     * @param question   用户问题
     * @param sysPrompt  由 {@code PromptAssembler} 按语义域装配
     * @param userId     用户标识，用于会话隔离与审计
     * @param sessionId  会话标识。多轮追问靠它保持上下文
     */
    @Override
    public AskDsl generate(String question, String sysPrompt, String userId, String sessionId) {
        ReActAgent.Builder builder = ReActAgent.builder()
                .name("nlsql")
                .sysPrompt(sysPrompt)
                .model(model);

        if (stateStore != null) {
            // 挂上存储之后，agent 在每次调用**结束时**把整段会话写回，
            // 下次调用再按 (userId, sessionId) 读回来——多轮追问就是这么成立的。
            // 注意 sysPrompt 不在会话里：它每次由 PromptAssembler 按当前语义域重新装配，
            // 所以域改了之后，历史问答仍在、而域描述已经是最新的。
            builder.stateStore(stateStore);
            if (conflictPolicy != null) {
                builder.conflictPolicy(conflictPolicy);
            }
        }

        ReActAgent agent = builder.build();

        RuntimeContext ctx = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .build();

        Msg reply = agent.call(List.of(new UserMessage(question)), schema, ctx).block();

        if (reply == null) {
            throw new DslGenerationException("模型没有返回内容");
        }

        // 走 JsonNode 路径时，结构化结果在文本内容里（JSON 字符串），
        // 不是 Java 对象——反序列化由我们自己做，这样也顺带统一了错误信息
        String json = reply.getTextContent();
        if (json == null || json.isBlank()) {
            throw new DslGenerationException("模型返回的结构化内容为空");
        }

        return DslParser.parse(json);
    }
}

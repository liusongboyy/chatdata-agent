package com.liusongboyy.chatdata.engine.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 从 agentscope 的回复消息里取出 token 消耗。
 *
 * <h2>为什么用 {@code getChatUsage()} 而不用 {@code getUsage()}</h2>
 *
 * <p>两个方法都在 {@code Msg} 上，但语义不同：
 *
 * <ul>
 *   <li>{@code getUsage()} —— 只读 {@code msg.usage} 字段。结构化输出走
 *       「合成 generate_response 工具」那条路径时，框架会把多轮调用的
 *       用量聚合后塞进 <b>metadata</b>，而 {@code usage} 字段上只保留最后一轮。</li>
 *   <li>{@code getChatUsage()} —— 先看 {@code usage}，为空再看 metadata 里的
 *       {@code _chat_usage}。**两条路都覆盖**。</li>
 * </ul>
 *
 * <p>问数正是走结构化输出，所以必须用后者。用前者会漏掉前几轮的消耗——
 * 而漏掉的部分恰恰是"重问一次"这种最贵的场景。
 *
 * <h2>拿不到时不编造</h2>
 *
 * <p>返回 {@link TokenUsage#UNKNOWN}，一路传到数据库里变成 NULL。
 * 写成 0 会让成本看板把这批请求算成"免费"，而那正是最需要看见的一批。
 */
public final class UsageCollector {

    private UsageCollector() {
    }

    /**
     * 从回复里取消耗。
     *
     * @param reply 模型回复；可为 null
     * @return 消耗；拿不到时为 {@link TokenUsage#UNKNOWN}
     */
    public static TokenUsage of(Msg reply) {
        if (reply == null) {
            return TokenUsage.UNKNOWN;
        }
        ChatUsage u;
        try {
            u = reply.getChatUsage();
        } catch (RuntimeException e) {
            // 框架内部解析 metadata 时可能抛（脏数据、版本差异）。
            // 采集失败绝不能让问数失败——它只是个统计
            return TokenUsage.UNKNOWN;
        }
        if (u == null) {
            return TokenUsage.UNKNOWN;
        }
        return of(u);
    }

    /** {@code ChatUsage} → {@link TokenUsage}。 */
    public static TokenUsage of(ChatUsage u) {
        if (u == null) {
            return TokenUsage.UNKNOWN;
        }
        return new TokenUsage(
                u.getInputTokens(),
                u.getOutputTokens(),
                u.getCachedTokens(),
                u.getCacheCreationTokens(),
                u.getReasoningTokens());
    }

    /**
     * 跑一轮 agent，并把**整轮**的 token 累加起来。
     *
     * <h2>为什么不能直接用 {@code agent.call(...).block()} 的返回消息</h2>
     *
     * <p>直连模式是 ReAct 循环：看结构 → 查数 → 再看结果 → 作答，<b>每轮都是一次
     * 完整计费的模型调用</b>。而返回消息上的 usage 只反映**最后一轮**——
     * 中间那些查数的调用全部漏掉，而它们恰恰是 token 花得最多的部分。
     *
     * <p>框架的 {@code streamEvents} 会在每次模型调用结束时发一个
     * {@link ModelCallEndEvent}，把它们的 usage 加起来才是真实消耗。
     *
     * <p><b>与 {@code call()} 走同一套生命周期</b>（同一个 {@code buildAgentStream}），
     * 所以换用事件流不改变行为，只是多拿到一个副产物。
     *
     * @return 最终回复 + 整轮累计消耗。回复为 null 表示 agent 没产出结果
     */
    public static Generated<Msg> run(ReActAgent agent, List<Msg> msgs, RuntimeContext ctx) {
        AtomicReference<Msg> result = new AtomicReference<>();
        @SuppressWarnings("unchecked")
        AtomicReference<TokenUsage> total = new AtomicReference<>(TokenUsage.UNKNOWN);

        agent.streamEvents(msgs, ctx)
                .doOnNext((AgentEvent e) -> {
                    if (e instanceof ModelCallEndEvent end) {
                        // accumulate() 里做了 UNKNOWN 的合并，不会把"没采到"当成 0
                        total.updateAndGet((prev) -> prev.plus(of(end.getUsage())));
                    } else if (e instanceof AgentResultEvent done) {
                        result.set(done.getResult());
                    }
                })
                .blockLast();

        return new Generated<>(result.get(), total.get());
    }
}

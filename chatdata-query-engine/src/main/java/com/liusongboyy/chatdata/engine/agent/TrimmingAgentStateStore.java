package com.liusongboyy.chatdata.engine.agent;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 给任意 {@link AgentStateStore} 加一层"只保留最近 N 条消息"的裁剪。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>{@link AgentState} 的 {@code context} 是**只增不减**的：所有轮次的问答都堆在里面。
 * 而它是每一轮都要发给模型的——会话越长，单轮成本单调上升，且终有一轮会超出模型窗口。
 * 那一刻失败的是问数本身，但原因与用户这次问的问题毫无关系，排查起来非常绕。
 *
 * <p>上限本身由 {@link AskSessionProperties#getMaxMessages()} 配置，见那里的说明。
 *
 * <h2>裁剪点必须落在用户消息上</h2>
 *
 * <p>简单地从尾部取 N 条会从**某个助手答案的中间**切开，于是模型看到的首条消息
 * 是一条没有对应提问的答案——它会把它当成本次问题的上下文。这类错误不报错，
 * 只是答案莫名其妙，与 PRD §8 说的「静默语义错误」同类。
 * 故裁剪后向前收窄到最近的一条用户消息。
 *
 * <h2>为什么可以就地改</h2>
 *
 * <p>agentscope 只在**一次调用彻底结束**（或失败）之后才落盘
 * （{@code ReActAgent.saveStateToSession} 挂在 {@code doCallInner} 的
 * {@code flatMap}/{@code onErrorResume} 上，不在迭代中间），
 * 且本引擎每问一次新建 agent（见 {@link DslGenerator}），
 * 所以拿到手的状态对象此后不会再被读。就地裁剪因此是安全的。
 *
 * <p>反过来说，**不做拷贝是刻意的**：拷贝要逐字段列一遍 {@link AgentState} 的字段，
 * 将来 agentscope 加一个字段，拷贝会静默丢掉它——那比多删几条消息严重得多。
 *
 * <h2>读路径不裁剪</h2>
 *
 * <p>写的时候已经裁剪过，读到的必然已在上限内，再裁一遍只是白做功。
 * 万一有人直接往表里塞了超长状态，读出来仍是完整的历史——这是可接受的降级。
 */
public final class TrimmingAgentStateStore implements AgentStateStore {

    private final AgentStateStore delegate;
    /** 保留的消息条数上限；&lt;= 0 表示不限。 */
    private final int maxMessages;

    public TrimmingAgentStateStore(AgentStateStore delegate, int maxMessages) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate 不能为空");
        }
        this.delegate = delegate;
        this.maxMessages = maxMessages;
    }

    @Override
    public boolean supportsVersioning() {
        return delegate.supportsVersioning();
    }

    @Override
    public void save(String userId, String sessionId, String key, State state) {
        delegate.save(userId, sessionId, key, trim(state));
    }

    @Override
    public <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        return delegate.getVersioned(userId, sessionId, key, type);
    }

    @Override
    public long saveIfVersion(
            String userId, String sessionId, String key, State state, long expectedVersion) {
        return delegate.saveIfVersion(userId, sessionId, key, trim(state), expectedVersion);
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> states) {
        // 列表状态（对话缓冲的另一条存储路径）与单状态走同一套裁剪规则，
        // 否则两条路径的上限会不一致——那正是"某天突然变慢"的来路
        List<? extends State> trimmed = states == null
                ? null
                : states.stream().map(this::trim).toList();
        delegate.save(userId, sessionId, key, trimmed);
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        return delegate.get(userId, sessionId, key, type);
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> type) {
        return delegate.getList(userId, sessionId, key, type);
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return delegate.exists(userId, sessionId);
    }

    @Override
    public void delete(String userId, String sessionId) {
        delegate.delete(userId, sessionId);
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        // 接口上的默认实现是空操作，若不转发，"删掉一个会话"会静默失效
        delegate.delete(userId, sessionId, key);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return delegate.listSessionIds(userId);
    }

    @Override
    public void close() {
        delegate.close();
    }

    /**
     * 裁掉最旧的消息，使上下文不超过上限。
     *
     * @return 原对象（已就地裁剪），或非 {@link AgentState} 的原样返回
     */
    private State trim(State state) {
        if (maxMessages <= 0 || !(state instanceof AgentState agentState)) {
            return state;
        }

        List<Msg> context = agentState.contextMutable();
        if (context.size() <= maxMessages) {
            return state;
        }

        int from = context.size() - maxMessages;

        // 起点收窄到最近的一条用户消息：见类注释里"裁剪点必须落在用户消息上"
        while (from < context.size() && context.get(from).getRole() != MsgRole.USER) {
            from++;
        }

        // 整段尾部都没有用户消息（理论上不该发生）——不裁，宁可超限也不给出无头上下文
        if (from >= context.size()) {
            return state;
        }

        context.subList(0, from).clear();
        return state;
    }
}

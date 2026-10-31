package com.liusongboyy.chatdata.mesh.aistio;

import io.agentscope.extensions.aistio.FrameworkAdapter;
import io.agentscope.extensions.aistio.model.SessionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * 把问数 mesh 接入 aistio 控制面。
 *
 * <h2>它解决什么</h2>
 *
 * <p>{@code agentscope-extensions-aistio} 自带的 {@code AgentScopeAdapter} 要求
 * {@code target instanceof Agent}（真的 {@code ReActAgent} 实例）。而 mesh 是
 * 一个**转发器**：收 A2A 请求 → 换令牌 → 调 ask → 返回结果。没有 agent 循环、
 * 没有模型、没有工具。
 *
 * <p>所以不硬造一个假的 ReActAgent（那会让 mesh 多一层无意义的模型调用），
 * 而是自己实现 {@link FrameworkAdapter}——它只有 4 个方法必须实现。
 *
 * <h2>本期实现范围</h2>
 *
 * <p>只声明<b>能处理对话</b>（{@code CAP_CONVERSATION_INBOUND}）：管理台发一句话，
 * 这里转给 mesh 的问数链路，把答案返回去。上下文快照、消息历史、子 agent 清单
 * 这些能力先不声明——不声明就等于"我做不到"，比声明了却返回空更诚实。
 *
 * <p>上下文/历史要等有了真正的会话持久化再做。现在把 {@code emit} 留着，
 * 将来接上事件流不需要改这个类的外部契约。
 */
public class MeshFrameworkAdapter implements FrameworkAdapter {

    private static final Logger log = LoggerFactory.getLogger(MeshFrameworkAdapter.class);

    /** 一次对话轮次的执行体。由 mesh 侧注入，避免这个类依赖 AskAgentRunner。 */
    @FunctionalInterface
    public interface ConversationTurn {
        /**
         * @param sessionId 管理台给的会话 id
         * @param content   用户说的话
         * @return 答案
         */
        String run(String sessionId, String content);
    }

    private final ConversationTurn turn;
    private volatile Consumer<SessionEvent> emitter;

    public MeshFrameworkAdapter(ConversationTurn turn) {
        this.turn = turn;
    }

    @Override
    public String frameworkName() {
        // 这个名字会出现在管理台上，标清"我不是 agentscope 原生 agent"
        return "chatdata-mesh";
    }

    @Override
    public boolean canHandle(Object target) {
        // 目标就是 mesh 自己（传进来的是 SessionBridge 的 instrument 入口对象）。
        // 用类型判断而不是恒 true：将来若在同一进程里挂多个 adapter，
        // 恒 true 会让它们互相抢。
        return target instanceof MeshInstrumented;
    }

    @Override
    public void attach(Object target, Consumer<SessionEvent> emit) {
        // emit 是控制面要求的事件通道。本期不发事件（没有 agent 循环可观测），
        // 但必须存下来——将来接事件流时不需要改 attach 的签名
        this.emitter = emit;
        log.info("aistio 适配器已挂载：framework={} target={}",
                frameworkName(), target.getClass().getSimpleName());
    }

    @Override
    public void detach() {
        this.emitter = null;
        log.info("aistio 适配器已卸载");
    }

    /**
     * 管理台发起的一轮对话。
     *
     * <p>这是「被 agentscope service 编排」的落点：控制面调这个方法，
     * 我们调问数，答案原样返回。
     *
     * <p><b>必须返回答案本身，不是 ack。</b>接口注释里写得很明确：
     * {@code completion must carry the answer, not just acknowledge input}。
     */
    @Override
    public Mono<String> runConversationTurn(String sessionId, String content) {
        return Mono.fromCallable(() -> turn.run(sessionId, content))
                // 问数要几秒到十几秒，不能占控制面的默认超时线程
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    // 失败也要给控制面一个可读的结果，而不是让它看到空错误
                    log.warn("aistio 对话轮次失败：session={} {}", sessionId, e.getMessage());
                    return Mono.just("问数失败：" + e.getMessage());
                });
    }

    /**
     * 声明能力。
     *
     * <p><b>只声明真做到的。</b>声明了却返回空，管理台上会显示成"支持但查不到"，
     * 那种误导比"不支持"难查得多。
     */
    @Override
    public Set<String> capabilities() {
        Set<String> caps = new TreeSet<>();
        caps.add(CAP_CONVERSATION_INBOUND);
        return caps;
    }

    /**
     * 取会话上下文快照。
     *
     * <p><b>本期不实现，返回空 Mono。</b>
     *
     * <p>aistio 用它让管理台展示"这个 session 现在带着什么上下文"。
     * 而 mesh 没有 agent 循环——它每次都是「拿问题去问一次」，不存在
     * 累积的上下文对象。强行拼一个假的快照会让管理台展示不存在的东西，
     * 比不展示更糟。
     *
     * <p>返回空而不是报错：控制面看到空快照会显示"无上下文"，
     * 那是准确的描述。
     */
    @Override
    public Mono<io.agentscope.extensions.aistio.model.ContextSnapshot> extractContext(
            String sessionId) {
        return Mono.empty();
    }

    /** 让 {@link #canHandle} 能识别被测对象。实现方（mesh）实现这个空接口即可。 */
    public interface MeshInstrumented {
    }
}

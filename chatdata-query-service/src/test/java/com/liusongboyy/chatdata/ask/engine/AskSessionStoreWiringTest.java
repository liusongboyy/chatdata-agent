package com.liusongboyy.chatdata.ask.engine;

import com.liusongboyy.chatdata.engine.agent.AskSessionProperties;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话状态存储的**装配验证**：在真实 Spring 上下文里把 bean 拉起来跑一次读写。
 *
 * <h2>为什么单测覆盖不到这件事</h2>
 *
 * <p>{@code AskSessionStore} 与 {@code DslSource} 都是 {@code @Lazy} 的
 * （理由见 {@code AskEngineConfiguration}：建存储会连库、必要时建表，那是启动期副作用）。
 * 惰性的代价是——**应用上下文起得来，不代表这两个 bean 装得起来**。
 * 参数注入错了、{@code DataSource} 不在上下文里、库名解析不出来，
 * 这些都要到第一次真去问数时才暴露，而那时用户看到的是"查询失败"。
 *
 * <p>故这里显式把它们从上下文里取出来用一次。这是本文件存在的全部理由，
 * 它不重复验证裁剪规则或 SQL 语义——那些在引擎模块各自的单测里。
 *
 * <p>{@code DslSource} 取不了：它依赖模型，而模型需要 api-key。
 * 所以这里验到 {@code AgentStateStore} 为止——它是 {@code DslSource} 唯一的
 * 新增依赖，也是唯一会连库的那个。
 */
@DatabaseAvailable
@SpringBootTest(properties = {
        "chatdata.auth.secret=session-wiring-test-secret",
        "chatdata.engine.datasource.secret=session-wiring-test-engine-secret",
        // 这里不连 IAM，只让上下文装配得起来；给一个占位密钥，
        // 避免任何客户端在构造期因为"缺配置"而失败
        "chatdata.iam.client-secret=session-wiring-test-iam-secret",
        // **关掉启动校验**。本用例验的是"会话存储接线对不对"，与语义配置是否合规无关；
        // 而启动校验默认 fail-on-violation，库里只要有人建了个没配完的域，
        // 这个测试就会以"上下文加载失败"的形式红掉，报错还指不到真正的原因。
        // 配置校验本身有 EngineStartupCheckRealDbTest 覆盖。
        "chatdata.engine.startup.enabled=false"
})
class AskSessionStoreWiringTest {

    @Autowired
    private AgentStateStore store;

    @Autowired
    private AskSessionProperties props;

    /** 造一段"问、答、问、答…"的上下文——形态与真实会话一致，裁剪点才有意义。 */
    private static AgentState stateWith(int messages) {
        List<Msg> context = new ArrayList<>();
        for (int i = 0; i < messages; i++) {
            context.add(i % 2 == 0 ? new UserMessage("问题" + i) : new AssistantMessage("答案" + i));
        }
        return AgentState.builder().context(context).build();
    }

    @Test
    @DisplayName("会话存储 bean 装得起来，且能真读写业务库")
    void storeIsWiredAndUsable() {
        String user = "wiring-" + UUID.randomUUID();
        String session = "s-" + UUID.randomUUID();

        AgentState saved = AgentState.builder()
                .sessionId(session)
                .userId(user)
                .context(List.of(new UserMessage("上个月华东的保费是多少")))
                .build();

        store.save(user, session, "agent_state", saved);

        assertThat(store.get(user, session, "agent_state", AgentState.class))
                .isPresent()
                .get()
                .satisfies(loaded -> assertThat(loaded.getContext()).hasSize(1));

        // 换个会话读不到——隔离由 (userId, sessionId) 保证
        assertThat(store.get(user, "s-" + UUID.randomUUID(), "agent_state", AgentState.class))
                .isEmpty();
    }

    @Test
    @DisplayName("裁剪装饰器确实在链路上：超限的上下文落盘时已被裁到配置的上限内")
    void trimmingIsPartOfTheChain() {
        Assumptions.assumeTrue(props.getMaxMessages() > 0,
                "配置把上下文上限关了（max-messages<=0），本用例无意义");

        String user = "wiring-" + UUID.randomUUID();
        String session = "s-" + UUID.randomUUID();

        int over = props.getMaxMessages() * 3;
        store.save(user, session, "agent_state", stateWith(over));

        // 只测"没超过上限"是不够的：装饰器整个漏装时也会通过。
        // 所以要喂一个**必然超限**的上下文，看落盘的是不是被裁过的。
        assertThat(store.get(user, session, "agent_state", AgentState.class))
                .isPresent()
                .get()
                .satisfies(loaded -> assertThat(loaded.getContext().size())
                        .isLessThanOrEqualTo(props.getMaxMessages()));
    }
}

package com.liusongboyy.chatdata.engine.agent;

import com.liusongboyy.chatdata.engine.nlsql.AskDsl;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Flux;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多轮追问的**端到端验证**：真库 + 假模型。
 *
 * <h2>为什么要用假模型</h2>
 *
 * <p>要验的是"第 2 轮模型**看得见**第 1 轮"，而不是"模型答得对不对"。
 * 后者要联网、要花钱、还不稳定；前者只要把模型收到的消息记下来就能确定性判定。
 * 真模型在这里反而是噪声——它答错了也说明不了上下文有没有传过去。
 *
 * <h2>这条测试挡的是什么</h2>
 *
 * <p>{@code ReActAgent} 默认**不设 stateStore**，此时每次调用都是全新会话，
 * 多轮追问静默失效：第 2 轮问「那华北呢」，模型只能把它当成一个缺主语的新问题。
 * 这种失效不报错、不抛异常，只是答案不对——与 PRD §12.4 说的「静默语义错误」同类。
 *
 * <p>每个用例都用随机 userId/sessionId，故反复跑不会互相污染，也不会读到上一轮的数据。
 */
class DslGeneratorSessionRealDbTest {

    private static final String URL =
            "jdbc:mysql://127.0.0.1:3306/jimureport?useUnicode=true&characterEncoding=UTF-8"
                    + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    private static final String USER = "root";
    private static final String PASSWORD = "root";

    /** 假模型返回的 DSL。够 {@code DslParser} 解析出非空 DSL 即可。 */
    private static final String DSL_JSON =
            "{\"metrics\":[\"保费\"],\"dimensions\":[],\"timeRange\":null,\"filters\":[]}";

    private static final String Q1 = "上个月华东的有效保单保费合计是多少";
    private static final String Q2 = "那华北呢";

    /** 用产品默认值建表名，改默认值时这条测试跟着改——不让两处漂移。 */
    private static final AskSessionProperties PROPS = new AskSessionProperties();

    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void checkDb() {
        Assumptions.assumeTrue(reachable(), "本地 MySQL 不可用，跳过真库测试");
        dataSource = new DriverManagerDataSource(URL, USER, PASSWORD);
    }

    private static boolean reachable() {
        try (Connection ignored = DriverManager.getConnection(URL, USER, PASSWORD)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 记录每次调用收到的消息，并返回一段固定的 DSL。
     *
     * <p>{@code supportsNativeStructuredOutput=true} 与产品配置一致
     * （见 {@code AskEngineModelFactory}）——走 native 分支时模型把 JSON 当**文本**返回，
     * 正是这里伪造的形态。
     */
    private static final class RecordingModel implements Model {
        private final List<List<Msg>> calls = new ArrayList<>();

        @Override
        public Flux<ChatResponse> stream(List<Msg> msgs, List<ToolSchema> tools,
                                         GenerateOptions options) {
            calls.add(List.copyOf(msgs));
            return Flux.just(ChatResponse.builder()
                    .id("r" + calls.size())
                    .content(List.of(TextBlock.builder().text(DSL_JSON).build()))
                    .usage(new ChatUsage(10, 10, 0.0))
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "recording-fake";
        }

        @Override
        public boolean supportsNativeStructuredOutput() {
            return true;
        }

        @Override
        public boolean supportsNativeStructuredOutputWithTools() {
            return false;
        }

        int callCount() {
            return calls.size();
        }

        /** 第 n 次调用（从 1 开始）收到的全部消息文本，拼成一串便于 contains 断言。 */
        String transcript(int n) {
            return calls.get(n - 1).stream()
                    .map(Msg::getTextContent)
                    .filter(java.util.Objects::nonNull)
                    .reduce("", (a, b) -> a + "\n" + b);
        }
    }

    /** 库名走与生产同一条解析路径（读连接上的当前库），不写死。 */
    private static AgentStateStore store(int maxMessages) {
        String database = AskSessionStores.resolveDatabase(dataSource, null);
        AgentStateStore mysql = new MysqlAgentStateStore(
                dataSource, database, PROPS.getTable(), true);
        return maxMessages > 0 ? new TrimmingAgentStateStore(mysql, maxMessages) : mysql;
    }

    @Test
    @DisplayName("同一 (用户, 会话) 的第二个问题，模型看得见第一轮——多轮追问成立")
    void secondTurnSeesFirstTurn() {
        RecordingModel model = new RecordingModel();
        DslGenerator generator = new DslGenerator(model, store(40), null);

        String user = "u-" + UUID.randomUUID();
        String session = "s-" + UUID.randomUUID();

        AskDsl first = generator.generate(Q1, "sys", user, session);
        AskDsl second = generator.generate(Q2, "sys", user, session);

        assertThat(first.isEmpty()).isFalse();
        assertThat(second.isEmpty()).isFalse();
        assertThat(model.callCount()).isEqualTo(2);

        // 第 1 轮是这段会话的开头：有它自己的问题，但没有更早的问答
        assertThat(model.transcript(1)).contains(Q1);
        assertThat(model.transcript(1)).doesNotContain(DSL_JSON);

        // 第 2 轮必须带上第 1 轮的问与答，否则「那华北呢」里的指代无从解析
        String secondTurn = model.transcript(2);
        assertThat(secondTurn).contains(Q1);
        assertThat(secondTurn).contains(DSL_JSON);
    }

    @Test
    @DisplayName("换一个 sessionId 就是另一段会话：上个会话的问题不会串进来")
    void differentSessionIsIsolated() {
        RecordingModel model = new RecordingModel();
        DslGenerator generator = new DslGenerator(model, store(40), null);

        String user = "u-" + UUID.randomUUID();
        String sessionA = "s-" + UUID.randomUUID();
        String sessionB = "s-" + UUID.randomUUID();

        generator.generate(Q1, "sys", user, sessionA);
        generator.generate(Q2, "sys", user, sessionB);
        // 正向对照：回到会话 A 时上下文**必须**还在。
        // 少了这一步，上面那条"没串进来"可能只是因为存储压根没生效——那是假绿。
        generator.generate(Q2, "sys", user, sessionA);

        assertThat(model.transcript(2)).doesNotContain(Q1);
        assertThat(model.transcript(3)).contains(Q1);
    }

    @Test
    @DisplayName("换个用户，即便会话 id 相同也读不到别人的上下文")
    void differentUserIsIsolated() {
        RecordingModel model = new RecordingModel();
        DslGenerator generator = new DslGenerator(model, store(40), null);

        String session = "s-" + UUID.randomUUID();
        String userA = "u-" + UUID.randomUUID();

        generator.generate(Q1, "sys", userA, session);
        generator.generate(Q2, "sys", "u-" + UUID.randomUUID(), session);
        generator.generate(Q2, "sys", userA, session);

        assertThat(model.transcript(2)).doesNotContain(Q1);
        assertThat(model.transcript(3)).contains(Q1);
    }

    @Test
    @DisplayName("不设存储（单轮模式）：第二轮看不到第一轮——这是关掉会话持久化后的应然行为")
    void withoutStoreEveryCallIsFresh() {
        RecordingModel model = new RecordingModel();
        DslGenerator generator = new DslGenerator(model);

        String user = "u-" + UUID.randomUUID();
        String session = "s-" + UUID.randomUUID();

        generator.generate(Q1, "sys", user, session);
        generator.generate(Q2, "sys", user, session);

        assertThat(model.transcript(2)).doesNotContain(Q1);
    }

    @Test
    @DisplayName("长会话：上下文被压在上限内，最近几轮还在、最早的已裁掉")
    void trimmingBoundsTheContextAcrossTurns() {
        RecordingModel model = new RecordingModel();
        // 上限 4 条 ≈ 2 轮。跑 5 轮后，第 5 轮只该看见最近两轮
        AgentStateStore store = store(4);
        DslGenerator generator = new DslGenerator(model, store, null);

        String user = "u-" + UUID.randomUUID();
        String session = "s-" + UUID.randomUUID();

        for (int i = 1; i <= 5; i++) {
            generator.generate("第" + i + "问", "sys", user, session);
        }

        String last = model.transcript(5);
        assertThat(last).contains("第4问");
        assertThat(last).doesNotContain("第1问");
    }
}

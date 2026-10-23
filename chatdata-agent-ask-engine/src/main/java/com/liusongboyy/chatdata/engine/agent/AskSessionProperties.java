package com.liusongboyy.chatdata.engine.agent;

import io.agentscope.core.state.ConflictPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多轮追问的会话状态配置。
 *
 * <h2>为什么状态必须落库</h2>
 *
 * <p>agentscope 的 {@code ReActAgent} 默认 **不设 {@code AgentStateStore}**，
 * 此时每次调用都是一次性会话——第 2 轮问「那华北呢」，模型看不到第 1 轮问了什么，
 * 只能把这句当成一个缺主语的新问题。多轮追问（FR-5.2）靠的就是这里配的存储。
 *
 * <p>按 {@code (userId, sessionId)} 存取，故同一用户的两个会话互不干扰——
 * 隔离由 agentscope 按 key 保证，我们不需要自己拼 key。
 */
@ConfigurationProperties(prefix = "chatdata.engine.session")
public class AskSessionProperties {

    /**
     * 是否启用会话持久化。
     *
     * <p>默认开。关掉它 = 每次提问都是独立会话，多轮追问失效——
     * 只在排查「是不是上下文串了」这类问题时才有理由关。
     */
    private boolean enabled = true;

    /**
     * 状态表所在的库。**留空表示"用数据源当前连的库"**（见 {@link AskSessionStores#resolveDatabase}）。
     *
     * <p>刻意不写死库名：库名已经在 JDBC URL 的 {@code MYSQL_DB} 里表达过一次，
     * 在配置文件里再写一遍，就是给"改了一处忘了另一处"留机会。
     */
    private String database;

    /**
     * 状态表名。
     *
     * <p>用 {@code ask_} 前缀，与厂商的 {@code chat2bi_*} 隔离——见 PRD §6。
     * 不用 agentscope 默认的 {@code agentscope_sessions}，是因为它默认还要建一个
     * **独立的 {@code agentscope} 库**（spike E7 实测），那在只给单库权限的生产环境里
     * 会直接起不来。
     */
    private String table = "ask_agent_session";

    /**
     * 表不存在时是否自动建。
     *
     * <p>默认开，方便开发环境直接跑。生产建议关掉、改用 {@code sql/12-ask-agent-session.sql}，
     * 因为自动建表需要 {@code CREATE} 权限，而多数生产账号没有——
     * 那时会在**第一次提问**时报错（store 是惰性 bean），而不是启动即失败。
     * 关掉之后表缺失会得到一句明确的"表不存在"，比权限错误好排查。
     */
    private boolean createTable = true;

    /**
     * 单会话保留的最近消息条数上限，超出即裁掉最旧的。
     *
     * <p><strong>必须有上限。</strong>会话不设界会一路长下去：既让每轮的 token 成本
     * 单调上升，又会在某轮突然超出模型窗口——那时候失败的是问数本身，
     * 而原因与用户的问题毫无关系。
     *
     * <p>默认 40 条：按一轮约 2 条（问 + 答）算，约 20 轮。够覆盖"再按区域拆一下"
     * 这类指代，又不至于把上下文撑爆。设为 0 或负数表示不限。
     */
    private int maxMessages = 40;

    /**
     * 同一会话并发写时的冲突策略。**默认 OVERWRITE。**
     *
     * <p>这是 agentscope 2.0.4 自身的默认值（见 {@code ReActAgent} 的构造），
     * 这里显式写出来是因为它是个**有后果**的选择，不该藏在默认值里：
     *
     * <ul>
     *   <li>{@code OVERWRITE} —— 后写覆盖先写，记一条 warn 日志。用户同时点两次提问时，
     *       其中一轮的上下文会丢，但**两轮都能正常出结果**。</li>
     *   <li>{@code FAIL} —— 抛 {@code ConcurrentSessionModificationException}，
     *       用户看到一次失败。语义干净，但对"手快点了两下"这种真实场景过于严厉。</li>
     *   <li>{@code APPEND_MERGE} —— 把两边新增的消息合并。两个**并行**的问题合并后
     *       会得到一个交替的上下文，第 3 轮读起来是乱的。**不推荐。**</li>
     * </ul>
     *
     * <p>问数是"一次提问一次回答"的交互，同一会话的并发写属于用户误操作而非正常负载，
     * 故取最不打扰用户的那个。
     */
    private ConflictPolicy conflictPolicy = ConflictPolicy.OVERWRITE;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public boolean isCreateTable() {
        return createTable;
    }

    public void setCreateTable(boolean createTable) {
        this.createTable = createTable;
    }

    public int getMaxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public ConflictPolicy getConflictPolicy() {
        return conflictPolicy;
    }

    public void setConflictPolicy(ConflictPolicy conflictPolicy) {
        this.conflictPolicy = conflictPolicy;
    }
}

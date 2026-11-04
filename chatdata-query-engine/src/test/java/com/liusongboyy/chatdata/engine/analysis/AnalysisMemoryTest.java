package com.liusongboyy.chatdata.engine.analysis;

import com.liusongboyy.chatdata.engine.chat.AnalysisService;
import com.liusongboyy.chatdata.engine.datasource.QueryResult;
import com.liusongboyy.chatdata.engine.datasource.SqlExecutor;
import com.liusongboyy.chatdata.engine.datasource.dialect.SqlDialects;
import com.liusongboyy.chatdata.engine.nlsql.AskDsl;
import com.liusongboyy.chatdata.engine.nlsql.RenderedSql;
import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;
import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多轮追问的分析记忆。
 *
 * <h2>它解决什么</h2>
 *
 * <p>问「各区域保费构成」→ 追问「那华南呢」。第二轮如果不知道上一轮查了什么，
 * 模型只能把这句当成缺主语的新问题。记忆把上轮的意图、步骤、发现带进 prompt。
 *
 * <h2>三条必须守住的边界</h2>
 *
 * <ul>
 *   <li><b>只存摘要</b>：行数据可能上千条，塞进状态会把上下文撑爆</li>
 *   <li><b>读写失败不能影响问数</b>：记忆是增强，不是主流程</li>
 *   <li><b>prompt 里要写明"这是上一轮"</b>：否则模型会把摘要当成本轮已知事实复述出来</li>
 * </ul>
 */
class AnalysisMemoryTest {

    /** 最简内存 store，够测存取语义。 */
    private static class MemoryStore implements AgentStateStore {
        private final Map<String, State> data = new HashMap<>();

        private String key(String u, String s, String k) {
            return u + "|" + s + "|" + k;
        }

        @Override
        public void save(String userId, String sessionId, String key, State value) {
            data.put(key(userId, sessionId, key), value);
        }

        @Override
        public long saveIfVersion(String userId, String sessionId, String key,
                                  State value, long expectedVersion) {
            save(userId, sessionId, key, value);
            return 1;
        }

        @Override
        public <T extends State> Optional<T> get(String userId, String sessionId,
                                                 String key, Class<T> type) {
            State v = data.get(key(userId, sessionId, key));
            return v == null ? Optional.empty() : Optional.of(type.cast(v));
        }

        @Override
        public <T extends State> List<T> getList(String userId, String sessionId,
                                                 String key, Class<T> type) {
            return List.of();
        }

        @Override
        public void save(String userId, String sessionId, String key,
                         List<? extends State> values) {
        }

        @Override
        public boolean exists(String userId, String sessionId) {
            return data.keySet().stream().anyMatch(k -> k.startsWith(userId + "|" + sessionId + "|"));
        }

        @Override
        public void delete(String userId, String sessionId) {
            data.keySet().removeIf(k -> k.startsWith(userId + "|" + sessionId + "|"));
        }

        @Override
        public java.util.Set<String> listSessionIds(String userId) {
            return data.keySet().stream()
                    .filter(k -> k.startsWith(userId + "|"))
                    .map(k -> k.split("\\|")[1])
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    private static AnalysisResult result() {
        AskDsl dsl = new AskDsl(List.of("保费"), List.of("区域"), null, List.of(),
                AskDsl.Granularity.DAY, List.of(), null, List.of(), null);
        StepResult step = new StepResult("s1", "各区域保费", "看构成", dsl, null,
                new QueryResult(List.of("区域", "保费"), List.of(
                        List.of("华东", new BigDecimal("600")),
                        List.of("华南", new BigDecimal("400")))),
                java.util.Set.of(), StepStatus.OK, null, 12, null);
        List<Finding> findings = new ArrayList<>();
        findings.add(new Finding(Finding.Kind.CONTRIBUTION, "s1",
                "「华东」占保费的 60.0%", Map.of("sharePct", new BigDecimal("60"))));
        return new AnalysisResult("各区域保费构成", List.of(step), findings, "华东占六成。", 1, false);
    }

    @Test
    @DisplayName("★ 只提炼摘要，不存原始行")
    void keepsSummaryOnly() {
        AnalysisMemory m = AnalysisMemory.of(result());

        assertThat(m.steps()).hasSize(1);
        assertThat(m.steps().get(0)).contains("各区域保费").contains("2 行");
        assertThat(m.findings()).hasSize(1);
        assertThat(m.narrative()).isEqualTo("华东占六成。");
        // 行数据不该出现在任何字段里
        assertThat(m.toString()).doesNotContain("600");
    }

    @Test
    @DisplayName("★ prompt 里明确标注「上一轮」，避免模型当成这一轮的事实")
    void promptSaysPreviousRound() {
        String block = AnalysisMemory.of(result()).toPromptBlock();

        assertThat(block).contains("上一轮").contains("不要直接复述");
        assertThat(block).contains("各区域保费构成");
        assertThat(block).contains("华东");
    }

    @Test
    @DisplayName("存取往返：按 (userId, sessionId) 隔离")
    void roundTripIsolatedBySession() {
        MemoryStore store = new MemoryStore();
        AnalysisMemory m = AnalysisMemory.of(result());

        m.save(store, "u1", "s1");

        assertThat(AnalysisMemory.load(store, "u1", "s1")).isNotNull();
        assertThat(AnalysisMemory.load(store, "u1", "s2")).as("别的会话读不到").isNull();
        assertThat(AnalysisMemory.load(store, "u2", "s1")).as("别的用户读不到").isNull();
    }

    @Test
    @DisplayName("★ 读失败返回 null，不抛——追问退化成普通提问，不影响正确性")
    void loadFailureDegrades() {
        AgentStateStore broken = new MemoryStore() {
            @Override
            public <T extends State> Optional<T> get(String userId, String sessionId,
                                                     String key, Class<T> type) {
                throw new IllegalStateException("库挂了");
            }
        };

        assertThat(AnalysisMemory.load(broken, "u1", "s1")).isNull();
    }

    @Test
    @DisplayName("★ 写失败只记日志，不抛")
    void saveFailureIsSwallowed() {
        AgentStateStore broken = new MemoryStore() {
            @Override
            public void save(String userId, String sessionId, String key, State value) {
                throw new IllegalStateException("库挂了");
            }
        };

        AnalysisMemory.of(result()).save(broken, "u1", "s1");   // 不抛即通过
    }

    @Test
    @DisplayName("没有 store 时两个方向都是空操作")
    void nullStoreIsNoop() {
        assertThat(AnalysisMemory.load(null, "u1", "s1")).isNull();
        AnalysisMemory.of(result()).save(null, "u1", "s1");
    }

    @Test
    @DisplayName("★ 第二轮的分析把上一轮摘要带进了规划 prompt")
    void secondRoundSeesPreviousSummary() {
        MemoryStore store = new MemoryStore();
        List<String> prompts = new ArrayList<>();

        AnalysisService service = new AnalysisService(model(), SqlDialects.of("MYSQL5.7"),
                executor(), null,
                (q, sysPrompt, u, s) -> {
                    prompts.add(sysPrompt);
                    return com.liusongboyy.chatdata.engine.agent.Generated.of(plan());
                },
                null, store);

        // 第一轮：没有记忆
        service.analyze(request(), e -> { });
        assertThat(prompts.get(0)).doesNotContain("上一轮分析");

        // 第二轮：同一会话，应看到上一轮摘要
        service.analyze(request(), e -> { });
        assertThat(prompts.get(1)).contains("上一轮分析").contains("各区域");
    }

    // ── 夹具 ──

    private static SemanticModel model() {
        RegisteredTable t = new RegisteredTable("t1", "f_order", "订单表",
                RegisteredTable.Mode.TABLE, null, true, List.of(
                new ColumnDef("region", "varchar", Aggregation.NONE, "区域", true),
                new ColumnDef("amount", "decimal", Aggregation.SUM, "订单金额", true)));
        Domain d = new Domain("d1", "订单域", "ds1", "MYSQL5.7", true, List.of(t),
                List.of(), perm(), List.of(), List.of());
        return new SemanticModel(d);
    }

    private static DomainPermission perm() {
        try {
            return DomainPermission.from(new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    "{\"v\":1,\"grantRoles\":[\"分析师\"],\"unrestrictedRoles\":[\"分析师\"]}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SqlExecutor executor() {
        return new SqlExecutor(null, 1000, 30) {
            @Override
            public QueryResult execute(String datasourceId, RenderedSql sql) {
                return new QueryResult(List.of("区域", "订单金额"), List.of(
                        List.of("华东", new BigDecimal("600")),
                        List.of("华南", new BigDecimal("400"))));
            }
        };
    }

    private static AnalysisPlan plan() {
        AskDsl dsl = new AskDsl(List.of("订单金额"), List.of("区域"), null, List.of(),
                AskDsl.Granularity.DAY, List.of(), null, List.of(), null);
        return new AnalysisPlan("各区域", List.of(new PlanStep("s1", "各区域", "", dsl)));
    }

    private static com.liusongboyy.chatdata.engine.chat.AskRequest request() {
        return new com.liusongboyy.chatdata.engine.chat.AskRequest(
                "各区域订单金额", "u1", "u1", java.util.Set.of("分析师"), "sess", null);
    }
}

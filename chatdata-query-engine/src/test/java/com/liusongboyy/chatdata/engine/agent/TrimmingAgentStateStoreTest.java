package com.liusongboyy.chatdata.engine.agent;

import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话上下文裁剪的测试。
 *
 * <p>这里要防的不是"裁剪没生效"，而是**裁错位置**——
 * 从一段助手答案中间切开，会让模型看到一条没有对应提问的答案，
 * 进而把它当成新问题的上下文。那类错误的症状是"答案莫名其妙"，不报错。
 */
class TrimmingAgentStateStoreTest {

    /** 记下最后一次落盘内容的假存储。 */
    private static final class RecordingStore implements AgentStateStore {
        State lastSaved;
        State lastSavedIfVersion;
        List<? extends State> lastListSaved;
        boolean deleteWithKeyCalled;

        @Override
        public void save(String userId, String sessionId, String key, State state) {
            this.lastSaved = state;
        }

        @Override
        public long saveIfVersion(String userId, String sessionId, String key, State state,
                                  long expectedVersion) {
            this.lastSavedIfVersion = state;
            return expectedVersion + 1;
        }

        @Override
        public void save(String userId, String sessionId, String key,
                         List<? extends State> states) {
            this.lastListSaved = states;
        }

        @Override
        public <T extends State> Optional<T> get(String userId, String sessionId, String key,
                                                 Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends State> List<T> getList(String userId, String sessionId, String key,
                                                 Class<T> type) {
            return List.of();
        }

        @Override
        public boolean exists(String userId, String sessionId) {
            return false;
        }

        @Override
        public void delete(String userId, String sessionId) {
            throw new UnsupportedOperationException("本测试不涉及");
        }

        @Override
        public void delete(String userId, String sessionId, String key) {
            this.deleteWithKeyCalled = true;
        }

        @Override
        public Set<String> listSessionIds(String userId) {
            return Set.of();
        }

        @Override
        public boolean supportsVersioning() {
            return true;
        }

        @Override
        public <T extends State> VersionedState<T> getVersioned(String userId, String sessionId,
                                                                String key, Class<T> type) {
            return new VersionedState<>(null, AgentStateStore.UNVERSIONED);
        }
    }

    /** 造一段 "问、答、问、答…" 的上下文。 */
    private static AgentState stateOf(int turns) {
        List<Msg> context = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            context.add(new UserMessage("问题" + i));
            context.add(new AssistantMessage("答案" + i));
        }
        return AgentState.builder()
                .sessionId("s1")
                .userId("u1")
                .context(context)
                .build();
    }

    private static List<String> texts(AgentState state) {
        return state.getContext().stream().map(Msg::getTextContent).toList();
    }

    @Test
    @DisplayName("没超上限：原样落盘，一条不裁")
    void keepsEverythingUnderLimit() {
        RecordingStore delegate = new RecordingStore();
        AgentState state = stateOf(3);          // 6 条

        new TrimmingAgentStateStore(delegate, 10).save("u1", "s1", "agent_state", state);

        assertThat(texts((AgentState) delegate.lastSaved))
                .containsExactly("问题0", "答案0", "问题1", "答案1", "问题2", "答案2");
    }

    @Test
    @DisplayName("超上限：只留最近 N 条，且起点落在一条用户消息上")
    void trimsAtUserBoundary() {
        RecordingStore delegate = new RecordingStore();
        AgentState state = stateOf(10);         // 20 条

        // 上限 5 → 天然起点在 index 15（答案7，助手消息），必须前移到 "问题8"
        new TrimmingAgentStateStore(delegate, 5).save("u1", "s1", "agent_state", state);

        List<String> kept = texts((AgentState) delegate.lastSaved);
        assertThat(kept).startsWith("问题8");
        assertThat(kept).hasSize(4);            // 问题8/答案8/问题9/答案9
        assertThat(kept.get(0)).startsWith("问题");
        // 不会剩下一段没有提问的答案
        assertThat(kept).doesNotContain("答案7");
    }

    @Test
    @DisplayName("上限为 0 或负数：视为不限，一条不裁")
    void zeroMeansUnlimited() {
        RecordingStore delegate = new RecordingStore();
        AgentState state = stateOf(50);         // 100 条

        new TrimmingAgentStateStore(delegate, 0).save("u1", "s1", "agent_state", state);

        assertThat(((AgentState) delegate.lastSaved).getContext()).hasSize(100);
    }

    @Test
    @DisplayName("CAS 写入路径同样裁剪——两条写路径的上限必须一致")
    void trimsOnVersionedSave() {
        RecordingStore delegate = new RecordingStore();
        AgentState state = stateOf(10);

        new TrimmingAgentStateStore(delegate, 4)
                .saveIfVersion("u1", "s1", "agent_state", state, 7L);

        assertThat(((AgentState) delegate.lastSavedIfVersion).getContext()).hasSize(4);
    }

    @Test
    @DisplayName("列表状态也裁剪")
    void trimsListStates() {
        RecordingStore delegate = new RecordingStore();

        new TrimmingAgentStateStore(delegate, 2)
                .save("u1", "s1", "agent_state", List.of(stateOf(4), stateOf(4)));

        assertThat(delegate.lastListSaved)
                .allSatisfy(s -> assertThat(((AgentState) s).getContext()).hasSize(2));
    }

    @Test
    @DisplayName("非 AgentState 的状态原样放行——裁剪只认会话上下文")
    void passesThroughOtherStates() {
        RecordingStore delegate = new RecordingStore();
        State other = new State() {
        };

        new TrimmingAgentStateStore(delegate, 1).save("u1", "s1", "other", other);

        assertThat(delegate.lastSaved).isSameAs(other);
    }

    @Test
    @DisplayName("delete(userId, sessionId, key) 必须转发——接口默认实现是空操作")
    void forwardsKeyedDelete() {
        RecordingStore delegate = new RecordingStore();

        new TrimmingAgentStateStore(delegate, 10).delete("u1", "s1", "agent_state");

        // 不转发的话，"删掉一个会话的某个状态"会静默失效
        assertThat(delegate.deleteWithKeyCalled).isTrue();
    }

    @Test
    @DisplayName("尾部整段没有用户消息时宁可超限也不裁——不给模型一段无头上下文")
    void doesNotTrimWhenNoUserBoundaryExists() {
        RecordingStore delegate = new RecordingStore();
        List<Msg> noUserMessages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            noUserMessages.add(new AssistantMessage("答案" + i));
        }
        AgentState state = AgentState.builder().sessionId("s1").context(noUserMessages).build();

        new TrimmingAgentStateStore(delegate, 3).save("u1", "s1", "agent_state", state);

        assertThat(((AgentState) delegate.lastSaved).getContext()).hasSize(10);
    }
}

package com.liusongboyy.chatdata.engine.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.liusongboyy.chatdata.engine.analysis.AnalysisPlan;
import com.liusongboyy.chatdata.engine.analysis.AnalysisPlanSource;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ConflictPolicy;

import java.util.List;

/**
 * 生成分析计划。
 *
 * <p>与 {@link DslGenerator} 结构相同、**同一个模型**，只是 schema 换成
 * {@link AnalysisPlanParser#jsonSchema()}，解析换成 {@link AnalysisPlanParser}。
 *
 * <p>为什么单独一个类而不是给 DslGenerator 加分支：两者的产物类型不同
 * （DSL vs 计划），合并后每个调用点都要判断"这次要哪种"，而那个判断迟早会写错。
 */
public final class AnalysisPlanGenerator implements AnalysisPlanSource {

    private final Model model;
    private final JsonNode schema;
    private final AgentStateStore stateStore;
    private final ConflictPolicy conflictPolicy;

    public AnalysisPlanGenerator(Model model) {
        this(model, null, null);
    }

    public AnalysisPlanGenerator(Model model, AgentStateStore stateStore,
                                 ConflictPolicy conflictPolicy) {
        this.model = model;
        this.schema = AnalysisPlanParser.jsonSchema();
        this.stateStore = stateStore;
        this.conflictPolicy = conflictPolicy;
    }

    @Override
    public AnalysisPlan generatePlan(String question, String sysPrompt,
                                     String userId, String sessionId) {
        ReActAgent.Builder builder = ReActAgent.builder()
                .name("analysis-planner")
                .sysPrompt(sysPrompt)
                .model(model);

        if (stateStore != null) {
            builder.stateStore(stateStore);
            if (conflictPolicy != null) {
                builder.conflictPolicy(conflictPolicy);
            }
        }

        RuntimeContext ctx = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .build();

        Msg reply = builder.build().call(List.of(new UserMessage(question)), schema, ctx).block();
        if (reply == null) {
            throw new DslGenerationException("模型没有返回分析计划");
        }
        String json = reply.getTextContent();
        if (json == null || json.isBlank()) {
            throw new DslGenerationException("模型返回的分析计划为空");
        }
        return AnalysisPlanParser.parse(json, question);
    }
}

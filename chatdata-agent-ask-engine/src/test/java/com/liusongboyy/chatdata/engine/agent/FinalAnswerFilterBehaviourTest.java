package com.liusongboyy.chatdata.engine.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.FinalAnswerFilterMiddleware;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code FinalAnswerFilterMiddleware} 行为的实测记录。
 *
 * <h2>这是决策依据，不是回归测试</h2>
 *
 * <p>它验的是 **agentscope 的行为**，不是本工程的代码——本工程的问数链路里
 * 并没有接这个中间件。之所以留着，是因为"要不要接"这个决定依赖于它的实际效果，
 * 而 PRD 待验证清单里这一条此前是**未实测**状态
 * （见 {@code spike-p0-results.md} 的待验证项 3）。
 *
 * <h2>实测结论</h2>
 *
 * <p>按 model call 缓冲文本事件；该轮若产出工具调用，则这一轮的文本**整段丢弃**，
 * 只放行最后一轮的文本。即"先思考、再查数、再作答"的链路里，
 * 中间那句"我先查一下数据"不会漏给用户。
 *
 * <h2>但它对本工程当前不生效</h2>
 *
 * <p>它的挂载点是 {@code ReActAgent.onReasoning} 返回的事件流，只有走
 * {@code streamEvents(...)} 时才有东西可拦。而本工程的问数链路走的是
 * {@code call(msgs, 手写schema, ctx)}——一次性拿结构化 DSL，从不消费 agent 事件
 * （也正因如此，链路上没有工具、没有中间轮次、没有可泄漏的中间文本）。
 *
 * <p>所以现在接上它**不改变任何可见行为**。等真要按 FR-5.3 把 agent 的
 * 思考文本也流给前端时，这个中间件才是必需的——届时本文件的对照组就是它的验收依据。
 */
class FinalAnswerFilterBehaviourTest {

    /** 探针工具：让模型的工具调用有落点。 */
    public static class ProbeTools {
        @Tool(name = "echo", description = "回显")
        public String echo(@ToolParam(name = "v", required = true, description = "值") String v) {
            return "echo:" + v;
        }
    }

    /**
     * 两轮模型：第 1 轮先输出一句推理文本、再发起工具调用；第 2 轮给出最终答案。
     *
     * <p>这正是"中间推理文本会混进答案流"的最小复现形态。
     */
    private static final class TwoRoundModel implements Model {
        private int round;

        @Override
        public Flux<ChatResponse> stream(List<Msg> msgs, List<ToolSchema> tools,
                                         GenerateOptions options) {
            round++;
            if (round == 1) {
                return Flux.just(
                        ChatResponse.builder()
                                .id("c1")
                                .content(List.of(TextBlock.builder()
                                        .text(INTERMEDIATE).build()))
                                .usage(new ChatUsage(1, 1, 0.0))
                                .finishReason("stop")
                                .build(),
                        ChatResponse.builder()
                                .id("c2")
                                .content(List.of(ToolUseBlock.builder()
                                        .id("call_1").name("echo")
                                        .input(Map.of("v", "x")).build()))
                                .usage(new ChatUsage(1, 1, 0.0))
                                .finishReason("tool_calls")
                                .build());
            }
            return Flux.just(ChatResponse.builder()
                    .id("c3")
                    .content(List.of(TextBlock.builder().text(FINAL).build()))
                    .usage(new ChatUsage(1, 1, 0.0))
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "two-round-fake";
        }
    }

    private static final String INTERMEDIATE = "我先查一下数据";
    private static final String FINAL = "最终答案是 42";

    /** 跑一次流式调用，把透出的文本增量拼起来——这就是前端会看到的东西。 */
    private static String visibleText(boolean withMiddleware) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ProbeTools());

        ReActAgent.Builder builder = ReActAgent.builder()
                .name("filter-probe")
                .sysPrompt("s")
                .model(new TwoRoundModel())
                .toolkit(toolkit)
                .maxIters(5);
        if (withMiddleware) {
            builder.middleware(new FinalAnswerFilterMiddleware());
        }

        RuntimeContext ctx = RuntimeContext.builder().userId("u").sessionId("probe").build();

        List<AgentEvent> events = builder.build()
                .streamEvents(List.of(new UserMessage("查一下")), ctx)
                .collectList()
                .block();

        assertThat(events).isNotNull();

        StringBuilder text = new StringBuilder();
        for (AgentEvent event : events) {
            if (event instanceof TextBlockDeltaEvent delta) {
                text.append(delta.getDelta());
            }
        }
        return text.toString();
    }

    @Test
    @DisplayName("不挂中间件：中间轮次的推理文本会混进答案流——这是要解决的问题")
    void withoutMiddlewareIntermediateTextLeaks() {
        assertThat(visibleText(false))
                .contains(INTERMEDIATE)
                .contains(FINAL);
    }

    @Test
    @DisplayName("挂上中间件：中间推理被抑制，只剩最终答案")
    void withMiddlewareOnlyFinalAnswerSurvives() {
        assertThat(visibleText(true))
                .doesNotContain(INTERMEDIATE)
                .contains(FINAL);
    }
}

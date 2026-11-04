package com.liusongboyy.chatdata.engine.agent;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端验证：<b>真实 {@code ReActAgent} + 假模型</b>，确认 usage 能采到。
 *
 * <h2>为什么要真的跑一遍 ReActAgent</h2>
 *
 * <p>{@code UsageCollectorTest} 只验了"从 {@code Msg} 里取数"这段。而真正容易出问题的是
 * <b>usage 到底在不在那个 {@code Msg} 上</b>——框架把它放在 {@code metadata._chat_usage}
 * 还是 {@code usage} 字段、聚合发生在哪一步、结构化输出那条路径会不会丢，
 * 这些只有跑一遍才知道。假模型让我们能把回包里的 usage 定成已知值，
 * 从而区分"采到了"与"恰好等于 0"。
 *
 * <h2>这条测试挡的是什么</h2>
 *
 * <p>如果框架某次升级改了 usage 的落点，{@code UsageCollector} 会静默退回
 * {@link TokenUsage#UNKNOWN}——问数照常成功，只是成本看板上从某天起全是"—"。
 * 那种问题只有在真实流量跑了几周之后才对账时发现。
 */
class UsageRealAgentTest {

    /** 回包固定报这么多 token，便于断言"采到的就是模型报的"。 */
    private static final class FixedUsageModel implements Model {
        private final ChatUsage usage;
        int calls;

        FixedUsageModel(ChatUsage usage) {
            this.usage = usage;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> msgs, List<ToolSchema> tools,
                                         GenerateOptions options) {
            calls++;
            return Flux.just(ChatResponse.builder()
                    .id("r" + calls)
                    // 结构化输出走 native path 时，模型把 JSON 当**文本**返回
                    .content(List.of(TextBlock.builder()
                            .text("{\"metrics\":[\"保费\"],\"dimensions\":[],"
                                    + "\"timeRange\":null,\"filters\":[]}")
                            .build()))
                    .usage(usage)
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "fixed-usage-fake";
        }

        @Override
        public boolean supportsNativeStructuredOutput() {
            return true;
        }

        @Override
        public boolean supportsNativeStructuredOutputWithTools() {
            return false;
        }
    }

    @Test
    @DisplayName("★ 真实 ReActAgent 跑一轮，usage 原样传回")
    void realAgentCarriesUsage() {
        var reported = new ChatUsage(1032, 49, 1024, 0, 7, 0, 1.5);
        var model = new FixedUsageModel(reported);

        var got = new DslGenerator(model).generate(
                "上个月华东的保费是多少", "你是问数助手", "u1", "s1");

        assertThat(model.calls).isEqualTo(1);
        assertThat(got.value().isEmpty()).as("前提：DSL 要解析成功").isFalse();

        var usage = got.usage();
        assertThat(usage.known()).as("采不到 usage 时这里是 false，成本看板会永远显示 —")
                .isTrue();
        assertThat(usage.inputTokens()).isEqualTo(1032);
        assertThat(usage.outputTokens()).isEqualTo(49);
        // **缓存命中是输入的子集**，不是额外加的。这个断言防止有人"顺手"改成累加
        assertThat(usage.cachedTokens()).isEqualTo(1024);
        assertThat(usage.reasoningTokens()).isEqualTo(7);
        // 1032 输入里 1024 命中 → 99.2%
        assertThat(usage.cacheHitRate()).isEqualTo(99.2);
    }

    @Test
    @DisplayName("模型不报 usage → UNKNOWN（不是 0）")
    void noUsageReportedIsUnknown() {
        // 供应商不返回 usage 是真实存在的情况（部分兼容实现只返回 content）
        var model = new FixedUsageModel(null);

        var got = new DslGenerator(model).generate("问题", "sys", "u1", "s1");

        assertThat(got.usage().known()).isFalse();
    }
}

package com.liusongboyy.chatdata.engine.agent;

import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * token 采集。
 *
 * <p>这里测的是**语义**，不是解析：哪几个字段是子集、哪些情况必须记"不知道"
 * 而不是 0。后者弄错会让成本看板凭空少一块，而少掉的那块恰好是
 * "重试很多次"这种最该被看见的消耗。
 */
class UsageCollectorTest {

    private static Msg replyWith(ChatUsage usage) {
        return AssistantMessage.builder().content(TextBlock.builder().text("ok").build()).usage(usage).build();
    }

    @Test
    @DisplayName("★ 六个字段原样搬过来，不做任何加减")
    void copiesAllFieldsVerbatim() {
        var usage = new ChatUsage(1032, 49, 1024, 0, 7, 3, 1.5);

        var got = UsageCollector.of(replyWith(usage));

        assertThat(got.inputTokens()).isEqualTo(1032);
        assertThat(got.outputTokens()).isEqualTo(49);
        // 缓存命中**是输入的子集**，不是额外加的。这里若写 2056 就高估一倍
        assertThat(got.cachedTokens()).isEqualTo(1024);
        assertThat(got.reasoningTokens()).isEqualTo(7);
    }

    @Test
    @DisplayName("★ 没有 usage → UNKNOWN，不是 ZERO")
    void missingUsageIsUnknownNotZero() {
        var got = UsageCollector.of(AssistantMessage.builder().content(TextBlock.builder().text("ok").build()).build());

        assertThat(got.known()).as("写成 0 会让这批请求在成本看板上算成\"免费\"").isFalse();
    }

    @Test
    @DisplayName("回复为 null → UNKNOWN，不抛")
    void nullReplyIsUnknown() {
        assertThat(UsageCollector.of((Msg) null).known()).isFalse();
        assertThat(UsageCollector.of((ChatUsage) null).known()).isFalse();
    }

    @Test
    @DisplayName("★ UNKNOWN 与 ZERO 在累加时语义不同")
    void accumulatingRespectsUnknownVersusZero() {
        var real = new TokenUsage(100, 10, 0, 0, 0);

        // 不知道 + 确切值 = 确切值（把"没采到"当成没发生，比当成 0 更接近真相）
        assertThat(TokenUsage.UNKNOWN.plus(real)).isEqualTo(real);
        // 确切的 0 + 确切值 = 确切的 0 + 值
        assertThat(TokenUsage.ZERO.plus(real)).isEqualTo(real);
        // 两边都不知道 → 还是不知道
        assertThat(TokenUsage.UNKNOWN.plus(TokenUsage.UNKNOWN).known()).isFalse();
    }

    @Test
    @DisplayName("★ 多轮累加：三次调用全加起来")
    void sumsAcrossCalls() {
        var a = new TokenUsage(100, 10, 0, 0, 0);
        var b = new TokenUsage(200, 20, 150, 0, 5);

        var total = a.plus(b).plus(a);

        assertThat(total.inputTokens()).isEqualTo(400);
        assertThat(total.outputTokens()).isEqualTo(40);
        assertThat(total.cachedTokens()).isEqualTo(150);
        assertThat(total.reasoningTokens()).isEqualTo(5);
    }

    @Test
    @DisplayName("缓存命中率按输入量算，没有输入时是 null 而不是 0")
    void cacheHitRate() {
        assertThat(new TokenUsage(1000, 0, 250, 0, 0).cacheHitRate()).isEqualTo(25.0);
        assertThat(TokenUsage.ZERO.cacheHitRate()).as("0% 与\"没数据\"不是一回事").isNull();
        assertThat(TokenUsage.UNKNOWN.cacheHitRate()).isNull();
    }
}

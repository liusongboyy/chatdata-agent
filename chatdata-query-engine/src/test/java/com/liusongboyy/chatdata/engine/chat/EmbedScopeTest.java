package com.liusongboyy.chatdata.engine.chat;

import com.liusongboyy.chatdata.engine.agent.AskDeniedException;
import com.liusongboyy.chatdata.engine.permission.PermissionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * embed 令牌的助理约束——**整个 embed 方向唯一的安全边界**。
 *
 * <p>宿主系统拿到的令牌，如果不限制助理，它就等于拿到了一张普通用户令牌：
 * CRM 可以问保险域的数据，而它只该看 CRM 那个域。
 *
 * <p>约束的传递链是三段：IAM 写进令牌属性 → {@code /iam/principal} 透出 →
 * {@code AskServiceFactory.enforceEmbedScope} 强制。任一段失效都是越权。
 *
 * <p>这里验第三段，也就是**最后一道闸门**。
 */
class EmbedScopeTest {

    private static PermissionContext ctx(Map<String, Object> vars) {
        return PermissionContext.of("u1", Set.of("admin"), vars);
    }

    @Test
    @DisplayName("★ 普通令牌（没有 embedAssistant）不受限制")
    void plainTokenIsUnrestricted() {
        assertThatCode(() -> AskServiceFactory.enforceEmbedScope("2200", ctx(Map.of())))
                .doesNotThrowAnyException();
        // 不指定助理也不该被拒——普通用户可以不选助理，走助理层自己的闸门
        assertThatCode(() -> AskServiceFactory.enforceEmbedScope(null, ctx(Map.of())))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ embed 令牌：请求的助理与令牌限定的一致 → 放行")
    void matchingAssistantPasses() {
        assertThatCode(() -> AskServiceFactory.enforceEmbedScope(
                "2200", ctx(Map.of("embedAssistant", "2200"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ embed 令牌：请求别的助理 → 拒绝，且消息里带上限定的那个")
    void otherAssistantRejected() {
        assertThatThrownBy(() -> AskServiceFactory.enforceEmbedScope(
                "2300", ctx(Map.of("embedAssistant", "2200"))))
                .isInstanceOf(AskDeniedException.class)
                // 消息必须说明「是这个令牌的限制」，否则宿主开发者会去查用户权限，
                // 方向完全错了
                .hasMessageContaining("2200");
    }

    @Test
    @DisplayName("★ embed 令牌：不指定助理 → 拒绝（不能退化成「随便问」）")
    void missingAssistantRejected() {
        assertThatThrownBy(() -> AskServiceFactory.enforceEmbedScope(
                null, ctx(Map.of("embedAssistant", "2200"))))
                .isInstanceOf(AskDeniedException.class)
                .hasMessageContaining("必须指定");

        assertThatThrownBy(() -> AskServiceFactory.enforceEmbedScope(
                "   ", ctx(Map.of("embedAssistant", "2200"))))
                .isInstanceOf(AskDeniedException.class);
    }

    @Test
    @DisplayName("★ 上下文为 null 时不炸（内部/评测调用没有 ctx）")
    void nullContextIsSafe() {
        assertThatCode(() -> AskServiceFactory.enforceEmbedScope("2200", null))
                .doesNotThrowAnyException();
    }
}

package com.liusongboyy.chatdata.iam.embed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * embed grant 的常量与边界。
 *
 * <p>完整的 provider 测试要 mock 一整套 Spring Authorization Server 的对象，
 * 成本高而收益低——真正的安全边界靠**集成测试**（真起服务、真换令牌）验证更可靠。
 *
 * <p>这里守的是几条"改一个字符就静默失效"的东西。
 */
class EmbedGrantTest {

    @Test
    @DisplayName("★ grant type 用 urn: 前缀——避免与标准值冲突")
    void grantTypeHasUrnPrefix() {
        assertThat(EmbedGrantType.VALUE).startsWith("urn:chatdata:");
        assertThat(EmbedGrantType.VALUE).contains("embed");
    }

    @Test
    @DisplayName("★ 三个 grant 的标识互不相同")
    void grantTypesAreDistinct() {
        // 撞了的话 token 端点会按第一个匹配的 converter 处理，
        // 表现为「用 embed 换到了渠道令牌」，而两者权限范围完全不同
        assertThat(EmbedGrantType.VALUE)
                .isNotEqualTo("urn:chatdata:params:oauth:grant-type:channel")
                .isNotEqualTo("urn:chatdata:params:oauth:grant-type:agent-proxy");
    }

    @Test
    @DisplayName("★ 令牌有效期是 5 分钟——泄露窗口要短")
    void tokenTtlIsShort() {
        assertThat(EmbedGrantAuthenticationProvider.TOKEN_TTL.toMinutes()).isEqualTo(5);
    }

    @Test
    @DisplayName("★ 令牌属性名稳定——ask 侧按字符串读，改了会让约束失效")
    void attributeNamesAreStable() {
        // 这两个值会被写进 oauth2_authorization 的 attributes，
        // 并被 /iam/principal 透出、被 ask 读取。改动等于破坏兼容
        assertThat(EmbedGrantAuthenticationProvider.ATTR_ASSISTANT).isEqualTo("embed_assistant");
        assertThat(EmbedGrantAuthenticationProvider.ATTR_CLIENT).isEqualTo("embed_client");
    }

    @Test
    @DisplayName("★ 参数名稳定——宿主系统按它拼请求")
    void paramNamesAreStable() {
        assertThat(EmbedGrantType.PARAM_USER_REF).isEqualTo("user_ref");
        assertThat(EmbedGrantType.PARAM_ASSISTANT).isEqualTo("assistant");
    }
}

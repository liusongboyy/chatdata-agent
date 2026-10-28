package com.liusongboyy.chatdata.iam.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 请求解析。
 *
 * <p>最要紧的一条：<b>不是本 grant 的请求必须返回 null</b>。
 * 抛异常会打断 token 端点的转换器链，让授权码与刷新令牌一起失效——
 * 那是"改一个功能，坏了另外两个"的经典形态。
 */
class ChannelGrantAuthenticationConverterTest {

    private final ChannelGrantAuthenticationConverter converter =
            new ChannelGrantAuthenticationConverter();

    private static MockHttpServletRequest request(String grantType, String channel, String user) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/oauth2/token");
        if (grantType != null) {
            req.setParameter("grant_type", grantType);
        }
        if (channel != null) {
            req.setParameter(ChannelGrantType.PARAM_CHANNEL, channel);
        }
        if (user != null) {
            req.setParameter(ChannelGrantType.PARAM_USER, user);
        }
        return req;
    }

    @Test
    @DisplayName("★ 其他 grant 类型返回 null，不打断转换器链")
    void otherGrantReturnsNull() {
        assertThat(converter.convert(request("authorization_code", null, null))).isNull();
        assertThat(converter.convert(request("refresh_token", null, null))).isNull();
        assertThat(converter.convert(request("client_credentials", null, null))).isNull();
        assertThat(converter.convert(request(null, null, null))).isNull();
    }

    @Test
    @DisplayName("★ 缺 channel 或 username → 拒绝，不静默放行")
    void missingParamsReject() {
        assertThatThrownBy(() -> converter.convert(request(ChannelGrantType.VALUE, null, "zhangsan")))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> converter.convert(request(ChannelGrantType.VALUE, "feishu", null)))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> converter.convert(request(ChannelGrantType.VALUE, "  ", "  ")))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    @DisplayName("正常解析：客户端身份从 SecurityContext 取")
    void convertsValidRequest() {
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("channel-bot")
                .clientSecret("{noop}x")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(ChannelGrantType.CHANNEL)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new OAuth2ClientAuthenticationToken(client,
                        ClientAuthenticationMethod.CLIENT_SECRET_BASIC, null));
        try {
            Authentication result = converter.convert(
                    request(ChannelGrantType.VALUE, "feishu", "  zhangsan  "));

            assertThat(result).isInstanceOf(ChannelGrantAuthenticationToken.class);
            ChannelGrantAuthenticationToken token = (ChannelGrantAuthenticationToken) result;
            assertThat(token.getChannel()).isEqualTo("feishu");
            // 两侧空白要去掉，否则与 iam_user.username 的精确匹配会失效
            assertThat(token.getUsername()).isEqualTo("zhangsan");
            assertThat(token.getPrincipal()).isNotNull();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}

package com.liusongboyy.chatdata.iam.channel;

import com.liusongboyy.chatdata.iam.config.IamProperties;
import com.liusongboyy.chatdata.iam.user.UserMapper;
import com.liusongboyy.chatdata.iam.user.UserRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 渠道换令牌的三道校验。
 *
 * <p>本类是整套 channel 接入的安全支点：只要漏一条，
 * 一个泄露的 client secret 就能冒充任意用户读取任意数据。
 */
class ChannelGrantAuthenticationProviderTest {

    private UserMapper users;
    private OAuth2AuthorizationService authorizations;
    private OAuth2TokenGenerator<?> tokenGenerator;
    private IamProperties props;
    private ChannelGrantAuthenticationProvider provider;

    @BeforeEach
    void setUp() {
        // 真实链路上由 OAuth2TokenEndpointFilter 在请求线程里设置，
        // 单测没有请求上下文，必须自己放一个——否则 tokenContext 构建时
        // authorizationServerContext(...) 会因 null 抛 IllegalArgumentException
        org.springframework.security.oauth2.server.authorization.context
                .AuthorizationServerContextHolder.setContext(
                        new org.springframework.security.oauth2.server.authorization.context
                                .AuthorizationServerContext() {
                            @Override
                            public String getIssuer() {
                                return "http://127.0.0.1:9000";
                            }

                            @Override
                            public org.springframework.security.oauth2.server.authorization.settings
                                    .AuthorizationServerSettings getAuthorizationServerSettings() {
                                return org.springframework.security.oauth2.server.authorization.settings
                                        .AuthorizationServerSettings.builder()
                                        .issuer("http://127.0.0.1:9000")
                                        .build();
                            }
                        });
        users = mock(UserMapper.class);
        authorizations = mock(OAuth2AuthorizationService.class);
        tokenGenerator = mock(OAuth2TokenGenerator.class);
        props = new IamProperties();
        props.getClients().setChannelSecret("test-secret");
        provider = new ChannelGrantAuthenticationProvider(
                authorizations, tokenGenerator, users, props);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        org.springframework.security.oauth2.server.authorization.context
                .AuthorizationServerContextHolder.resetContext();
    }

    private static RegisteredClient channelClient(String clientId) {
        return RegisteredClient.withId("reg-" + clientId)
                .clientId(clientId)
                .clientSecret("{noop}test-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(ChannelGrantType.CHANNEL)
                .scope("ask.read")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                        .accessTokenTimeToLive(Duration.ofMinutes(5))
                        .build())
                .build();
    }

    private static ChannelGrantAuthenticationToken request(String channel, String username,
                                                           String clientId) {
        RegisteredClient client = channelClient(clientId);
        // 用 3 参构造：它会把 authenticated 置 true，与真实链路上
        // OAuth2ClientAuthenticationFilter 的产出一致
        Authentication clientPrincipal = new OAuth2ClientAuthenticationToken(
                client, ClientAuthenticationMethod.CLIENT_SECRET_BASIC, null);
        return new ChannelGrantAuthenticationToken(channel, username, clientPrincipal, Map.of());
    }

    @Test
    @DisplayName("supports 只认本 grant 的 token")
    void supportsOnlyChannelGrant() {
        assertThat(provider.supports(ChannelGrantAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken.class)).isFalse();
    }

    @Test
    @DisplayName("★ 客户端不是 channel-bot → 拒绝（光有 grant type 不够）")
    void rejectsForeignClient() {
        // 别的客户端就算注册了这个 grant type，也不能换令牌
        var token = request("feishu", "zhangsan", "some-other-client");

        assertThatThrownBy(() -> provider.authenticate(token))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("无权使用渠道授权类型");

        verify(users, never()).selectBoundToChannel(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("★ 用户未绑定该渠道 → 拒绝（防万能钥匙）")
    void rejectsUnboundUser() {
        when(users.selectBoundToChannel(anyString(), anyString(), anyString())).thenReturn(null);

        var token = request("feishu", "zhangsan", "channel-bot");

        assertThatThrownBy(() -> provider.authenticate(token))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("未绑定");

        // 关键：没有签发任何令牌，也没有落授权记录
        verify(authorizations, never()).save(any());
    }

    @Test
    @DisplayName("用户绑定了别的渠道（provider 不匹配）→ 拒绝")
    void rejectsWrongProvider() {
        // provider=feishu 查不到（他只绑了 dingtalk）
        when(users.selectBoundToChannel(anyString(), anyString(), eq("feishu"))).thenReturn(null);
        when(users.selectBoundToChannel(anyString(), anyString(), eq("dingtalk")))
                .thenReturn(new UserRow(1L, "zhangsan", null, "张三", "ACTIVE", 0));

        var token = request("feishu", "zhangsan", "channel-bot");

        assertThatThrownBy(() -> provider.authenticate(token))
                .isInstanceOf(OAuth2AuthenticationException.class);
        verify(authorizations, never()).save(any());
    }

    @Test
    @DisplayName("账号已停用 → 拒绝，不产生无意义的授权记录")
    void rejectsDisabledUser() {
        when(users.selectBoundToChannel(anyString(), anyString(), anyString()))
                .thenReturn(new UserRow(1L, "zhangsan", null, "张三", "DISABLED", 0));

        var token = request("feishu", "zhangsan", "channel-bot");

        assertThatThrownBy(() -> provider.authenticate(token))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("停用");
        verify(authorizations, never()).save(any());
    }

    @Test
    @DisplayName("查库时带的是 provider，不是写死的 feishu")
    void lookupUsesRequestedProvider() {
        when(users.selectBoundToChannel(anyString(), anyString(), anyString()))
                .thenReturn(new UserRow(1L, "zhangsan", null, "张三", "DISABLED", 0));

        assertThatThrownBy(() -> provider.authenticate(
                request("dingtalk", "zhangsan", "channel-bot")))
                .isInstanceOf(OAuth2AuthenticationException.class);

        verify(users).selectBoundToChannel("t_default", "zhangsan", "dingtalk");
    }

    /**
     * 一个正向用例：绑定的启用用户能拿到令牌，且授权记录的 principal 是用户本人。
     *
     * <p>principal 记成 bot 的话，{@code oauth2_authorization} 里所有渠道令牌
     * 都在同一个名字下，审计查不到是谁。
     */
    @Test
    @DisplayName("★ 绑定且启用的用户 → 签发令牌，principal 记为用户本人")
    void issuesTokenForBoundUser() {
        when(users.selectBoundToChannel(anyString(), anyString(), anyString()))
                .thenReturn(new UserRow(7L, "zhangsan", null, "张三", "ACTIVE", 0));
        when(tokenGenerator.generate(any()))
                .thenReturn(new org.springframework.security.oauth2.core.OAuth2AccessToken(
                        org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER,
                        "opaque-token-value",
                        java.time.Instant.now(),
                        java.time.Instant.now().plusSeconds(300),
                        java.util.Set.of("ask.read")));

        Authentication result = provider.authenticate(request("feishu", "zhangsan", "channel-bot"));

        assertThat(result).isNotNull();
        org.mockito.ArgumentCaptor<org.springframework.security.oauth2.server.authorization.OAuth2Authorization>
                saved = org.mockito.ArgumentCaptor.forClass(
                        org.springframework.security.oauth2.server.authorization.OAuth2Authorization.class);
        verify(authorizations).save(saved.capture());
        assertThat(saved.getValue().getPrincipalName())
                .as("principal_name 必须是用户本人，不能是 bot")
                .isEqualTo("zhangsan");
        // getAttribute 是泛型 <T> T，不写目标类型时 assertThat 会因重载歧义编译失败
        String channel = saved.getValue().getAttribute("channel");
        assertThat(channel).isEqualTo("feishu");
    }

    /**
     * ★ 回归：令牌必须以**生成器返回的原类型**存进 authorization。
     *
     * <p>曾经的 bug：provider 把生成器的产物重建成 {@code new OAuth2AccessToken(...)}。
     * {@code OAuth2Authorization.Builder} 以 {@code token.getClass()} 为键存 token，
     * 重建后键从内部的 {@code OAuth2AccessTokenClaims} 变成了 {@code OAuth2AccessToken}，
     * 于是 {@code JdbcOAuth2AuthorizationService} 的
     * {@code getToken(OAuth2AccessToken.class)} 取不到——
     * 落库时 {@code access_token_value} 全为 NULL。
     *
     * <p>症状极具迷惑性：换令牌返回 200 并带回 token 字符串，
     * 但拿它调任何资源服务器都是 401，而 IAM 日志里没有任何异常。
     */
    @Test
    @DisplayName("★ 令牌按生成器的原类型存入，不被重新包装")
    void accessTokenKeepsGeneratorType() {
        when(users.selectBoundToChannel(anyString(), anyString(), anyString()))
                .thenReturn(new UserRow(7L, "zhangsan", null, "张三", "ACTIVE", 0));

        // 用一个子类模拟 OAuth2AccessTokenClaims：真实生成器产出的就是子类
        class ClaimsLike extends org.springframework.security.oauth2.core.OAuth2AccessToken {
            ClaimsLike() {
                super(TokenType.BEARER, "opaque-value",
                        java.time.Instant.now(), java.time.Instant.now().plusSeconds(300),
                        java.util.Set.of("ask.read"));
            }
        }
        when(tokenGenerator.generate(any())).thenReturn(new ClaimsLike());

        provider.authenticate(request("feishu", "zhangsan", "channel-bot"));

        org.mockito.ArgumentCaptor<org.springframework.security.oauth2.server.authorization.OAuth2Authorization>
                saved = org.mockito.ArgumentCaptor.forClass(
                        org.springframework.security.oauth2.server.authorization.OAuth2Authorization.class);
        verify(authorizations).save(saved.capture());

        // 关键断言：按**父类** OAuth2AccessToken 能取到 token。
        // JdbcOAuth2AuthorizationService 用的就是父类；取不到 = 落库时 access_token_value 为 NULL
        var stored = saved.getValue();
        assertThat(stored.getAccessToken())
                .as("必须能按 OAuth2AccessToken.class 取到，否则写库时 token 为空")
                .isNotNull();
        assertThat(stored.getAccessToken().getToken().getTokenValue()).isEqualTo("opaque-value");
    }
}

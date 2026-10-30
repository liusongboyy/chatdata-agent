package com.liusongboyy.chatdata.iam.agentproxy;

import com.liusongboyy.chatdata.iam.config.IamProperties;
import com.liusongboyy.chatdata.iam.user.UserMapper;
import com.liusongboyy.chatdata.iam.user.UserRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

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
 * Agent 代理换令牌的三道校验。
 *
 * <p>本类是 A2A 接入的安全支点：只要漏一条，一个泄露的 client secret
 * 就能以任意**已授权用户**的身份读数据。而第三条（授权表）是唯一的真边界——
 * 白名单只是防误接，客户端身份在没有传输层认证时也是可伪造的。
 */
class AgentProxyAuthenticationProviderTest {

    private UserMapper users;
    private OAuth2AuthorizationService authorizations;
    private OAuth2TokenGenerator<?> tokenGenerator;
    private IamProperties props;
    private AgentProxyAuthenticationProvider provider;

    @BeforeEach
    void setUp() {
        AuthorizationServerContextHolder.setContext(new AuthorizationServerContext() {
            @Override
            public String getIssuer() {
                return "http://127.0.0.1:9000";
            }

            @Override
            public AuthorizationServerSettings getAuthorizationServerSettings() {
                return AuthorizationServerSettings.builder().issuer("http://127.0.0.1:9000").build();
            }
        });
        users = mock(UserMapper.class);
        authorizations = mock(OAuth2AuthorizationService.class);
        tokenGenerator = mock(OAuth2TokenGenerator.class);
        props = new IamProperties();
        props.getClients().setAgentMeshSecret("test-secret");
        provider = new AgentProxyAuthenticationProvider(
                authorizations, tokenGenerator, users, props);
    }

    @AfterEach
    void tearDown() {
        AuthorizationServerContextHolder.resetContext();
    }

    private static RegisteredClient meshClient() {
        return RegisteredClient.withId("reg-mesh")
                .clientId("agent-mesh")
                .clientSecret("{noop}test-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AgentProxyGrantType.AGENT_PROXY)
                .scope("ask.read")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                        .accessTokenTimeToLive(Duration.ofMinutes(5))
                        .build())
                .build();
    }

    private static AgentProxyGrantAuthenticationToken request(String agent, String user,
                                                              String clientId) {
        RegisteredClient client = "agent-mesh".equals(clientId)
                ? meshClient()
                : RegisteredClient.withId("other")
                        .clientId(clientId)
                        .clientSecret("{noop}x")
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .authorizationGrantType(AgentProxyGrantType.AGENT_PROXY)
                        .scope("ask.read")
                        .build();
        Authentication clientPrincipal = new OAuth2ClientAuthenticationToken(
                client, ClientAuthenticationMethod.CLIENT_SECRET_BASIC, null);
        return new AgentProxyGrantAuthenticationToken(agent, user, clientPrincipal, Map.of());
    }

    @Test
    @DisplayName("supports 只认本 grant 的 token")
    void supportsOnlyProxyGrant() {
        assertThat(provider.supports(AgentProxyGrantAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.class))
                .isFalse();
    }

    @Test
    @DisplayName("★ 客户端不是 agent-mesh → 拒绝（光有 grant type 不够）")
    void rejectsForeignClient() {
        var token = request("report-bot", "zhangsan", "some-other-client");

        assertThatThrownBy(() -> provider.authenticate(token))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("无权使用");

        verify(users, never()).selectAgentProxiedUser(anyString(), anyString(), anyString());
    }

    /**
     * ★ 核心限制：用户没有授权这个 agent → 拒绝。
     *
     * <p>这条不成立的话，任何能调通 A2A 端点的人说出管理员用户名
     * 就能以他身份查数。
     */
    @Test
    @DisplayName("★ 用户未授权该 agent → 拒绝（唯一的真边界）")
    void rejectsUnauthorizedAgent() {
        when(users.selectAgentProxiedUser(anyString(), anyString(), anyString())).thenReturn(null);

        var token = request("report-bot", "zhangsan", "agent-mesh");

        assertThatThrownBy(() -> provider.authenticate(token))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("未授权");

        // 关键：没有签发令牌，也没有落授权记录
        verify(authorizations, never()).save(any());
    }

    @Test
    @DisplayName("用户授权了 A agent，B agent 来调 → 拒绝（粒度是按 agent）")
    void authorizationIsPerAgent() {
        // provider=report-bot 查不到（他只授权了 weekly-digest）
        when(users.selectAgentProxiedUser(anyString(), anyString(), eq("report-bot")))
                .thenReturn(null);
        when(users.selectAgentProxiedUser(anyString(), anyString(), eq("weekly-digest")))
                .thenReturn(new UserRow(1L, "zhangsan", null, "张三", "ACTIVE", 0));

        assertThatThrownBy(() -> provider.authenticate(
                request("report-bot", "zhangsan", "agent-mesh")))
                .isInstanceOf(OAuth2AuthenticationException.class);

        verify(authorizations, never()).save(any());
    }

    @Test
    @DisplayName("账号已停用 → 拒绝")
    void rejectsDisabledUser() {
        when(users.selectAgentProxiedUser(anyString(), anyString(), anyString()))
                .thenReturn(new UserRow(1L, "zhangsan", null, "张三", "DISABLED", 0));

        assertThatThrownBy(() -> provider.authenticate(
                request("report-bot", "zhangsan", "agent-mesh")))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("停用");

        verify(authorizations, never()).save(any());
    }

    @Test
    @DisplayName("查库时带的是请求里的 agent，不是写死的值")
    void lookupUsesRequestedAgent() {
        when(users.selectAgentProxiedUser(anyString(), anyString(), anyString()))
                .thenReturn(new UserRow(1L, "zhangsan", null, "张三", "DISABLED", 0));

        assertThatThrownBy(() -> provider.authenticate(
                request("ops-agent", "lisi", "agent-mesh")))
                .isInstanceOf(OAuth2AuthenticationException.class);

        verify(users).selectAgentProxiedUser("t_default", "lisi", "ops-agent");
    }

    /**
     * 正向用例：授权成立时签发令牌，且**审计记的是被代理的人，不是 agent**。
     *
     * <p>记成 agent 的话，{@code oauth2_authorization} 里所有代理令牌
     * 都在同一个 agent 名下，"替谁查的"就查不出来了。
     */
    @Test
    @DisplayName("★ 授权成立 → 签发令牌，principal 是用户本人，attributes 记 agent")
    void issuesTokenForAuthorizedAgent() {
        when(users.selectAgentProxiedUser(anyString(), anyString(), anyString()))
                .thenReturn(new UserRow(7L, "zhangsan", null, "张三", "ACTIVE", 0));
        when(tokenGenerator.generate(any()))
                .thenReturn(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                        "opaque-token", Instant.now(), Instant.now().plusSeconds(300),
                        Set.of("ask.read")));

        provider.authenticate(request("report-bot", "zhangsan", "agent-mesh"));

        var saved = org.mockito.ArgumentCaptor.forClass(OAuth2Authorization.class);
        verify(authorizations).save(saved.capture());

        assertThat(saved.getValue().getPrincipalName())
                .as("principal_name 必须是用户本人，不能是 agent")
                .isEqualTo("zhangsan");
        String agent = saved.getValue()
                .getAttribute(AgentProxyAuthenticationProvider.ATTR_AGENT_CLIENT);
        assertThat(agent)
                .as("审计里要能看出是哪个 agent 代理的")
                .isEqualTo("report-bot");
    }

    @Test
    @DisplayName("★ 令牌按父类存入，否则落库时 access_token_value 为 NULL")
    void accessTokenStoredUnderParentClass() {
        when(users.selectAgentProxiedUser(anyString(), anyString(), anyString()))
                .thenReturn(new UserRow(7L, "zhangsan", null, "张三", "ACTIVE", 0));

        // 用子类模拟生成器真实的产物（OAuth2AccessTokenClaims）
        class ClaimsLike extends OAuth2AccessToken {
            ClaimsLike() {
                super(TokenType.BEARER, "opaque", Instant.now(),
                        Instant.now().plusSeconds(300), Set.of("ask.read"));
            }
        }
        when(tokenGenerator.generate(any())).thenReturn(new ClaimsLike());

        provider.authenticate(request("report-bot", "zhangsan", "agent-mesh"));

        var saved = org.mockito.ArgumentCaptor.forClass(OAuth2Authorization.class);
        verify(authorizations).save(saved.capture());
        assertThat(saved.getValue().getAccessToken()).isNotNull();
        assertThat(saved.getValue().getAccessToken().getToken().getTokenValue())
                .isEqualTo("opaque");
    }
}

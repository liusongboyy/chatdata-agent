package com.liusongboyy.chatdata.iam.serviceauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 服务间调用的客户端凭证校验。
 *
 * <p>这段逻辑原先长在 {@code EffectivePolicyController} 的私有方法里、没有测试覆盖。
 * 抽出来时补上——它是 {@code /iam/policy/**} 上**唯一**的闸门
 * （该前缀在过滤链上是 permitAll），漏判的后果是任意调用方读到全量策略。
 *
 * <p>Bearer 那条路（ask 实际走的那条）需要构造真实的 {@code OAuth2Authorization}，
 * 成本高于收益，故这里覆盖 Basic 的判据，Bearer 的分支由集成验证覆盖。
 */
class IamServiceClientAuthTest {

    private static final String SCOPE = "iam.principal";

    private RegisteredClientRepository clients;
    private PasswordEncoder encoder;
    private IamServiceClientAuth auth;

    private void setUp(RegisteredClient client) {
        clients = mock(RegisteredClientRepository.class);
        encoder = mock(PasswordEncoder.class);
        OAuth2AuthorizationService authorizations = mock(OAuth2AuthorizationService.class);
        when(clients.findByClientId(anyString())).thenReturn(client);
        when(encoder.matches(anyString(), anyString())).thenReturn(true);
        auth = new IamServiceClientAuth(clients, encoder, authorizations);
    }

    private static RegisteredClient client(Set<String> scopes) {
        return RegisteredClient.withId("1")
                .clientId("ask-resource-server")
                .clientSecret("{bcrypt}hash")
                .authorizationGrantType(
                        org.springframework.security.oauth2.core.AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scopes(s -> s.addAll(scopes))
                .build();
    }

    private static String basic(String id, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("没有 Authorization 头 → 拒绝")
    void noHeaderDenied() {
        setUp(client(Set.of(SCOPE)));
        assertThat(auth.allowed(null, SCOPE)).isFalse();
    }

    @Test
    @DisplayName("认不出的凭据方案 → 拒绝（不是放行）")
    void unknownSchemeDenied() {
        setUp(client(Set.of(SCOPE)));
        assertThat(auth.allowed("Token abc", SCOPE)).isFalse();
    }

    @Test
    @DisplayName("Basic 凭据正确且带 iam.principal → 放行")
    void basicWithScopeAllowed() {
        setUp(client(Set.of(SCOPE)));
        assertThat(auth.allowed(basic("ask-resource-server", "secret"), SCOPE)).isTrue();
    }

    @Test
    @DisplayName("★ Basic 凭据正确但缺 iam.principal scope → 拒绝")
    void basicWithoutScopeDenied() {
        setUp(client(Set.of("ask.read")));
        assertThat(auth.allowed(basic("ask-resource-server", "secret"), SCOPE)).isFalse();
    }

    @Test
    @DisplayName("★ Basic 密钥不对 → 拒绝")
    void basicWrongSecretDenied() {
        setUp(client(Set.of(SCOPE)));
        when(encoder.matches(anyString(), anyString())).thenReturn(false);
        assertThat(auth.allowed(basic("ask-resource-server", "wrong"), SCOPE)).isFalse();
    }

    @Test
    @DisplayName("客户端不存在 → 拒绝")
    void unknownClientDenied() {
        setUp(null);
        assertThat(auth.allowed(basic("nobody", "secret"), SCOPE)).isFalse();
    }

    @Test
    @DisplayName("Basic 头不是合法 base64 → 拒绝，不抛")
    void malformedBasicDenied() {
        setUp(client(Set.of(SCOPE)));
        assertThat(auth.allowed("Basic !!!not-base64!!!", SCOPE)).isFalse();
        assertThat(auth.allowed("Basic " + Base64.getEncoder()
                .encodeToString("no-colon".getBytes(StandardCharsets.UTF_8)), SCOPE)).isFalse();
    }

    @Test
    @DisplayName("Bearer 令牌不存在 → 拒绝")
    void unknownBearerDenied() {
        RegisteredClientRepository repo = mock(RegisteredClientRepository.class);
        OAuth2AuthorizationService authorizations = mock(OAuth2AuthorizationService.class);
        when(authorizations.findByToken(anyString(), any())).thenReturn(null);
        IamServiceClientAuth a = new IamServiceClientAuth(repo, mock(PasswordEncoder.class),
                authorizations);

        assertThat(a.allowed("Bearer whatever", SCOPE)).isFalse();
    }

    @Test
    @DisplayName("★ scope 按前缀判：有 iam.identity 不等于能读 iam.principal")
    void scopesAreNotInterchangeable() {
        // 渠道拿的是 iam.identity。它不该因为"有令牌"就能读全量用户目录
        setUp(client(Set.of("iam.identity")));

        assertThat(auth.allowed(basic("ask-resource-server", "secret"),
                IamServiceClientAuth.SCOPE_IDENTITY))
                .as("自己那一档该放行")
                .isTrue();
        assertThat(auth.allowed(basic("ask-resource-server", "secret"),
                IamServiceClientAuth.SCOPE_PRINCIPAL))
                .as("拿窄的 scope 去读宽的东西必须被拒")
                .isFalse();
    }

    @Test
    @DisplayName("★ 反过来也一样：iam.principal 不自动包含 iam.identity")
    void principalDoesNotImplyIdentity() {
        // 这不是"应该如此"的问题，而是**当前实现的直接后果**。
        // 记下来是因为以后可能想加继承关系——那时这条测试会提醒你
        // 这是个有意的决定，不是遗漏
        setUp(client(Set.of(IamServiceClientAuth.SCOPE_PRINCIPAL)));

        assertThat(auth.allowed(basic("ask-resource-server", "secret"),
                IamServiceClientAuth.SCOPE_IDENTITY)).isFalse();
    }
}

package com.liusongboyy.chatdata.iam.policy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * {@code /iam/policy/**} 的客户端凭证校验。
 *
 * <h2>为什么是独立的组件，而不是控制器里的私有方法</h2>
 *
 * <p>这个前缀在过滤链上是 {@code permitAll}（见 {@code SecurityConfig}），
 * 也就是说**唯一的闸门就是这段代码**。它原来长在 {@code EffectivePolicyController}
 * 的私有方法里，只有那一个 handler 会调用；再加一个 handler 时，
 * 忘了调用不会有任何报错，只会静默放行。
 *
 * <p>所以它现在由 {@link PolicyClientAuthInterceptor} 统一施加——
 * 新 handler 不需要"记得"做任何事。
 *
 * <h2>两种凭据都认</h2>
 *
 * <ol>
 *   <li><b>Bearer</b>（首选）：client_credentials 换来的令牌，scope 必须含
 *       {@code iam.principal}。调用方（ask）走的就是这条——它本来就要拿令牌，
 *       不该再维护一份 Basic 凭证</li>
 *   <li><b>Basic</b>：客户端 id/secret 直连。留给运维手工排查用（curl 一条命令即可）</li>
 * </ol>
 *
 * <p>两条路都必须带 {@code iam.principal} scope，判据一致。
 */
@Component
public class IamServiceClientAuth {

    private static final Logger log = LoggerFactory.getLogger(IamServiceClientAuth.class);

    /** 服务间调用所需的 scope。缺了它，任何拿到过令牌的客户端都能读全量策略。 */
    private static final String REQUIRED_SCOPE = "iam.principal";

    private final RegisteredClientRepository clients;
    private final PasswordEncoder passwordEncoder;
    private final OAuth2AuthorizationService authorizations;

    public IamServiceClientAuth(RegisteredClientRepository clients,
                                PasswordEncoder passwordEncoder,
                                OAuth2AuthorizationService authorizations) {
        this.clients = clients;
        this.passwordEncoder = passwordEncoder;
        this.authorizations = authorizations;
    }

    public boolean allowed(String authorization) {
        if (authorization == null) {
            return false;
        }
        if (authorization.startsWith("Bearer ")) {
            return bearerAllowed(authorization.substring("Bearer ".length()).trim());
        }
        if (authorization.startsWith("Basic ")) {
            return basicAllowed(authorization);
        }
        return false;
    }

    private boolean bearerAllowed(String token) {
        OAuth2Authorization auth = authorizations.findByToken(token, OAuth2TokenType.ACCESS_TOKEN);
        if (auth == null) {
            return false;
        }
        OAuth2Authorization.Token<OAuth2AccessToken> accessToken = auth.getAccessToken();
        if (accessToken == null || accessToken.getToken() == null || accessToken.isInvalidated()) {
            return false;
        }
        Instant expiresAt = accessToken.getToken().getExpiresAt();
        if (expiresAt != null && expiresAt.isBefore(Instant.now())) {
            return false;
        }
        return auth.getAuthorizedScopes().contains(REQUIRED_SCOPE);
    }

    /** Basic 认证 + 必须带 {@code iam.principal} scope。 */
    private boolean basicAllowed(String authorization) {
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(
                    authorization.substring("Basic ".length()).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        int sep = decoded.indexOf(':');
        if (sep <= 0) {
            return false;
        }
        String clientId = decoded.substring(0, sep);
        String secret = decoded.substring(sep + 1);

        RegisteredClient client = clients.findByClientId(clientId);
        if (client == null || client.getClientSecret() == null) {
            return false;
        }
        boolean matches = passwordEncoder.matches(secret, client.getClientSecret());
        if (!matches) {
            log.warn("策略接口客户端凭证不匹配：client={}", clientId);
            return false;
        }
        return client.getScopes().contains(REQUIRED_SCOPE);
    }
}

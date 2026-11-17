package com.liusongboyy.chatdata.iam.serviceauth;

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
 * 服务间调用前缀的客户端凭证校验（{@code /iam/policy/**}、{@code /iam/identity/**}）。
 *
 * <h2>为什么是独立的组件，而不是控制器里的私有方法</h2>
 *
 * <p>这些前缀在过滤链上是 {@code permitAll}（见 {@code SecurityConfig}），
 * 也就是说**唯一的闸门就是这段代码**。它原来长在 {@code EffectivePolicyController}
 * 的私有方法里，只有那一个 handler 会调用；再加一个 handler 时，
 * 忘了调用不会有任何报错，只会静默放行。
 *
 * <p>所以它现在由 {@link ClientCredentialInterceptor} 统一施加——
 * 新 handler 不需要"记得"做任何事。
 *
 * <h2>两种凭据都认</h2>
 *
 * <ol>
 *   <li><b>Bearer</b>（首选）：client_credentials 换来的令牌。调用方（ask、channel）
 *       走的就是这条——它本来就要拿令牌，不该再维护一份 Basic 凭证</li>
 *   <li><b>Basic</b>：客户端 id/secret 直连。留给运维手工排查用（curl 一条命令即可）</li>
 * </ol>
 *
 * <p>两条路都按**同一个 scope**判，判据一致。
 *
 * <h2>scope 按前缀给，不是一个 scope 打天下</h2>
 *
 * <p>不同前缀暴露的东西差别很大：
 *
 * <table>
 *   <tr><th>前缀</th><th>scope</th><th>暴露什么</th></tr>
 *   <tr><td>{@code /iam/policy/**}</td><td>{@code iam.principal}</td>
 *       <td>全量角色目录与用户目录</td></tr>
 *   <tr><td>{@code /iam/identity/**}</td><td>{@code iam.identity}</td>
 *       <td>单个外部 id 的绑定结果</td></tr>
 * </table>
 *
 * <p>共用一个 scope 的话，渠道（只需要 identity）会被迫拿 {@code iam.principal}，
 * 顺带能读全量用户目录——那是**用不上的权限**。给多了不是"方便"，
 * 是让一次密钥泄露的后果变大。
 */
@Component
public class IamServiceClientAuth {

    private static final Logger log = LoggerFactory.getLogger(IamServiceClientAuth.class);

    /** 读全量策略目录所需。 */
    public static final String SCOPE_PRINCIPAL = "iam.principal";

    /** 解析外部身份所需。渠道用，比 {@link #SCOPE_PRINCIPAL} 窄得多。 */
    public static final String SCOPE_IDENTITY = "iam.identity";

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

    /**
     * @param authorization 请求头
     * @param requiredScope 这个前缀要求的 scope，见类注释
     */
    public boolean allowed(String authorization, String requiredScope) {
        if (authorization == null) {
            return false;
        }
        if (authorization.startsWith("Bearer ")) {
            return bearerAllowed(authorization.substring("Bearer ".length()).trim(), requiredScope);
        }
        if (authorization.startsWith("Basic ")) {
            return basicAllowed(authorization, requiredScope);
        }
        return false;
    }

    private boolean bearerAllowed(String token, String requiredScope) {
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
        return auth.getAuthorizedScopes().contains(requiredScope);
    }

    /** Basic 认证 + 客户端必须注册过这个 scope。 */
    private boolean basicAllowed(String authorization, String requiredScope) {
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
        return client.getScopes().contains(requiredScope);
    }
}

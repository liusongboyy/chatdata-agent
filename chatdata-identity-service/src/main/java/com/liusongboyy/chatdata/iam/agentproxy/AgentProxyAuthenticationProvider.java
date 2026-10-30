package com.liusongboyy.chatdata.iam.agentproxy;

import com.liusongboyy.chatdata.iam.config.IamProperties;
import com.liusongboyy.chatdata.iam.user.UserMapper;
import com.liusongboyy.chatdata.iam.user.UserRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 代理换令牌。
 *
 * <h2>它和渠道 grant 的区别</h2>
 *
 * <p>渠道判的是「这个飞书号绑定的是谁」，agent 代理判的是
 * 「这个人同意哪个 agent 代表他」。后者来自 {@code iam_agent_grant}，
 * 是**用户自己的授权**，不是一次性的绑定事实。
 *
 * <h2>三道校验</h2>
 *
 * <ol>
 *   <li><b>客户端必须是配置里的 agent-mesh 客户端。</b>光有 grant type 不够——
 *       任何客户端都能在注册时声明它。</li>
 *   <li><b>用户存在、启用，且授权表里有 (user, agent) 且未撤销。</b>
 *       这是唯一的真边界：没有它，任何能调通 A2A 端点的人说出管理员用户名
 *       就能以他身份查数。</li>
 *   <li><b>agent_client 必须与请求者声明的一致。</b>
 *       用户可能只授权了「周报助手」，不能让「随便什么 agent」借它的名义。</li>
 * </ol>
 *
 * <h2>审计</h2>
 *
 * <p>授权记录的 {@code principal_name} 是**被代理的用户**，不是 agent。
 * 否则 {@code oauth2_authorization} 里所有代理令牌都记在同一个 agent 名下，
 * 出问题查不到是替谁查的。同时把 {@code agent_client} 记进 attributes——
 * 「谁替谁查的」两个都要有。
 */
public class AgentProxyAuthenticationProvider implements AuthenticationProvider {

    private static final Logger log =
            LoggerFactory.getLogger(AgentProxyAuthenticationProvider.class);

    private static final String ERROR_URI =
            "https://datatracker.ietf.org/doc/html/rfc6749#section-5.2";

    /** 授权记录里记「是哪个 agent 代理的」。排查时和 principal_name 一起看。 */
    static final String ATTR_AGENT_CLIENT = "agent_client";

    private final OAuth2AuthorizationService authorizationService;
    private final OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator;
    private final UserMapper users;
    private final IamProperties properties;

    public AgentProxyAuthenticationProvider(OAuth2AuthorizationService authorizationService,
                                            OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator,
                                            UserMapper users,
                                            IamProperties properties) {
        this.authorizationService = authorizationService;
        this.tokenGenerator = tokenGenerator;
        this.users = users;
        this.properties = properties;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return AgentProxyGrantAuthenticationToken.class.isAssignableFrom(authentication);
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        AgentProxyGrantAuthenticationToken request =
                (AgentProxyGrantAuthenticationToken) authentication;

        // ① 客户端必须是 agent-mesh 客户端
        OAuth2ClientAuthenticationToken clientPrincipal =
                ClientPrincipal.of(request);
        RegisteredClient client = clientPrincipal.getRegisteredClient();

        String expectedId = properties.getClients().getAgentMeshId();
        if (client == null || !expectedId.equals(client.getClientId())) {
            log.warn("Agent 代理换令牌被拒：客户端不是 mesh 客户端。clientId={}",
                    client == null ? "null" : client.getClientId());
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.UNAUTHORIZED_CLIENT,
                    "该客户端无权使用 agent 代理授权类型", ERROR_URI));
        }
        if (!client.getAuthorizationGrantTypes().contains(AgentProxyGrantType.AGENT_PROXY)) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.UNAUTHORIZED_CLIENT, "客户端未注册该授权类型", ERROR_URI));
        }

        // ② 用户存在、启用，且授权表里有 (user, agent) 且未撤销
        String agentClient = request.getAgentClient();
        String username = request.getProxyUser();
        UserRow user = users.selectAgentProxiedUser(properties.getTenant(), username, agentClient);
        if (user == null) {
            // 不区分「用户不存在」与「没授权」——区分了就是一个账号探测接口
            log.warn("Agent 代理换令牌被拒：无有效授权。agent={} user={}", agentClient, username);
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_GRANT,
                    "该用户未授权此 agent 代表其查询", ERROR_URI));
        }

        // ③ 用户启用
        if (!user.active()) {
            log.warn("Agent 代理换令牌被拒：账号已停用。agent={} user={}", agentClient, username);
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_GRANT, "账号已停用", ERROR_URI));
        }

        Set<String> authorizedScopes = resolveScopes(client, request);

        // 令牌的 principal 是**被代理的用户**，不是 agent。
        // 授权记录的 principal_name 取自它——记成 agent 的话，
        // 审计里全是 agent 名，"替谁查的"就查不出来了
        Authentication principal = UsernamePasswordAuthenticationToken.authenticated(
                user.username(), null, List.of(new SimpleGrantedAuthority("ROLE_AGENT_PROXY")));

        OAuth2TokenContext tokenContext = DefaultOAuth2TokenContext.builder()
                .registeredClient(client)
                .principal(principal)
                .authorizationServerContext(AuthorizationServerContextHolder.getContext())
                .authorizedScopes(authorizedScopes)
                .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                .authorizationGrantType(AgentProxyGrantType.AGENT_PROXY)
                .authorizationGrant(request)
                .build();

        OAuth2Token generated = tokenGenerator.generate(tokenContext);
        if (generated == null) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.SERVER_ERROR, "令牌生成失败", ERROR_URI));
        }
        OAuth2AccessToken accessToken = AccessTokens.rebuild(generated, tokenContext);

        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .principalName(user.username())
                .authorizationGrantType(AgentProxyGrantType.AGENT_PROXY)
                .authorizedScopes(authorizedScopes)
                .attribute(java.security.Principal.class.getName(), principal)
                // 「谁替谁查的」两个都要有：principal 是被代理的人，这里记 agent
                .attribute(ATTR_AGENT_CLIENT, agentClient)
                .token(accessToken, metadata -> metadata.put(
                        org.springframework.security.oauth2.server.authorization.settings
                                .OAuth2TokenFormat.class.getName(),
                        client.getTokenSettings().getAccessTokenFormat().getValue()))
                .build();
        authorizationService.save(authorization);

        log.info("Agent 代理换令牌：agent={} user={} scopes={}", agentClient, user.username(),
                authorizedScopes);

        Map<String, Object> additional = new HashMap<>();
        additional.put(ATTR_AGENT_CLIENT, agentClient);
        return new OAuth2AccessTokenAuthenticationToken(
                client, clientPrincipal, accessToken, null, additional);
    }

    /**
     * 请求的 scope 必须是客户端注册过的子集；没请求就用客户端的全部 scope。
     *
     * <p>不校验的话，客户端能申请任意 scope 而资源服务器按 scope 判能力——
     * 等于自己给自己提权。
     */
    private static Set<String> resolveScopes(RegisteredClient client,
                                             AgentProxyGrantAuthenticationToken request) {
        Object requested = request.getAdditionalParameters().get(OAuth2ParameterNames.SCOPE);
        if (requested instanceof String s && !s.isBlank()) {
            Set<String> out = new LinkedHashSet<>();
            for (String scope : s.trim().split("\\s+")) {
                if (!client.getScopes().contains(scope)) {
                    throw new OAuth2AuthenticationException(new OAuth2Error(
                            OAuth2ErrorCodes.INVALID_SCOPE, "未授权的 scope：" + scope, ERROR_URI));
                }
                out.add(scope);
            }
            return out;
        }
        return new LinkedHashSet<>(client.getScopes());
    }

    /** 客户端身份校验，与 Spring 内部的 {@code OAuth2AuthenticationProviderUtils} 等价。 */
    private static final class ClientPrincipal {
        private ClientPrincipal() {
        }

        static OAuth2ClientAuthenticationToken of(Authentication authentication) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof OAuth2ClientAuthenticationToken token && token.isAuthenticated()) {
                return token;
            }
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_CLIENT, "客户端认证失败", ERROR_URI));
        }
    }

    /**
     * 令牌重建。
     *
     * <p><b>必须重建，且键必须是 {@code OAuth2AccessToken.class}。</b>
     * {@code OAuth2Authorization.Builder} 以 {@code token.getClass()} 为键存 token，
     * 而生成器返回的是内部子类 {@code OAuth2AccessTokenClaims}——原样放进去后，
     * {@code JdbcOAuth2AuthorizationService} 按父类取不到，落库时
     * {@code access_token_value} 全为 NULL。
     *
     * <p>症状极具迷惑性：换令牌返回 200 并带回 token 字符串，
     * 但拿它调任何资源服务器都是 401，而 IAM 日志里没有任何异常。
     * （channel 那边踩过同一个坑，见 ChannelGrantAuthenticationProvider。）
     */
    private static final class AccessTokens {
        private AccessTokens() {
        }

        static OAuth2AccessToken rebuild(OAuth2Token generated, OAuth2TokenContext context) {
            if (!(generated instanceof OAuth2AccessToken source)) {
                throw new OAuth2AuthenticationException(new OAuth2Error(
                        OAuth2ErrorCodes.SERVER_ERROR,
                        "令牌生成器返回了非 access token 类型："
                                + generated.getClass().getSimpleName(), ERROR_URI));
            }
            OAuth2AccessToken.TokenType type = OAuth2AccessToken.TokenType.BEARER;
            if (source instanceof org.springframework.security.oauth2.core.ClaimAccessor claims) {
                Map<String, Object> cnf = claims.getClaimAsMap("cnf");
                if (cnf != null && cnf.containsKey("jkt")) {
                    type = OAuth2AccessToken.TokenType.DPOP;
                }
            }
            return new OAuth2AccessToken(type, source.getTokenValue(), source.getIssuedAt(),
                    source.getExpiresAt(), context.getAuthorizedScopes());
        }
    }
}

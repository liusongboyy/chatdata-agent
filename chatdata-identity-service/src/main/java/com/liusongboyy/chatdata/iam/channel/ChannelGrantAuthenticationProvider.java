package com.liusongboyy.chatdata.iam.channel;

import com.liusongboyy.chatdata.iam.config.IamProperties;
import com.liusongboyy.chatdata.iam.user.UserMapper;
import com.liusongboyy.chatdata.iam.user.UserRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 渠道换令牌。
 *
 * <h2>它换出的是什么</h2>
 *
 * <p>一张<b>正常的不透明 access token</b>，与浏览器登录 → 网关 BFF 拿到的完全同构。
 * ask 用它内省时看到的是目标用户的真实角色与组织——ask 侧完全不知道
 * 这次请求来自飞书。
 *
 * <h2>安全边界（本类的全部要害）</h2>
 *
 * <p>三道校验，缺一不可：
 *
 * <ol>
 *   <li><b>客户端必须是配置里的 channel 客户端。</b>光有 grant type 不够——
 *       任何客户端都能在注册时声明这个 grant，所以还要比对 clientId。</li>
 *   <li><b>目标用户确实绑定了本渠道。</b>按 {@code (provider, username)} 反查
 *       {@code iam_user.external_provider/external_id}。没有这条，一个泄露的
 *       client secret 就是一把能冒充任何人的万能钥匙。</li>
 *   <li><b>目标用户是启用的。</b>停用账号换到令牌也没用（ask 侧会拒），
 *       但在这里拒能给出更准确的错误，且不产生一条无意义的授权记录。</li>
 * </ol>
 *
 * <h2>为什么不签发 refresh token</h2>
 *
 * <p>渠道每次消息都现场换一次令牌（配合本地缓存，实际开销很小）。
 * 发 refresh token 就要处理轮换、失效、并发刷新——这些都是浏览器会话才需要的东西，
 * 渠道不需要，少一条链路就少一处漏洞。
 *
 * <h2>审计</h2>
 *
 * <p>授权记录的 {@code principal_name} 是<b>目标用户</b>，不是 bot。
 * 否则 {@code oauth2_authorization} 里所有渠道令牌都记在同一个名字下，
 * 出问题查不到是谁。
 */
public class ChannelGrantAuthenticationProvider implements AuthenticationProvider {

    private static final Logger log = LoggerFactory.getLogger(ChannelGrantAuthenticationProvider.class);

    private static final String ERROR_URI =
            "https://datatracker.ietf.org/doc/html/rfc6749#section-5.2";

    private final OAuth2AuthorizationService authorizationService;
    private final OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator;
    private final UserMapper users;
    private final IamProperties properties;

    public ChannelGrantAuthenticationProvider(OAuth2AuthorizationService authorizationService,
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
        return ChannelGrantAuthenticationToken.class.isAssignableFrom(authentication);
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        ChannelGrantAuthenticationToken request = (ChannelGrantAuthenticationToken) authentication;

        OAuth2ClientAuthenticationToken clientPrincipal =
                OAuth2ClientAuth.getAuthenticatedClient(request);
        RegisteredClient client = clientPrincipal.getRegisteredClient();

        // ① 客户端必须是 channel 客户端
        String expectedId = properties.getClients().getChannelId();
        if (client == null || !expectedId.equals(client.getClientId())) {
            log.warn("渠道换令牌被拒：客户端不是 channel 客户端。clientId={}",
                    client == null ? "null" : client.getClientId());
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.UNAUTHORIZED_CLIENT,
                    "该客户端无权使用渠道授权类型", ERROR_URI));
        }

        if (!client.getAuthorizationGrantTypes().contains(ChannelGrantType.CHANNEL)) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.UNAUTHORIZED_CLIENT, "客户端未注册该授权类型", ERROR_URI));
        }

        // ② 目标用户必须绑定了本渠道
        String channel = request.getChannel();
        String username = request.getUsername();
        UserRow user = users.selectBoundToChannel(properties.getTenant(), username, channel);
        if (user == null) {
            // 不区分「用户不存在」与「没绑定」：那会给攻击者一个探测账号的接口
            log.warn("渠道换令牌被拒：用户未绑定该渠道。channel={} user={}", channel, username);
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_GRANT,
                    "该用户未绑定到此渠道", ERROR_URI));
        }

        // ③ 目标用户是启用的
        if (!user.active()) {
            log.warn("渠道换令牌被拒：账号已停用。channel={} user={}", channel, username);
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_GRANT, "账号已停用", ERROR_URI));
        }

        Set<String> authorizedScopes = resolveScopes(client, request);

        // 令牌的 principal 是**目标用户**，不是 bot 客户端。
        // 授权记录的 principal_name 取自它——记成 bot 的话，审计里全是同一个名字
        Authentication principal = UsernamePasswordAuthenticationToken.authenticated(
                user.username(), null, List.of(new SimpleGrantedAuthority("ROLE_CHANNEL")));

        OAuth2TokenContext tokenContext = DefaultOAuth2TokenContext.builder()
                .registeredClient(client)
                .principal(principal)
                .authorizationServerContext(AuthorizationServerContextHolder.getContext())
                .authorizedScopes(authorizedScopes)
                .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                .authorizationGrantType(ChannelGrantType.CHANNEL)
                .authorizationGrant(request)
                .build();

        OAuth2Token generated = tokenGenerator.generate(tokenContext);
        if (generated == null) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.SERVER_ERROR, "令牌生成失败", ERROR_URI));
        }

        OAuth2AccessToken accessToken = asAccessToken(generated);

        // 不签发 refresh token：渠道每次现场换，见类注释
        OAuth2Authorization.Builder authorizationBuilder = OAuth2Authorization
                .withRegisteredClient(client)
                .principalName(user.username())
                .authorizationGrantType(ChannelGrantType.CHANNEL)
                .authorizedScopes(authorizedScopes)
                .attribute(java.security.Principal.class.getName(), principal)
                // 记下是哪个渠道换的——排查"这个令牌哪来的"时需要
                .attribute("channel", channel);
        registerAccessToken(authorizationBuilder, accessToken, tokenContext);
        OAuth2Authorization authorization = authorizationBuilder.build();
        authorizationService.save(authorization);

        log.info("渠道换令牌：channel={} user={} scopes={}", channel, user.username(), authorizedScopes);

        Map<String, Object> additional = new HashMap<>();
        additional.put("channel", channel);
        return new OAuth2AccessTokenAuthenticationToken(
                client, clientPrincipal, accessToken,
                /* refreshToken */ (OAuth2RefreshToken) null, additional);
    }

    /**
     * 请求的 scope 必须是客户端注册过的子集；没请求就用客户端的全部 scope。
     *
     * <p>不校验会怎样：客户端能用 token endpoint 申请任意 scope，
     * 而资源服务器按 scope 判能力——等于自己给自己提权。
     */
    private static Set<String> resolveScopes(RegisteredClient client,
                                             ChannelGrantAuthenticationToken request) {
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

    /** 生成器可能产出 Jwt（SELF_CONTAINED）。本项目全是 REFERENCE，走到了就是配置错了。 */
    private static OAuth2AccessToken asAccessToken(OAuth2Token generated) {
        if (generated instanceof OAuth2AccessToken accessToken) {
            return accessToken;
        }
        throw new OAuth2AuthenticationException(new OAuth2Error(
                OAuth2ErrorCodes.SERVER_ERROR,
                "令牌生成器返回了非 access token 类型：" + generated.getClass().getSimpleName(),
                ERROR_URI));
    }

    /**
     * 把令牌写进 authorization，<b>键必须是 {@code OAuth2AccessToken.class}</b>。
     *
     * <p>{@code OAuth2Authorization.Builder#token(T)} 以 {@code token.getClass()} 为键，
     * 而生产生成器（{@code OAuth2AccessTokenGenerator}）返回的是内部子类
     * {@code OAuth2AccessTokenClaims}。若原样放进去，键就成了那个子类，
     * {@code JdbcOAuth2AuthorizationService} 按 {@code OAuth2AccessToken.class} 取不到——
     * 落库时 {@code access_token_value} 全为 NULL。
     *
     * <p>症状极具迷惑性：换令牌返回 200 且带回 token 字符串，
     * 但拿它调任何资源服务器都是 401，而 IAM 日志里没有任何异常。
     *
     * <p>所以这里按 Spring 内部 {@code OAuth2AuthenticationProviderUtils.accessToken}
     * 的做法<b>重建</b>一个 {@code OAuth2AccessToken}</b>，并把子类的 claims
     * 作为元数据带上——重建是为了让键正确，claims 是为了不丢生成器写入的声明。
     */
    private static void registerAccessToken(OAuth2Authorization.Builder builder,
                                            OAuth2AccessToken generated,
                                            OAuth2TokenContext context) {
        OAuth2AccessToken.TokenType tokenType = OAuth2AccessToken.TokenType.BEARER;
        if (generated instanceof org.springframework.security.oauth2.core.ClaimAccessor claims) {
            Map<String, Object> cnf = claims.getClaimAsMap("cnf");
            if (cnf != null && cnf.containsKey("jkt")) {
                tokenType = OAuth2AccessToken.TokenType.DPOP;
            }
        }
        OAuth2AccessToken token = new OAuth2AccessToken(
                tokenType,
                generated.getTokenValue(),
                generated.getIssuedAt(),
                generated.getExpiresAt(),
                context.getAuthorizedScopes());

        builder.token(token, metadata -> {
            if (generated instanceof org.springframework.security.oauth2.core.ClaimAccessor claims) {
                metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME, claims.getClaims());
            }
            metadata.put(OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, false);
            metadata.put(org.springframework.security.oauth2.server.authorization.settings
                            .OAuth2TokenFormat.class.getName(),
                    context.getRegisteredClient().getTokenSettings()
                            .getAccessTokenFormat().getValue());
        });
    }

    /** 客户端身份校验的公共逻辑，与 Spring 内部的 {@code OAuth2AuthenticationProviderUtils} 等价。 */
    private static final class OAuth2ClientAuth {
        private OAuth2ClientAuth() {
        }

        static OAuth2ClientAuthenticationToken getAuthenticatedClient(Authentication authentication) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof OAuth2ClientAuthenticationToken client) {
                if (client.isAuthenticated()) {
                    return client;
                }
            }
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_CLIENT, "客户端认证失败", ERROR_URI));
        }
    }

    /** 未使用的授权类型常量引用，保留以便将来扩展。 */
    @SuppressWarnings("unused")
    private static final AuthorizationGrantType GRANT = ChannelGrantType.CHANNEL;
}

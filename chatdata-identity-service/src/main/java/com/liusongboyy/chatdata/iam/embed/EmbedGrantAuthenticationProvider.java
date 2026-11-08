package com.liusongboyy.chatdata.iam.embed;

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

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 嵌入式问数换令牌。
 *
 * <h2>换出来的是什么</h2>
 *
 * <p>一张<b>短时、限助理</b>的不透明令牌。ask 用它内省时看到的是目标用户的
 * 真实角色与组织——<b>权限一点不少</b>，只是能问的助理被限死。
 *
 * <h2>三道校验，缺一不可</h2>
 *
 * <ol>
 *   <li><b>客户端必须是 embed 客户端。</b>光有 grant type 不够——任何客户端
 *       都能在注册时声明这个 grant，所以还要比对 clientId 与
 *       {@code iam_embed_client} 白名单</li>
 *   <li><b>{@code user_ref} 必须能映射到本系统账号。</b>按
 *       {@code iam_user.external_provider='embed' + external_id=user_ref} 反查。
 *       查不到就拒——<b>绝不自动建号</b>：那会让第三方凭空污染账号体系</li>
 *   <li><b>助理必须属于这个客户端。</b>见 {@link EmbedClientMapper}——
 *       一个宿主只能问它被授权的助理</li>
 * </ol>
 *
 * <h2>为什么令牌里要带 assistant</h2>
 *
 * <p>宿主系统拿到的令牌如果和普通用户令牌同构，那它就能拿这张令牌去问<b>任何</b>域。
 * 而 CRM 只该看 CRM 那个域的数据。
 *
 * <p>约束通过令牌属性 {@code embed_assistant} 传递，由
 * {@code /iam/principal} 透出，ask 侧在 {@code forAssistant} 处强制校验。
 * <b>三道都要在服务端做</b>：只在前端限制等于没限制。
 *
 * <h2>为什么不签发 refresh token</h2>
 *
 * <p>5 分钟的有效期，只够换一次会话。要更长时间就重新换一张——
 * 而"重新换"意味着宿主系统必须再出示一次 client_secret，
 * 那正是我们想要的频率：<b>每次都要证明自己是那个宿主</b>。
 */
public class EmbedGrantAuthenticationProvider implements AuthenticationProvider {

    private static final Logger log =
            LoggerFactory.getLogger(EmbedGrantAuthenticationProvider.class);

    private static final String ERROR_URI =
            "https://datatracker.ietf.org/doc/html/rfc6749#section-5.2";

    /**
     * 令牌有效期。
     *
     * <p><b>5 分钟是刻意的短。</b>嵌入组件拿它换一次会话，之后靠会话。
     * 泄露的窗口因此只有 5 分钟，而不是普通令牌的 30 分钟。
     */
    static final Duration TOKEN_TTL = Duration.ofMinutes(5);

    /** 令牌属性名：限定的助理。ask 侧按它强制校验。 */
    public static final String ATTR_ASSISTANT = "embed_assistant";

    /** 令牌属性名：是哪个宿主系统换的。审计与排查用。 */
    public static final String ATTR_CLIENT = "embed_client";

    private final OAuth2AuthorizationService authorizationService;
    private final OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator;
    private final UserMapper users;
    private final EmbedClientMapper embedClients;
    private final IamProperties properties;

    public EmbedGrantAuthenticationProvider(OAuth2AuthorizationService authorizationService,
                                            OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator,
                                            UserMapper users,
                                            EmbedClientMapper embedClients,
                                            IamProperties properties) {
        this.authorizationService = authorizationService;
        this.tokenGenerator = tokenGenerator;
        this.users = users;
        this.embedClients = embedClients;
        this.properties = properties;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return EmbedGrantAuthenticationToken.class.isAssignableFrom(authentication);
    }

    @Override
    public Authentication authenticate(Authentication authentication)
            throws AuthenticationException {
        EmbedGrantAuthenticationToken request = (EmbedGrantAuthenticationToken) authentication;

        OAuth2ClientAuthenticationToken clientPrincipal = EmbedClientAuth.getAuthenticatedClient(request);
        RegisteredClient client = clientPrincipal.getRegisteredClient();

        // ① 客户端必须是 embed 客户端
        if (client == null) {
            throw invalidClient("客户端未认证");
        }
        if (!client.getAuthorizationGrantTypes().contains(
                new org.springframework.security.oauth2.core.AuthorizationGrantType(EmbedGrantType.VALUE))) {
            log.warn("embed 换令牌被拒：客户端未注册该 grant。clientId={}", client.getClientId());
            throw invalidClient("客户端未注册该授权类型");
        }

        String assistant = request.assistant();
        String userRef = request.userRef();

        // ② 这个宿主能不能问这个助理
        if (!embedClients.allows(client.getClientId(), assistant)) {
            // 不区分"助理不存在"与"没授权"：那会给攻击者一个探测助理的接口
            log.warn("embed 换令牌被拒：宿主无权使用该助理。client={} assistant={}",
                    client.getClientId(), assistant);
            throw invalidGrant("该宿主无权使用此助理");
        }

        // ③ user_ref 必须能映射到本系统账号
        UserRow user = users.selectByExternalIdentity(properties.getTenant(), "embed", userRef);
        if (user == null) {
            // **不自动建号。** 自动建号会让第三方凭空造出一个账号，
            // 而那个账号有它自己的一套权限——等于账号体系被第三方污染
            log.warn("embed 换令牌被拒：user_ref 映射不到本系统账号。client={} userRef={}",
                    client.getClientId(), userRef);
            throw invalidGrant("该用户未开通嵌入访问，请联系管理员");
        }

        // ④ 目标用户是启用的
        if (!user.active()) {
            log.warn("embed 换令牌被拒：账号已停用。user={}", user.username());
            throw invalidGrant("账号已停用");
        }

        Set<String> authorizedScopes = resolveScopes(client, request);

        Authentication principal = UsernamePasswordAuthenticationToken.authenticated(
                user.username(), null, List.of(new SimpleGrantedAuthority("ROLE_EMBED")));

        OAuth2TokenContext tokenContext = DefaultOAuth2TokenContext.builder()
                .registeredClient(client)
                .principal(principal)
                .authorizationServerContext(AuthorizationServerContextHolder.getContext())
                .authorizedScopes(authorizedScopes)
                .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                .authorizationGrantType(
                        new org.springframework.security.oauth2.core.AuthorizationGrantType(
                                EmbedGrantType.VALUE))
                .authorizationGrant(request)
                .build();

        OAuth2Token generated = tokenGenerator.generate(tokenContext);
        if (generated == null) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.SERVER_ERROR, "令牌生成失败", ERROR_URI));
        }
        OAuth2AccessToken accessToken = asAccessToken(generated, tokenContext);

        OAuth2Authorization.Builder builder = OAuth2Authorization
                .withRegisteredClient(client)
                .principalName(user.username())
                .authorizationGrantType(
                        new org.springframework.security.oauth2.core.AuthorizationGrantType(
                                EmbedGrantType.VALUE))
                .authorizedScopes(authorizedScopes)
                .attribute(java.security.Principal.class.getName(), principal)
                // **这两条是安全边界，不是元数据。** ask 侧靠它们限制范围
                .attribute(ATTR_ASSISTANT, assistant)
                .attribute(ATTR_CLIENT, client.getClientId());

        registerAccessToken(builder, accessToken, tokenContext);
        authorizationService.save(builder.build());

        log.info("embed 换令牌：client={} user={} assistant={} scopes={}",
                client.getClientId(), user.username(), assistant, authorizedScopes);

        Map<String, Object> additional = new HashMap<>();
        additional.put(ATTR_ASSISTANT, assistant);
        additional.put(ATTR_CLIENT, client.getClientId());
        return new OAuth2AccessTokenAuthenticationToken(
                client, clientPrincipal, accessToken, null, additional);
    }

    /**
     * 强制 5 分钟有效期。
     *
     * <p>令牌生成器按客户端的 {@code accessTokenTimeToLive} 来，
     * 而那个值对同一客户端的所有 grant 是共享的——不能为了 embed 把它改短，
     * 那会影响这个客户端的其他用途。
     *
     * <p>所以这里重建一个 AccessToken，把 expiresAt 压到 {@link #TOKEN_TTL}。
     * 与 channel provider 的 {@code asAccessToken} 同一个理由：需要改写生成器的产出。
     */
    private OAuth2AccessToken asAccessToken(OAuth2Token generated,
                                            OAuth2TokenContext context) {
        Instant now = Instant.now();
        Instant maxExpiry = now.plus(TOKEN_TTL);
        Instant issued = now;
        Instant expires = maxExpiry;
        if (generated instanceof OAuth2AccessToken t) {
            issued = t.getIssuedAt() == null ? now : t.getIssuedAt();
            if (t.getExpiresAt() != null && t.getExpiresAt().isBefore(maxExpiry)) {
                expires = t.getExpiresAt();
            }
            return new OAuth2AccessToken(t.getTokenType(), t.getTokenValue(), issued, expires,
                    t.getScopes());
        }
        throw new OAuth2AuthenticationException(new OAuth2Error(
                OAuth2ErrorCodes.SERVER_ERROR, "令牌类型异常", ERROR_URI));
    }

    /** 请求的 scope 必须是客户端注册过的子集。理由同 channel provider。 */
    private static Set<String> resolveScopes(RegisteredClient client,
                                             EmbedGrantAuthenticationToken request) {
        Object requested = request.getAdditionalParameters().get(OAuth2ParameterNames.SCOPE);
        if (requested instanceof String s && !s.isBlank()) {
            Set<String> out = new LinkedHashSet<>();
            for (String scope : s.trim().split("\\s+")) {
                if (!client.getScopes().contains(scope)) {
                    throw new OAuth2AuthenticationException(new OAuth2Error(
                            OAuth2ErrorCodes.INVALID_SCOPE, "请求了未注册的 scope：" + scope,
                            ERROR_URI));
                }
                out.add(scope);
            }
            return out;
        }
        return client.getScopes();
    }

    /**
     * 注册 access token。
     *
     * <p>照 Spring 内部 {@code OAuth2AuthenticationProviderUtils.accessToken} 的做法
     * <b>重建</b>一个 {@code OAuth2AccessToken}：直接用生成器的产出会让
     * {@code access_token_value} 落库为 NULL——症状极具迷惑性，
     * 换令牌返回 200 且带回 token 字符串，但拿它调任何资源服务器都是 401，
     * 而 IAM 日志里没有任何异常。
     *
     * <p>这段与 {@code ChannelGrantAuthenticationProvider} 里同名方法几乎一样。
     * **没有抽成公共工具**：它是一个 Spring 内部的私有做法，跟着版本变；
     * 抽出来之后一旦 Spring 改了，就得在一个地方同时兼容两个 grant 的行为差异。
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
                tokenType, generated.getTokenValue(),
                generated.getIssuedAt(), generated.getExpiresAt(),
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

    private static OAuth2AuthenticationException invalidClient(String msg) {
        return new OAuth2AuthenticationException(new OAuth2Error(
                OAuth2ErrorCodes.UNAUTHORIZED_CLIENT, msg, ERROR_URI));
    }

    private static OAuth2AuthenticationException invalidGrant(String msg) {
        return new OAuth2AuthenticationException(new OAuth2Error(
                OAuth2ErrorCodes.INVALID_GRANT, msg, ERROR_URI));
    }
}

package com.liusongboyy.chatdata.iam.config;

import com.liusongboyy.chatdata.iam.agentproxy.AgentProxyAuthenticationProvider;
import com.liusongboyy.chatdata.iam.agentproxy.AgentProxyGrantAuthenticationConverter;
import com.liusongboyy.chatdata.iam.channel.ChannelGrantAuthenticationConverter;
import com.liusongboyy.chatdata.iam.channel.ChannelGrantAuthenticationProvider;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.liusongboyy.chatdata.iam.config.IamProperties;
import com.liusongboyy.chatdata.iam.user.UserMapper;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

/**
 * 授权服务器的过滤链。
 *
 * <p>顺序必须在默认链之前：它用 {@code getEndpointsMatcher()} 精确匹配
 * {@code /oauth2/**} 与 {@code /.well-known/**}，其余请求交给默认链。
 *
 * <p>未认证时：浏览器（Accept: text/html）被重定向到 {@code /login}，
 * 由 {@code LoginController} 再转到 SPA 的 {@code /app/login}；
 * API 客户端拿到 401。
 */
@Configuration
public class AuthorizationServerSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            RegisteredClientRepository clients,
            OAuth2AuthorizationService authorizations,
            OAuth2AuthorizationConsentService consents,
            AuthorizationServerSettings settings,
            OAuth2TokenGenerator<?> tokenGenerator,
            SecurityContextRepository securityContextRepository,
            UserMapper userMapper,
            IamProperties iamProperties) throws Exception {

        OAuth2AuthorizationServerConfigurer configurer = new OAuth2AuthorizationServerConfigurer();

        // 配置必须在 with(...) 的回调里做：Security 7 的 configurer 在挂到
        // HttpSecurity 之前 getBuilder() 为 null，先调 setter 会抛
        // "securityBuilder cannot be null"
        http
                .securityMatcher(configurer.getEndpointsMatcher())
                .with(configurer, c -> c
                        .registeredClientRepository(clients)
                        .authorizationService(authorizations)
                        .authorizationConsentService(consents)
                        .authorizationServerSettings(settings)
                        .tokenGenerator(tokenGenerator)
                        .oidc(Customizer.withDefaults())
                        // 渠道换令牌（飞书等）。自定义 grant，理由见 ChannelGrantType
                        .tokenEndpoint(t -> t
                                .accessTokenRequestConverter(new ChannelGrantAuthenticationConverter())
                                .accessTokenRequestConverter(
                                        new AgentProxyGrantAuthenticationConverter())
                                .authenticationProvider(new ChannelGrantAuthenticationProvider(
                                        authorizations, tokenGenerator, userMapper, iamProperties))
                                // Agent 代理：判据是 iam_agent_grant（用户授权某个 agent），
                                // 与渠道的 external_id 绑定是两回事，所以两个 provider
                                .authenticationProvider(new AgentProxyAuthenticationProvider(
                                        authorizations, tokenGenerator, userMapper, iamProperties))))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository))
                // 令牌端点的调用方用客户端凭证认证，不用 CSRF token
                .csrf(csrf -> csrf.ignoringRequestMatchers(configurer.getEndpointsMatcher()))
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));

        return http.build();
    }

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * 令牌生成器。
     *
     * <p>访问令牌走哪个生成器**由客户端的 tokenSettings 决定**：
     * 本项目所有客户端都是 {@code OAuth2TokenFormat.REFERENCE}，
     * 因此实际签发的是不透明引用令牌；{@link JwtGenerator} 只是为将来的 OIDC id_token 备着。
     */
    @Bean
    public OAuth2TokenGenerator<?> tokenGenerator(JwtEncoder jwtEncoder) {
        return new DelegatingOAuth2TokenGenerator(
                new JwtGenerator(jwtEncoder),
                new OAuth2AccessTokenGenerator(),
                new OAuth2RefreshTokenGenerator());
    }
}

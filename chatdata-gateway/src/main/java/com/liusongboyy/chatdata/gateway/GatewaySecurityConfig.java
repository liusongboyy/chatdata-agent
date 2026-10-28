package com.liusongboyy.chatdata.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationEntryPoint;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.savedrequest.WebSessionServerRequestCache;
import org.springframework.web.server.WebFilter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import reactor.core.publisher.Mono;

/**
 * 网关的安全配置（reactive）。
 *
 * <h2>它做 BFF，不做资源服务器</h2>
 *
 * <p>浏览器只拿会话 Cookie；令牌存在网关的会话里（Redis），由 {@code TokenRelay}
 * 过滤器注入上游。**网关自己不校验业务权限**——那是 IAM 与 ask 的事。
 *
 * <h2>P1 阶段二：业务接口要登录，且写操作要 CSRF</h2>
 *
 * <p>ask 已切成资源服务器（校验 IAM 的不透明令牌）。因此这里：
 * {@code /api/**} 要求已登录，写请求校验 CSRF（Cookie 里的 {@code XSRF-TOKEN}
 * 回填到 {@code X-XSRF-TOKEN} 头）。{@code /sys/**} 的兼容路由已随本地认证一起删除。
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            ServerOAuth2AuthorizationRequestResolver authorizationRequestResolver) {
        return http
                .authorizeExchange(ex -> ex
                        .pathMatchers("/actuator/health", "/actuator/info").permitAll()
                        // 转发给下游的路由：下游各自鉴权（IAM 的登录/授权端点、ask 的旧令牌）
                        // 静态前端与 IAM 的端点：各自鉴权（IAM 的登录/授权端点）
                        .pathMatchers(
                                "/app/**",
                                "/iam/**", "/oauth2/**", "/.well-known/**",
                                "/login", "/captcha/**").permitAll()
                        // 业务接口：必须已登录。令牌由 TokenRelay 注入，ask 自己内省校验
                        .pathMatchers("/api/**").authenticated()
                        // BFF 自己的端点：登录入口与回调必须放行，主体查询要求已登录
                        .pathMatchers("/bff/login", "/bff/callback").permitAll()
                        .pathMatchers("/bff/me").authenticated()
                        .anyExchange().authenticated())
                .oauth2Login(oauth -> oauth
                        // WebFlux 的 oauth2Login 没有 redirectionEndpoint()，回调路径靠 matcher 指定。
                        // 它必须与注册的 redirect_uri 一致，否则登录过滤器不认这个回调。
                        .authenticationMatcher(ServerWebExchangeMatchers.pathMatchers(
                                HttpMethod.GET, "/bff/callback"))
                        .authorizationRequestResolver(authorizationRequestResolver)
                        // 带 request cache：深链接（如 /app/ask）在登录后能回到原处，
                        // 没有 saved request 时才落到 /app/
                        .authenticationSuccessHandler(successHandler()))
                .logout(logout -> logout
                        .logoutUrl("/bff/logout")
                        .logoutSuccessHandler((exchange, authentication) -> {
                            // WebFilterExchange 包了一层，响应对象在 getExchange() 里
                            var response = exchange.getExchange().getResponse();
                            response.setStatusCode(HttpStatus.NO_CONTENT);
                            return response.setComplete();
                        }))
                // 会话 Cookie 鉴权必须防 CSRF：Cookie 里给非 HttpOnly 的 XSRF-TOKEN，
                // 前端回填到 X-XSRF-TOKEN 头
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler())
                        // /iam/** 由 IAM 自己校验 CSRF。两层都校验会撞两件事：
                        // 同一个头只能带一个 token；两边的 XSRF Cookie 还会同名覆盖。
                        .requireCsrfProtectionMatcher(GatewaySecurityConfig::requireCsrf))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint()))
                .build();
    }

    /**
     * 未认证时的分流。
     *
     * <p><strong>/api/** 返回 401，页面导航才重定向。</strong>
     * 默认入口点一律 302 到授权端点——fetch 跟着 302 走会拿到一页 HTML，
     * 前端只能看到"解析失败"，而真实原因是"未登录"。
     *
     * <p>Security 7 的 reactive {@code ExceptionHandlingSpec} 没有
     * {@code defaultAuthenticationEntryPointFor}，所以这里自己做匹配分流。
     */
    /**
     * 需要网关校验 CSRF 的请求：非安全方法，且目标不是 IAM / 授权服务器。
     *
     * <p>IAM 有自己的会话与 CSRF 防护（Cookie 名也改成了 {@code IAM-XSRF-TOKEN}），
     * 这里再拦一次既多余又必然冲突：同一个 {@code X-XSRF-TOKEN} 头只能放一个 token。
     */
    private static Mono<ServerWebExchangeMatcher.MatchResult> requireCsrf(
            org.springframework.web.server.ServerWebExchange exchange) {
        String method = exchange.getRequest().getMethod().name();
        boolean safe = "GET".equals(method) || "HEAD".equals(method)
                || "TRACE".equals(method) || "OPTIONS".equals(method);
        String path = exchange.getRequest().getPath().value();
        boolean handledByIam = path.startsWith("/iam/") || path.startsWith("/oauth2/");
        // Security 7 的 MatchResult 工厂返回的是 Mono<MatchResult>
        return safe || handledByIam
                ? ServerWebExchangeMatcher.MatchResult.notMatch()
                : ServerWebExchangeMatcher.MatchResult.match();
    }

    private static ServerAuthenticationEntryPoint entryPoint() {
        PathPatternParserServerWebExchangeMatcher api =
                new PathPatternParserServerWebExchangeMatcher("/api/**");
        ServerAuthenticationEntryPoint unauthorized =
                new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED);
        ServerAuthenticationEntryPoint login =
                new RedirectServerAuthenticationEntryPoint("/oauth2/authorization/iam");

        // 注意：不能写 flatMap(...).switchIfEmpty(login...)。
        // commence 返回 Mono<Void>，**完成时是空的**，switchIfEmpty 会把它当成"没匹配上"，
        // 于是又跑一遍登录重定向，把刚写好的 401 覆盖成 302（实测踩过）。
        // 先归一成一个布尔再分支，语义才与意图一致。
        return (exchange, e) -> api.matches(exchange)
                .filter(ServerWebExchangeMatcher.MatchResult::isMatch)
                .map(match -> Boolean.TRUE)
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(isApi -> Boolean.TRUE.equals(isApi)
                        ? unauthorized.commence(exchange, e)
                        : login.commence(exchange, e));
    }

    private static RedirectServerAuthenticationSuccessHandler successHandler() {
        RedirectServerAuthenticationSuccessHandler handler =
                new RedirectServerAuthenticationSuccessHandler("/app/");
        handler.setRequestCache(new WebSessionServerRequestCache());
        return handler;
    }

    /**
     * 让 CSRF Cookie 在**第一个 GET** 上就下发。
     *
     * <p>Spring Security 的 CSRF token 是惰性的：没人读它就没人写 Cookie，
     * 于是 SPA 的第一次写请求会因为"读不到 XSRF-TOKEN"而 403。
     * 这个过滤器订阅一次 token，把 Cookie 触发出来。
     */
    @Bean
    public WebFilter csrfCookieWebFilter() {
        return (exchange, chain) -> exchange
                .getAttributeOrDefault(CsrfToken.class.getName(), Mono.empty())
                .then(chain.filter(exchange));
    }

    /**
     * 授权请求解析器，**强制带 PKCE**。
     *
     * <p>Spring Security 只对公共客户端自动加 PKCE，而 BFF 是机密客户端；
     * IAM 注册时设了 {@code requireProofKey(true)}，不带 {@code code_challenge}
     * 会被授权端点以 invalid_request 拒绝。
     */
    @Bean
    public ServerOAuth2AuthorizationRequestResolver authorizationRequestResolver(
            ReactiveClientRegistrationRepository clientRegistrationRepository) {
        DefaultServerOAuth2AuthorizationRequestResolver resolver =
                new DefaultServerOAuth2AuthorizationRequestResolver(clientRegistrationRepository);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return resolver;
    }
}

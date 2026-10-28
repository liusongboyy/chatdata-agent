package com.liusongboyy.chatdata.gateway;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BFF 的三个端点。
 *
 * <p>浏览器的令牌生命周期全在这里：登录入口、当前主体、退出。
 * 令牌本身不出现在响应里——前端拿不到它，也就偷不走。
 */
@Controller
public class BffController {

    /** 客户端注册 id，与 {@code application.yml} 里 {@code registration.iam} 一致。 */
    private static final String CLIENT_REGISTRATION_ID = "iam";

    private final ServerOAuth2AuthorizedClientRepository authorizedClients;

    public BffController(ServerOAuth2AuthorizedClientRepository authorizedClients) {
        this.authorizedClients = authorizedClients;
    }

    /** 入口：交给 Spring Security 的授权码流程（带 PKCE）。 */
    @GetMapping("/bff/login")
    public Mono<Void> login(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.FOUND);
        exchange.getResponse().getHeaders().setLocation(URI.create("/oauth2/authorization/iam"));
        return exchange.getResponse().setComplete();
    }

    /**
     * 当前主体 + 令牌是否就绪。
     *
     * <p>令牌从会话里取，**不回显令牌内容**，只报是否有效与到期时间——
     * 排查"为什么 401"时这两个信息够用，而令牌本身不该出现在日志或响应里。
     */
    @GetMapping("/bff/me")
    @ResponseBody
    public Mono<Map<String, Object>> me(Authentication authentication,
                                        ServerWebExchange exchange) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (authentication == null || !(authentication.getPrincipal() instanceof OAuth2User user)) {
            out.put("authenticated", false);
            return Mono.just(out);
        }
        out.put("authenticated", true);
        out.put("username", user.getName());
        out.put("attributes", user.getAttributes());

        // 走官方仓库接口取令牌，**不自己拼会话 key**。
        // 曾经按 OAuth2AuthorizedClient.class.getName() 扫会话属性，
        // 而实际存储键是 WebSessionServerOAuth2AuthorizedClientRepository 的类名，
        // 于是永远找不到——表现是"已登录但 tokenReady=false"，
        // 之后所有 /api/** 都是 401，看起来像令牌过期或权限配置错误
        return authorizedClients
                .loadAuthorizedClient(CLIENT_REGISTRATION_ID, authentication, exchange)
                .map(client -> {
                    Instant expiresAt = client.getAccessToken().getExpiresAt();
                    out.put("tokenReady", true);
                    out.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
                    out.put("scopes", client.getAccessToken().getScopes());
                    return out;
                })
                .defaultIfEmpty(withTokenReady(out, false));
    }

    private static Map<String, Object> withTokenReady(Map<String, Object> out, boolean ready) {
        out.put("tokenReady", ready);
        return out;
    }

}

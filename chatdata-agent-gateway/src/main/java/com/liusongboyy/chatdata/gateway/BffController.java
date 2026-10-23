package com.liusongboyy.chatdata.gateway;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
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
    public Mono<Map<String, Object>> me(@AuthenticationPrincipal OAuth2User user, WebSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (user == null) {
            out.put("authenticated", false);
            return Mono.just(out);
        }
        out.put("authenticated", true);
        out.put("username", user.getName());
        out.put("attributes", user.getAttributes());

        OAuth2AuthorizedClient client = findAuthorizedClient(session);
        if (client == null) {
            out.put("tokenReady", false);
        } else {
            Instant expiresAt = client.getAccessToken().getExpiresAt();
            out.put("tokenReady", true);
            out.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
            out.put("scopes", client.getAccessToken().getScopes());
        }
        return Mono.just(out);
    }

    /**
     * 从会话里找已授权的客户端。
     *
     * <p>按类名前缀扫描而不是拼固定的 key：会话属性的 key 由 Spring Security 内部约定，
     * 拼错了不会报错，只会得到"令牌明明在会话里却读不到"。
     */
    private static OAuth2AuthorizedClient findAuthorizedClient(WebSession session) {
        return session.getAttributes().entrySet().stream()
                .filter(e -> e.getKey().startsWith(OAuth2AuthorizedClient.class.getName()))
                .map(e -> (OAuth2AuthorizedClient) e.getValue())
                .findFirst()
                .orElse(null);
    }
}

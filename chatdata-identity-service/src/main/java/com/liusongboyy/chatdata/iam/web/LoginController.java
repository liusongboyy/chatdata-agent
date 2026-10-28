package com.liusongboyy.chatdata.iam.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 服务端不渲染登录页——IAM 与 ask 一样前后端分离。
 *
 * <p>授权服务器的未认证入口会重定向到 {@code /login}，这里把它转成 SPA 的登录路由，
 * 并带上**原始目标地址**，登录成功后由 SPA 跳回。
 *
 * <h2>目标地址从 SavedRequest 取，不能从当前请求取</h2>
 *
 * <p>踩过的坑：一开始用 {@code request.getRequestURI()} 当 returnTo，
 * 但到达本控制器时 URI 已经是 {@code /login}——原始 authorize 地址早被授权服务器
 * 的 302 丢掉了，结果是"登录后又跳回登录页"。
 * Spring Security 在重定向到登录页时会把原始请求存进会话（{@code RequestCache}），
 * 从那里取才是对的。
 */
@Controller
public class LoginController {

    private final HttpSessionRequestCache requestCache = new HttpSessionRequestCache();

    @GetMapping("/login")
    public String login(HttpServletRequest request, HttpServletResponse response) {
        SavedRequest saved = requestCache.getRequest(request, response);
        String target = saved == null ? "/admin/users" : saved.getRedirectUrl();
        // 用完即弃：留着会让下一次直接访问 /login 又跳回上一次的目标
        requestCache.removeRequest(request, response);

        return "redirect:" + UriComponentsBuilder.fromPath("/iam/app/login")
                .queryParam("returnTo", URLEncoder.encode(target, StandardCharsets.UTF_8))
                .build(true)
                .toUriString();
    }
}

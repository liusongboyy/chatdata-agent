package com.liusongboyy.chatdata.iam.policy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 给 {@code /iam/policy/**} 统一施加客户端凭证校验。
 *
 * <h2>为什么是拦截器而不是让每个控制器自己调</h2>
 *
 * <p>这个前缀在过滤链上是 {@code permitAll}——Spring Security 管不到它，
 * 因为凭据是"客户端"而不是"用户"，表达不成一条普通的 {@code hasRole} 规则。
 * 于是**每个 handler 都得自己把闸门关上**，而漏关的后果是静默放行全量策略。
 *
 * <p>拦截器把"漏关"从可能变成不可能：新加一个 handler 什么都不用做。
 *
 * <h2>顺序</h2>
 *
 * <p>拦截器在 handler 之前、过滤链之后执行。放在过滤链里做不了这件事
 * （要写自定义 filter 并自己解析 Basic/Bearer），放在控制器里做则要每个 handler 重复。
 */
@Component
public class PolicyClientAuthInterceptor implements HandlerInterceptor {

    private final IamServiceClientAuth auth;

    public PolicyClientAuthInterceptor(IamServiceClientAuth auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws java.io.IOException {
        // CORS 预检不带 Authorization，拦下它会让浏览器侧的跨域调试莫名其妙地失败。
        // 预检本身不返回任何策略内容，放行是安全的
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        if (auth.allowed(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            return true;
        }

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 不区分"没带凭据"与"凭据不对"：调用方只需要知道进不来，
        // 而区分开等于告诉探测者哪一半猜对了
        response.getWriter().write("{\"message\":\"客户端凭证无效\"}");
        return false;
    }
}

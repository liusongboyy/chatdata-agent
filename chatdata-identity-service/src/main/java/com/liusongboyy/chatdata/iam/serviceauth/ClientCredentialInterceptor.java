package com.liusongboyy.chatdata.iam.serviceauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 给「服务间调用」的端点统一施加客户端凭证校验。
 *
 * <h2>为什么是拦截器而不是让每个控制器自己调</h2>
 *
 * <p>这些路径在过滤链上是 {@code permitAll}——Spring Security 管不到它们，
 * 因为凭据是"客户端"而不是"用户"，表达不成一条普通的 {@code hasRole} 规则。
 * 于是**每个 handler 都得自己把闸门关上**，而漏关的后果是静默放行。
 *
 * <p>拦截器把"漏关"从可能变成不可能：新加一个 handler 什么都不用做。
 *
 * <h2>scope 按前缀给</h2>
 *
 * <p>不同前缀暴露的东西差别很大，所以各要各的 scope：
 *
 * <table>
 *   <tr><th>前缀</th><th>scope</th><th>谁用</th></tr>
 *   <tr><td>{@code /iam/policy/**}</td><td>{@code iam.principal}</td>
 *       <td>ask（读角色/用户目录）</td></tr>
 *   <tr><td>{@code /iam/identity/**}</td><td>{@code iam.identity}</td>
 *       <td>channel（解析飞书 open_id）</td></tr>
 * </table>
 *
 * <p><b>没有匹配的前缀 = 拒绝</b>，不是放行。这一条很重要：注册路径与
 * 这张表是两处配置，只有"匹配不上就拒绝"能让漏配一边的后果是"调不通"
 * 而不是"裸奔"。
 */
@Component
public class ClientCredentialInterceptor implements HandlerInterceptor {

    /**
     * 前缀 → 要求的 scope。
     *
     * <p><b>加新前缀时这里和 {@code WebMvcConfig.SERVICE_PREFIXES} 都要加。</b>
     * 只加在 WebMvcConfig 里的话，这里匹配不上 → 拒绝 → 立刻发现；
     * 只加在这里的话，拦截器根本不会被调用 → 裸奔 → 不会发现。
     * 所以宁可让前者发生。
     */
    private static final Map<String, String> SCOPE_BY_PREFIX = scopeByPrefix();

    private static Map<String, String> scopeByPrefix() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("/iam/policy/", IamServiceClientAuth.SCOPE_PRINCIPAL);
        m.put("/iam/identity/", IamServiceClientAuth.SCOPE_IDENTITY);
        return Map.copyOf(m);
    }

    private final IamServiceClientAuth auth;

    public ClientCredentialInterceptor(IamServiceClientAuth auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws java.io.IOException {
        // CORS 预检不带 Authorization，拦下它会让浏览器侧的跨域调试莫名其妙地失败。
        // 预检本身不返回任何内容，放行是安全的
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String scope = requiredScope(request);
        if (scope != null && auth.allowed(request.getHeader(HttpHeaders.AUTHORIZATION), scope)) {
            return true;
        }

        // scope == null（没有匹配的前缀）也走这里：配置漏了一半，拒绝比放行安全
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 不区分"没带凭据"与"凭据不对"：调用方只需要知道进不来，
        // 而区分开等于告诉探测者哪一半猜对了
        response.getWriter().write("{\"message\":\"客户端凭证无效\"}");
        return false;
    }

    /**
     * 按请求路径找要求的 scope。
     *
     * <p>用 {@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE} 而不是
     * {@code getRequestURI()}：前者是**映射后的模式**（{@code /iam/policy/users}），
     * 后者是原始路径。两者在正常情况下一致，但用模式更贴近"我注册了哪些前缀"这件事。
     *
     * @return 要求的 scope；没有匹配的前缀时返回 null
     */
    private static String requiredScope(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = pattern == null ? request.getRequestURI() : String.valueOf(pattern);
        for (Map.Entry<String, String> e : SCOPE_BY_PREFIX.entrySet()) {
            if (path.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }
}

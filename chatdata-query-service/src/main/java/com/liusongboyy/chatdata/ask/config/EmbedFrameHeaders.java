package com.liusongboyy.chatdata.ask.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 嵌入页的 `frame-ancestors` 白名单。
 *
 * <h2>为什么必须做</h2>
 *
 * <p>问数页面一旦允许被 iframe 嵌入，<b>任何网站都能嵌</b>——
 * 包括钓鱼站。攻击者做一个看起来像银行登录页的页面，中间嵌一个真的问数框，
 * 用户输入的问题会进到我们的系统，而他以为在跟钓鱼站交互。
 *
 * <p>`frame-ancestors` 告诉浏览器：只有白名单里的域名能嵌这个页面。
 * 不在白名单里的，浏览器**直接拒绝渲染**——这是浏览器层强制的，绕不过去。
 *
 * <h2>为什么只对 /app/embed 放开</h2>
 *
 * <p>主问数页（`/app/ask`）是给用户直接打开的，没有任何理由被嵌入。
 * 对它放开等于把整个应用暴露成可嵌入的——包括管理台入口。
 *
 * <p>所以这里按路径分流：`/app/embed` 用白名单，其余一律 `'none'`。
 *
 * <h2>配置</h2>
 *
 * <p>{@code chatdata.embed.allowed-origins}，逗号分隔。**留空 = 谁都不能嵌**，
 * 而不是"谁都能嵌"——失败方向必须安全。
 */
@Component
public class EmbedFrameHeaders extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(EmbedFrameHeaders.class);

    /** 嵌入页的路径。**只对它放开**，见类注释。 */
    private static final String EMBED_PATH = "/app/embed";

    /**
     * 允许嵌入的源。
     *
     * <p>留空时仍然是 `'none'`——**不能因为没配就放开**。
     * 那种失败方向是灾难性的：一个配置疏漏 = 任何网站都能嵌。
     */
    private final List<String> allowedOrigins;

    public EmbedFrameHeaders(
            @Value("${chatdata.embed.allowed-origins:}") String raw) {
        List<String> out = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            for (String s : raw.split(",")) {
                String origin = s.trim();
                if (!origin.isEmpty()) {
                    out.add(origin);
                }
            }
        }
        this.allowedOrigins = List.copyOf(out);
        if (out.isEmpty()) {
            log.info("未配置 chatdata.embed.allowed-origins：嵌入页禁止被任何站点 iframe 引用");
        } else {
            log.info("嵌入页允许的 frame-ancestors：{}", out);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (path != null && path.startsWith(EMBED_PATH)) {
            // 嵌入页：按白名单放开
            response.setHeader("Content-Security-Policy", frameAncestors());
            // **不要同时设 X-Frame-Options。** 那个头只认 ALLOW-FROM（已废弃）
            // 或 DENY/SAMEORIGIN，设了 DENY 会把 frame-ancestors 的放开覆盖掉，
            // 表现为"白名单配了但就是嵌不进去"
        } else {
            // 其余页面：明确禁止被嵌
            response.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
            response.setHeader("X-Frame-Options", "DENY");
        }

        chain.doFilter(request, response);
    }

    private String frameAncestors() {
        if (allowedOrigins.isEmpty()) {
            return "frame-ancestors 'none'";
        }
        StringBuilder sb = new StringBuilder("frame-ancestors");
        for (String origin : allowedOrigins) {
            sb.append(' ').append(origin);
        }
        return sb.toString();
    }
}

package com.liusongboyy.chatdata.ask.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 自研问数前端的 SPA 深链接转发。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>前端用 history 模式路由（URL 干净：{@code /app/ask}）。但服务器上并不存在
 * {@code ask} 这个文件——直接访问、或刷新页面时，静态资源处理器找不到它，
 * 返回 404。用户看到的是"刷新一下就 404"，而通过页内导航进来却一切正常，
 * 这类问题在开发时几乎不会被发现（开发时用的是 Vite dev server，它自带 fallback）。
 *
 * <h2>为什么只列具体路径，不写 {@code /app/**}</h2>
 *
 * <p>写成通配会让**真实存在的静态资源**也走这个控制器——Spring 的控制器映射
 * 优先于静态资源处理器，于是 JS 与 CSS 会被替换成 index.html，
 * 表现为"页面白屏，控制台报 MIME 类型错误"。列具体路由没有这个风险。
 *
 * <p>新增前端路由时**必须在这里补一行**，否则该路由刷新即 404。
 * 这条约束由 {@code WebAppRouteCoverageTest} 兜住——它读前端的 {@code router.ts}
 * 提取全部静态路径，断言每一条都在这里。漏一行就是测试失败，
 * 而不是等到有人刷新页面才发现（{@code /app/admin/models} 与
 * {@code /app/admin/cost} 就是这么漏掉的）。
 */
@Controller
public class WebAppForwardController {

    @GetMapping({
            "/app",
            "/app/",
            "/app/login",
            "/app/ask",
            // 管理面。**每条前端路由都要在这里列出来**，否则该路由刷新即 404。
            // 下面这条带路径参数的用 {id} 通配——它落在最后一段，
            // 不会像 /app/** 那样把 assets 也吞掉。
            "/app/admin",
            "/app/admin/models",
            "/app/admin/cost",
            "/app/admin/datasources",
            "/app/admin/domains",
            "/app/admin/modeling",
            "/app/admin/health",
            "/app/admin/assistants",
            "/app/admin/feedback",
            "/app/admin/domains/{id}/tables",
    })
    public String forwardToApp() {
        // forward 而不是 redirect：地址栏保持不变，且不产生一次额外往返
        return "forward:/app/index.html";
    }
}

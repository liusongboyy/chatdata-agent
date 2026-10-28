package com.liusongboyy.chatdata.iam.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * IAM 前端的 SPA 深链接转发。
 *
 * <p>与 ask 的同名类同理：前端用 history 模式，服务器上并不存在
 * {@code /iam/app/admin/users} 这个文件，刷新即 404。
 *
 * <p>路径前缀是 {@code /iam/app} 而不是 {@code /app}：后者属于 ask 的问数页，
 * 网关同时代理两个前端时不能撞车。
 *
 * <p><strong>只列具体路径，不写 {@code /app/**}</strong>：控制器映射优先于静态资源处理器，
 * 通配会把真实的 JS/CSS 也替换成 index.html，表现为"页面白屏 + MIME 类型报错"。
 * 新增前端路由时必须在这里补一行。
 */
@Controller
public class WebAppForwardController {

    @GetMapping({
            "/iam/app",
            "/iam/app/",
            "/iam/app/login",
            "/iam/app/admin",
            "/iam/app/admin/users",
            "/iam/app/admin/orgs",
            "/iam/app/admin/roles",
            "/iam/app/admin/policies",
    })
    public String forwardToApp() {
        return "forward:/iam/app/index.html";
    }
}

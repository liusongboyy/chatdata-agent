package com.liusongboyy.chatdata.ask.engine;

import com.liusongboyy.chatdata.ask.identity.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 当前主体：身份、角色、功能权限。
 *
 * <h2>为什么不用网关的 {@code /bff/me}</h2>
 *
 * <p>那个端点回显的是**登录那一刻**抓取的属性（{@code BffController.me} 直接返回
 * 会话里的 {@code OAuth2User.getAttributes()}）。管理员改完某人的权限，
 * 对方不重新登录就看不到任何变化——界面会一直藏着该出现的按钮。
 *
 * <p>本端点读的是令牌内省结果（30 秒缓存），与后端 {@code @perm} 判定用的是
 * 同一份数据。于是"界面看到的"和"点下去能不能成"天然一致。
 *
 * <h2>挂在 {@code /api/ask/} 下是刻意的</h2>
 *
 * <p>网关只路由 {@code /app/**}、{@code /api/ask/**}、{@code /api/engine/**}，
 * ask 的安全白名单也只有这两条。另起一个 {@code /api/me} 会 404/403，
 * 前端会把它当成"未登录"并跳登录页——变成死循环。
 */
@RestController
@RequestMapping("/api/ask")
public class MeProfileController {

    private final CurrentUser currentUser;

    public MeProfileController(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    /**
     * <p><strong>返回裸对象，不套 {@code Result} 信封</strong>——与本前缀下其它端点
     * （{@code /api/ask/assistants}、{@code /api/ask/feedback}）一致。
     * {@code Result} 信封是 {@code /api/engine/admin/**} 的约定，两者混用会让前端
     * 少解一层、把 {@code permissions} 读成 {@code undefined}，
     * 表现为"权限全丢、管理台入口消失"（实测踩到过）。
     */
    @GetMapping("/me")
    public Profile me() {
        return new Profile(
                currentUser.username().orElse(""),
                currentUser.displayName(),
                List.copyOf(currentUser.roles()),
                List.copyOf(currentUser.permissions()));
    }

    /**
     * @param displayName 页头在用，不能漏——只给 username 会让标题变成一串登录名
     * @param permissions 功能权限码；前端据此隐藏导航、按钮与 tab
     */
    public record Profile(String username, String displayName,
                          List<String> roles, List<String> permissions) {
    }
}

package com.liusongboyy.chatdata.ask.config;

import com.liusongboyy.chatdata.ask.engine.AdminApiPermissions;
import com.liusongboyy.chatdata.ask.identity.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@code @PreAuthorize("@perm...")} 背后的判定。
 *
 * <h2>与 IAM 侧同名，但数据来源不同</h2>
 *
 * <p>ask 的权限来自令牌内省（{@code IamIntrospector} 映射成 {@code PERM_*} authority，
 * 30 秒缓存），所以这里只读 SecurityContext，不查库——ask 本来就
 * "不查任何用户/角色表"。
 *
 * <h2>回滚开关</h2>
 *
 * <p>{@code chatdata.iam.functional-permissions.enabled=false} 时一律放行，
 * 过滤链同时退回旧的 {@code hasAnyRole("chat2bi","admin")}。没有这个开关的话，
 * IAM 一旦回滚（不再返回权限），ask 的管理面会整体 403——那是一次不必要的停机。
 */
@Component("perm")
public class PermissionChecker {

    private final CurrentUser currentUser;
    private final boolean enabled;

    public PermissionChecker(CurrentUser currentUser,
                             @Value("${chatdata.iam.functional-permissions.enabled:true}")
                             boolean enabled) {
        this.currentUser = currentUser;
        this.enabled = enabled;
    }

    public boolean has(String code) {
        if (!enabled) {
            return true;
        }
        return currentUser.hasPermission(code);
    }

    /**
     * 任一即可。
     *
     * <p>用于**被多个页面共用的读接口**：只挂某一个页面的码，会让另一个页面
     * 在 {@code Promise.all} 里吃 403 并整页空白。
     */
    public boolean hasAny(String... codes) {
        if (!enabled) {
            return true;
        }
        for (String code : codes) {
            if (currentUser.hasPermission(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 管理面接口的判定入口。
     *
     * <p>{@code AdminEngineController} 有 39 个端点、且一批是被多个页面共用的读接口，
     * 逐方法挂注解既啰嗦又容易漏。改为类级一个注解 + 一张可测的规则表
     * （{@link AdminApiPermissions}）。规则表有覆盖测试兜住漂移——
     * 漏一条是测试失败，而不是线上某个接口悄悄用不了或悄悄放行。
     *
     * <p>取不到请求上下文时返回 false（fail-closed）。
     */
    public boolean allowAdminApi() {
        if (!enabled) {
            return true;
        }
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return false;
        }
        HttpServletRequest request = attrs.getRequest();
        return AdminApiPermissions.allows(
                request.getMethod(), request.getRequestURI(), currentUser.permissions());
    }
}

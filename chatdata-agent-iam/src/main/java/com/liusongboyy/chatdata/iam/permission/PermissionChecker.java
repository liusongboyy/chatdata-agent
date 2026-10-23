package com.liusongboyy.chatdata.iam.permission;

import com.liusongboyy.chatdata.iam.config.IamProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * {@code @PreAuthorize("@perm.has('...')")} 背后的判定。
 *
 * <h2>为什么不直接用 {@code hasAuthority('PERM_...')}</h2>
 *
 * <p>因为那样会读**会话里的 authorities**，而会话是在登录那一刻建好的；
 * {@code /iam/me} 与登录响应却走 {@code loadPrincipal} 现查。两者不同源的结果是
 * **界面显示按钮、点下去全 403**——连内置超管也一样。
 *
 * <p>这里与 {@code loadPrincipal} 共用 {@link EffectivePermissionResolver}，
 * 于是"界面上看到的"与"后端判定的"必然是同一份数据。
 */
@Component("perm")
public class PermissionChecker {

    private static final Logger log = LoggerFactory.getLogger(PermissionChecker.class);

    private final EffectivePermissionResolver resolver;
    private final IamProperties properties;

    public PermissionChecker(EffectivePermissionResolver resolver, IamProperties properties) {
        this.resolver = resolver;
        this.properties = properties;
    }

    /** 当前主体是否持有该权限码。 */
    public boolean has(String code) {
        if (!properties.getFunctionalPermissions().isEnabled()) {
            // 回滚开关：整套退回旧的"过滤链判角色"行为
            return true;
        }
        return current().contains(code);
    }

    /** 任一即可。用于"被多个页面共用"的读接口——否则一个 403 会让整页空白。 */
    public boolean hasAny(String... codes) {
        if (!properties.getFunctionalPermissions().isEnabled()) {
            return true;
        }
        Set<String> granted = current();
        for (String code : codes) {
            if (granted.contains(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 当前主体是否持有内置超管**角色**（不是权限）。
     *
     * <p>用于提权防护：授予内置超管角色这件事，只有自己已经是超管的人才能做。
     * 这里读会话 authorities 是安全的——IAM 的会话里一定有 {@code ROLE_*}，
     * 而且"谁是超管"这件事不需要像权限那样实时（它是角色，改它本身就要超管权限）。
     */
    public boolean isBuiltinSuper() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        String expected = "ROLE_" + PermissionCatalog.BUILTIN_SUPER_ROLE;
        return auth.getAuthorities().stream()
                .anyMatch(a -> expected.equals(a.getAuthority()));
    }

    private Set<String> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            return Set.of();
        }
        try {
            return resolver.resolve(auth.getName());
        } catch (RuntimeException e) {
            // 查不出来 = 拒绝（fail-closed），不是放行
            log.warn("解析用户 {} 的功能权限失败，按拒绝处理：{}", auth.getName(), e.getMessage());
            return Set.of();
        }
    }
}

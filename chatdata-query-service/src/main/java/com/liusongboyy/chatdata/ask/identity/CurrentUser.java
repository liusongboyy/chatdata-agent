package com.liusongboyy.chatdata.ask.identity;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 当前请求的主体。
 *
 * <p>取代原来的 {@code IdentityResolver} + {@code RoleService}：
 * 身份与角色都来自 IAM 签发的令牌（资源服务器已内省并放进 SecurityContext），
 * ask **不再查任何用户/角色表**。
 *
 * <p>角色从 authorities 里取（{@code ROLE_xxx} → {@code xxx}）而不是从请求头取——
 * 中文角色名经 HTTP 头会乱码，那正是 ADR-0002 记过的坑。
 */
@Component
public class CurrentUser {

    private static final String ROLE_PREFIX = "ROLE_";
    /** 功能权限的 authority 前缀，由 {@code IamIntrospector} 注入。 */
    private static final String PERMISSION_PREFIX = "PERM_";

    public Optional<String> username() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            return Optional.empty();
        }
        return Optional.of(auth.getName());
    }

    public Set<String> roles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return Set.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String value = authority.getAuthority();
            if (value != null && value.startsWith(ROLE_PREFIX)) {
                roles.add(value.substring(ROLE_PREFIX.length()));
            }
        }
        return roles;
    }

    public boolean hasRole(String roleCode) {
        return roles().contains(roleCode);
    }

    /**
     * 功能权限码（页面/按钮/tab）。
     *
     * <p>来自令牌内省结果里的 {@code PERM_*} authority，与角色是两个维度：
     * 角色决定"能看哪些数据"（`rowRules` 按角色匹配），权限决定"能看到哪些页面与操作"。
     *
     * <p><strong>刻意不从 {@link #attributes()} 读</strong>——那里是行规则的
     * {@code dynamicVars} 的来源，把权限列表混进去会变成一个莫名其妙的规则变量。
     */
    public Set<String> permissions() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String value = authority.getAuthority();
            if (value != null && value.startsWith(PERMISSION_PREFIX)) {
                out.add(value.substring(PERMISSION_PREFIX.length()));
            }
        }
        return out;
    }

    public boolean hasPermission(String code) {
        return code != null && permissions().contains(code);
    }

    /** 显示名。令牌里有就用，没有退回用户名——页头一直要它。 */
    public String displayName() {
        Object name = attributes().get("displayName");
        String display = name == null ? null : String.valueOf(name);
        return display == null || display.isBlank() ? username().orElse("") : display;
    }

    /**
     * 主体的业务属性（orgCode / orgPath / tenant / displayName…）。
     *
     * <p>动态行规则按 {@code sysVar} 从这里取键。取不到时**不会跳过规则**——
     * {@code PermissionGuard} 会判成"规则无法求值"并拒绝（ADR-0002）。
     */
    public java.util.Map<String, Object> attributes() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return java.util.Map.of();
        }
        if (auth.getPrincipal() instanceof OAuth2AuthenticatedPrincipal principal) {
            return principal.getAttributes();
        }
        return java.util.Map.of();
    }
}

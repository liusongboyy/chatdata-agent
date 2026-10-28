package com.liusongboyy.chatdata.engine.permission;

import java.util.Map;
import java.util.Set;

/**
 * 权限判定的主体：谁在问、他有什么角色、有哪些动态变量可用。
 *
 * <p><strong>角色从令牌内解析，不经 HTTP 头传递。</strong>
 * 这是 ADR-0002 实测出的坑：RFC 7230 只允许 ASCII 头值，容器按 ISO-8859-1 解码，
 * 中文角色名（如「区域经理」）经头传输会变成乱码，导致与 {@code rowRules[].role}
 * 匹配失败——而匹配失败的后果是**静默放行**。故本对象只能由服务端从可信来源构造。
 *
 * @param userId      用户标识，用于审计
 * @param roles       角色码集合。**空集不等于放行**——见 {@link PermissionGuard}
 * @param dynamicVars 动态行规则可用的变量，如 {@code orgCode} / {@code userCode}。
 *                    缺某个键时对应规则**不会**被跳过，而是判定失败——见 {@link RowRuleCompiler}
 */
public record PermissionContext(String userId, Set<String> roles, Map<String, Object> dynamicVars) {

    public PermissionContext {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空——审计需要它，且空身份不应能通过任何判定");
        }
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        dynamicVars = dynamicVars == null ? Map.of() : Map.copyOf(dynamicVars);
    }

    public static PermissionContext of(String userId, Set<String> roles) {
        return new PermissionContext(userId, roles, Map.of());
    }

    /**
     * 带动态变量。变量来自资源服务器的内省结果（orgCode / orgPath …），
     * **不是客户端传的**——见 {@code AskRequest.attributes}。
     */
    public static PermissionContext of(String userId, Set<String> roles,
                                       Map<String, Object> dynamicVars) {
        return new PermissionContext(userId, roles, dynamicVars);
    }

    public boolean hasRole(String role) {
        // 逐字符精确匹配。ADR-0002：角色名必须与 rowRules[].role 完全一致，
        // 大小写或空白差异都会导致规则不匹配。
        return roles.contains(role);
    }

    /** 取动态变量。返回 {@code Optional} 而非 null，强制调用方处理"取不到"这一分支。 */
    public java.util.Optional<Object> dynamicVar(String key) {
        if (key == null || key.isBlank()) {
            return java.util.Optional.empty();
        }
        Object v = dynamicVars.get(key);
        if (v == null) {
            return java.util.Optional.empty();
        }
        String s = String.valueOf(v);
        return s.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(v);
    }
}

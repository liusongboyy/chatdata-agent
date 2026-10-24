package com.liusongboyy.chatdata.iam.client;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 令牌换回来的主体。
 *
 * <p>与 IAM 的 {@code /iam/principal} 响应一一对应。
 * **角色与组织不来自令牌本身**（不透明令牌里没有信息），而是 IAM 每次现查的结果——
 * 所以角色变更在下一次内省就生效（配合缓存 TTL）。
 *
 * @param userId   内部用户 id，审计用它
 * @param username 登录名
 * @param roles    角色码（与数据策略里的 {@code rowRules[].role} 同源）
 * @param permissions 功能权限码（页面/按钮/tab）。**与 roles 是两个维度**：
 *               角色决定"能看哪些行"，权限决定"能看到哪些页面与操作"
 * @param scope    授权范围（粗粒度接口能力）
 */
public record IamPrincipal(
        long userId,
        String tenant,
        String username,
        String displayName,
        Long orgId,
        String orgCode,
        String orgPath,
        /** 全部组织路径（兼岗）。行权限按它取并集；空表示未绑定组织。 */
        List<String> orgPaths,
        Set<String> roles,
        Set<String> permissions,
        Set<String> scope,
        String clientId,
        Instant expiresAt,
        int tokenVersion
) {

    public IamPrincipal {
        orgPaths = orgPaths == null ? List.of() : List.copyOf(orgPaths);
        roles = roles == null ? Set.of() : dedupe(roles);
        permissions = permissions == null ? Set.of() : dedupe(permissions);
        scope = scope == null ? Set.of() : dedupe(scope);
    }

    /**
     * 去重后再交给 {@code Set.copyOf}。
     *
     * <p>{@code Set.copyOf} 遇到重复元素会**直接抛异常**，而不是静默忽略。
     * 上游只要有一条没有 DISTINCT 的 SQL（例如两个角色配了同一个权限码），
     * 这里就会炸，而症状是 **401**——看起来像令牌失效，实际是数据重复。
     */
    private static Set<String> dedupe(Set<String> values) {
        return Set.copyOf(new java.util.LinkedHashSet<>(values));
    }

    /** 从 IAM 的 JSON 响应构造。字段缺失时用安全默认值，不抛异常。 */
    @SuppressWarnings("unchecked")
    public static IamPrincipal from(Map<String, Object> body) {
        return new IamPrincipal(
                asLong(body.get("userId"), 0L),
                asString(body.get("tenant")),
                asString(body.get("username")),
                asString(body.get("displayName")),
                body.get("orgId") == null ? null : asLong(body.get("orgId"), 0L),
                asString(body.get("orgCode")),
                asString(body.get("orgPath")),
                asList(body.get("orgPaths")),
                new java.util.LinkedHashSet<>(asList(body.get("roles"))),
                new java.util.LinkedHashSet<>(asList(body.get("permissions"))),
                new java.util.LinkedHashSet<>(asList(body.get("scope"))),
                asString(body.get("clientId")),
                parseInstant(asString(body.get("expiresAt"))),
                (int) asLong(body.get("tokenVersion"), 0L));
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static long asLong(Object v, long fallback) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? fallback : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> asList(Object v) {
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static Instant parseInstant(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(v);
        } catch (RuntimeException e) {
            return null;
        }
    }
}

package com.liusongboyy.chatdata.engine.permission;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;
import java.util.TreeSet;

/**
 * 助手的访问权限，解析自 {@code ask_assistant.perm_json}。
 *
 * <h2>判据与域层**不同，且不可统一**</h2>
 *
 * <p>ADR-0002 实测结论：
 *
 * <table>
 *   <tr><th>层</th><th>闸门判据</th><th>失败表现</th></tr>
 *   <tr><td>域</td><td>{@code grantRoles}/{@code grantUsers} 有内容，或 {@code isPublic}</td><td>返回 0 行</td></tr>
 *   <tr><td>助手</td><td><b>{@code enabled=true} 且角色在 {@code grantRoles} 内</b></td><td>明确错误</td></tr>
 * </table>
 *
 * <p>域看"有没有授权内容"，助手看"开关是否打开"——这是厂商各自默认值的差异，不是笔误，
 * 所以不能合并成一套判据。
 *
 * <h2>要修的缺口：缺 {@code enabled} 时厂商是放行</h2>
 *
 * <p>厂商实测：助手在 {@code enabled=true} 且授权为空时是**拒绝**（安全）；
 * 但配置里**根本没有 {@code enabled} 这个键**时，对**任何角色集放行**，包括空集与不存在的角色。
 * 本项目把"缺键"与"false"一视同仁——{@link #from(JsonNode)} 用
 * {@code asBoolean(false)} 取默认值，缺失即为 false。
 *
 * <p><strong>助手层不是数据保护点。</strong>它只决定"这个助手可不可见/可用"，
 * 数据保护由域层的行/列规则承担（ADR-0002 实测：助手层拦下返回明确错误，域层拦下返回 0 行）。
 * 不要把两者混为一谈。
 *
 * @param enabled    是否启用访问控制。**缺失即 false**（厂商此处是放行，我们改掉）
 * @param grantRoles 可访问的角色
 * @param grantUsers 可访问的用户
 */
public record AssistantPermission(boolean enabled, Set<String> grantRoles, Set<String> grantUsers) {

    public AssistantPermission {
        grantRoles = grantRoles == null ? Set.of() : Set.copyOf(grantRoles);
        grantUsers = grantUsers == null ? Set.of() : Set.copyOf(grantUsers);
    }

    /**
     * 从 {@code perm_json} 解析。
     *
     * <p>{@code enabled} 用 {@code asBoolean(false)}：**键缺失时为 false**。
     * 厂商在缺键时放行，那是本项目要修的缺口之一。
     */
    public static AssistantPermission from(JsonNode json) {
        if (json == null || json.isMissingNode() || json.isNull()) {
            // 没有权限配置 = 未启用访问控制。这与厂商一致（enabled 缺失→false→拒绝），
            // 但注意：拒绝的含义是"该助手谁都访问不了"，需由启动校验提示管理员补配置。
            return new AssistantPermission(false, Set.of(), Set.of());
        }
        return new AssistantPermission(
                json.path("enabled").asBoolean(false),
                strings(json, "grantRoles"),
                strings(json, "grantUsers"));
    }

    /**
     * 是否放行该主体。
     *
     * <p>两个条件**都要满足**：开关打开，且主体在授权名单内。
     * 开关关闭时一律拒绝，不论名单里有什么——这是与域层判据的关键差异。
     */
    public boolean grantsAccess(PermissionContext ctx) {
        if (!enabled) {
            return false;
        }
        if (grantUsers.contains(ctx.userId())) {
            return true;
        }
        for (String role : ctx.roles()) {
            if (grantRoles.contains(role)) {
                return true;
            }
        }
        return false;
    }

    /** 是否已配置过访问控制。供启动校验使用。 */
    public boolean hasAnyGrant() {
        return enabled && (!grantRoles.isEmpty() || !grantUsers.isEmpty());
    }

    private static Set<String> strings(JsonNode parent, String field) {
        Set<String> out = new TreeSet<>();
        parent.path(field).forEach(n -> {
            String s = n.asText();
            if (s != null && !s.isBlank()) {
                out.add(s);
            }
        });
        return out;
    }
}

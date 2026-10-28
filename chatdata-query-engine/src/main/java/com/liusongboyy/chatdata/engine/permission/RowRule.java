package com.liusongboyy.chatdata.engine.permission;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 行级规则：某个角色只能看到满足该条件的行。
 *
 * <p>对应厂商 {@code perm_json.rowRules[]} 的一项，字段名保持一致以便直接照搬配置。
 *
 * <p><strong>语义是"按角色授予限制"，不是"按角色授予权限"。</strong>
 * 某角色没有匹配的规则时，厂商的实现是**不附加任何过滤条件**（看到全部行）。
 * 我们改成默认拒绝——见 {@link RowScope} 与 {@link PermissionGuard}。
 *
 * @param id      规则标识，用于审计与排错
 * @param role    适用角色。**逐字符精确匹配**，中文角色名不能经 HTTP 头传递
 * @param field   字段名。会做标识符白名单校验，见 {@link RowRuleCompiler}
 * @param op      运算符
 * @param value   静态取值；{@code dynamic=true} 时忽略
 * @param sysVar  动态变量名；{@code dynamic=true} 时从 {@link PermissionContext#dynamicVars()} 取
 * @param dynamic 是否动态取值
 */
public record RowRule(
        long id,
        String role,
        String field,
        String op,
        String value,
        String sysVar,
        boolean dynamic
) {

    public static RowRule from(JsonNode n) {
        return new RowRule(
                n.path("id").asLong(0L),
                text(n, "role"),
                text(n, "field"),
                text(n, "op"),
                text(n, "value"),
                text(n, "sysVar"),
                n.path("dynamic").asBoolean(false));
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}

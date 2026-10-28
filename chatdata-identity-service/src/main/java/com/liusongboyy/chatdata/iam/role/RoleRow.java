package com.liusongboyy.chatdata.iam.role;

/**
 * 角色。
 *
 * @param category ADMIN（管理台）/ DATA（数据权限）/ FUNCTION（功能）。
 *                 分类沿用 ADR-0004：三者的授予对象与失败后果完全不同，不能混用。
 * @param code     一经使用不可重命名——权限配置按 code 引用，改名等于静默失效。
 * @param permissionCount 已配置的功能权限项数。列表页要能一眼看出"配了几项"，
 *                        否则得逐个点开。内置超管在服务层报全量（见 AdminRolePermissionService）。
 */
public record RoleRow(
        long id,
        String tenantId,
        String code,
        String name,
        String category,
        String description,
        boolean enabled,
        int permissionCount
) {
}

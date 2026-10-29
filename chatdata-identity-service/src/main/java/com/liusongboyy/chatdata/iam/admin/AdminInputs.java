package com.liusongboyy.chatdata.iam.admin;

import java.util.List;

/**
 * 管理面的入参。
 *
 * <p>集中在一处而不是散成十几个文件：它们的生命周期完全一致（跟着接口走），
 * 拆开只会让人在十几个小文件之间跳。
 */
public final class AdminInputs {

    private AdminInputs() {
    }

    /**
     * 新建用户。
     *
     * @param roles 角色码；不存在的码会被拒绝（不是静默忽略）
     */
    public record UserCreate(
            String username,
            String password,
            String displayName,
            Long orgId,
            List<String> roles,
            String status
    ) {
    }

    /**
     * 更新用户。用户名不可改（审计与策略都按用户名/ID 关联）。
     *
     * @param orgId 主组织。**这是权威值，不是"空则不改"**——与其他字段的
     *              局部合并语义刻意不同：组织要能被**清空**，而 null 表达不了
     *              "不改"和"清空"两件事。前端提交表单时总是带上当前值，
     *              所以"没动它"表现为"传回同一个 id"。null 表示不设组织。
     * @param externalProvider 外部身份来源（feishu/dingtalk…）。空白表示解绑。
     * @param externalId       外部身份 id（如飞书 open_id）。与 provider 一起给或一起空。
     *                         不放进 {@code UserCreate}：刚建号时还没有对方的 open_id，
     *                         绑定通常发生在建号之后（对方发一条消息才拿得到）。
     */
    public record UserUpdate(String displayName, String status, Long orgId,
                             String externalProvider, String externalId) {
    }

    /** 重置密码。 */
    public record PasswordReset(String password) {
    }

    /** 覆盖式设置角色。 */
    public record RoleCodes(List<String> roles) {
    }

    /**
     * 新建组织。
     *
     * @param code 稳定编码，创建后不可改（外部同步与策略按它映射）
     */
    public record OrgCreate(String code, String name, Long parentId, Integer sortNo, Boolean enabled) {
    }

    /** 更新组织。P0 不支持移动节点（改 parentId），那要连同子树 path 一起重写。 */
    public record OrgUpdate(String name, Integer sortNo, Boolean enabled) {
    }

    /** 新建角色。code 创建后不可改——权限配置按它引用，改名等于静默失效。 */
    public record RoleCreate(String code, String name, String category,
                             String description, Boolean enabled) {
    }

    /** 更新角色。 */
    public record RoleUpdate(String name, String category, String description, Boolean enabled) {
    }

    /**
     * 角色的功能权限（整表替换）。
     *
     * <p>提交的是"这个角色最终的权限集合"，不是增量——取消勾选是最常见的操作，
     * 增量语义没法表达它。
     *
     * @param codes 权限码；未知码会被拒绝，子码会自动补上父页面码
     */
    public record RolePermissionCodes(List<String> codes) {
    }
}

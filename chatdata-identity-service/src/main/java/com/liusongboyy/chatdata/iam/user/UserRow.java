package com.liusongboyy.chatdata.iam.user;

/**
 * 登录时需要的用户字段。**不包含组织与角色**——它们分别查，避免一个巨大的联表。
 *
 * @param tokenVersion 改密码/踢下线时递增；资源服务器用它让旧令牌失效
 */
public record UserRow(
        long id,
        String username,
        String passwordHash,
        String displayName,
        String status,
        int tokenVersion
) {
    public boolean active() {
        return "ACTIVE".equalsIgnoreCase(status);
    }
}

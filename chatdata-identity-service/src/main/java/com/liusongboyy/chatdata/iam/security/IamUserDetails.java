package com.liusongboyy.chatdata.iam.security;

import java.util.List;
import java.util.Set;

/**
 * IAM 主体视图。
 *
 * <p><strong>它不是 Spring Security 的 {@code UserDetails}。</strong>
 * 认证时用的是框架自带的 {@code org.springframework.security.core.userdetails.User}：
 * 授权服务器会把 Authentication 序列化进 {@code oauth2_authorization}，
 * 而它用的 Jackson 白名单不认自定义类型——自定义 principal 会在换令牌时报
 * "PolymorphicTypeValidator denied resolution"。
 *
 * <p>所以额外属性（userId/组织/token_version）不进认证对象，
 * 需要时按用户名现查。这也正好符合"角色与组织不进令牌、每次现取"的设计。
 */
public record IamUserDetails(
        long userId,
        String tenant,
        String username,
        String displayName,
        Long orgId,
        String orgCode,
        String orgPath,
        /**
         * 该用户所属**全部**组织的物化路径，主组织在前。
         *
         * <p>兼岗（同时管两个大区）时行权限要取并集；只有 {@link #orgPath}
         * 会让一半数据凭空消失。不含已停用的组织。
         */
        List<String> orgPaths,
        /**
         * 该用户所属**全部**组织的**编码**物化路径，主组织在前。
         *
         * <p>与 {@link #orgPaths} 一一对应，区别只在用 code 而不是 id 拼。
         * 业务库通常只认组织编码，行规则按编码路径做前缀匹配才生效。
         */
        List<String> orgCodePaths,
        String orgCodePath,
        Set<String> roles,
        /** 功能权限码（页面/按钮/tab）。与 {@code roles} 是两个维度：见 PermissionCatalog。 */
        Set<String> permissions,
        int tokenVersion
) {

    public IamUserDetails {
        orgPaths = orgPaths == null ? List.of() : List.copyOf(orgPaths);
        orgCodePaths = orgCodePaths == null ? List.of() : List.copyOf(orgCodePaths);
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public List<String> roleCodes() {
        return roles.stream().sorted().toList();
    }

    public List<String> permissionCodes() {
        return permissions.stream().sorted().toList();
    }
}

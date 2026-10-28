package com.liusongboyy.chatdata.iam.security;

import com.liusongboyy.chatdata.iam.config.IamProperties;
import com.liusongboyy.chatdata.iam.org.OrgMapper;
import com.liusongboyy.chatdata.iam.org.OrgRow;
import com.liusongboyy.chatdata.iam.permission.EffectivePermissionResolver;
import com.liusongboyy.chatdata.iam.user.UserMapper;
import com.liusongboyy.chatdata.iam.user.UserRow;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;

/**
 * 认证时的主体加载。
 *
 * <p>返回框架自带的 {@link User}，不是自定义类型——原因见 {@link IamUserDetails}：
 * 授权服务器要序列化 Authentication，自定义 principal 会被 Jackson 白名单拒绝。
 *
 * <p>角色每次现查，不缓存进认证对象：角色变更后下一次认证立即生效。
 */
@Service
public class IamUserDetailsService implements UserDetailsService {

    /** 无密码用户（未来的 SSO 用户）用它，保证任何密码都比对不上。 */
    private static final String NO_PASSWORD = "{noop}!sso-only!";

    private final UserMapper users;
    private final OrgMapper orgs;
    private final IamProperties properties;
    /**
     * 功能权限的**唯一**来源。
     *
     * <p>{@code @PreAuthorize("@perm.has(...)")} 也走它，所以"界面上看到的权限"
     * 与"后端判定的权限"永远是同一份数据。反过来（一个走会话 authorities、
     * 一个走现查）会得到**界面有按钮、点了全 403**，连内置超管都一样。
     */
    private final EffectivePermissionResolver permissions;

    public IamUserDetailsService(UserMapper users, OrgMapper orgs, IamProperties properties,
                                 EffectivePermissionResolver permissions) {
        this.users = users;
        this.orgs = orgs;
        this.properties = properties;
        this.permissions = permissions;
    }

    /**
     * 登录时的认证主体。
     *
     * <p><strong>刻意只带角色，不带功能权限。</strong>方法级判定走的是
     * {@code PermissionChecker}（现查 + 5 秒缓存），不走会话里的 authorities——
     * 否则权限变更要等重新登录才生效，而界面（{@code /iam/me}）却是实时的。
     */
    @Override
    public UserDetails loadUserByUsername(String username) {
        UserRow user = users.selectByUsername(properties.getTenant(), username);
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        List<SimpleGrantedAuthority> authorities = users.selectRoleCodes(user.id()).stream()
                .map(code -> new SimpleGrantedAuthority("ROLE_" + code))
                .toList();

        return User.withUsername(user.username())
                .password(user.passwordHash() == null ? NO_PASSWORD : user.passwordHash())
                .authorities(authorities)
                .disabled(!user.active())
                .build();
    }

    /** 需要 userId/组织/权限/tokenVersion 时（登录响应、/iam/me、/iam/principal）走这里现查。 */
    public IamUserDetails loadPrincipal(String username) {
        UserRow user = users.selectByUsername(properties.getTenant(), username);
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        OrgRow org = orgs.selectPrimaryByUser(user.id());
        return new IamUserDetails(
                user.id(),
                properties.getTenant(),
                user.username(),
                user.displayName() == null ? user.username() : user.displayName(),
                org == null ? null : org.id(),
                org == null ? null : org.code(),
                org == null ? null : org.path(),
                orgs.selectOrgPathsByUser(user.id()),
                orgs.selectOrgCodePathsByUser(user.id()),
                org == null ? null : org.codePath(),
                new HashSet<>(users.selectRoleCodes(user.id())),
                permissions.resolve(user),
                user.tokenVersion());
    }
}

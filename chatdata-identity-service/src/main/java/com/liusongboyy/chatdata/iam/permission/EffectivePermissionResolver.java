package com.liusongboyy.chatdata.iam.permission;

import com.liusongboyy.chatdata.iam.config.IamProperties;
import com.liusongboyy.chatdata.iam.user.UserMapper;
import com.liusongboyy.chatdata.iam.user.UserRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把"用户"解析成"他能用的功能权限码"。
 *
 * <h2>为什么必须只有这一处</h2>
 *
 * <p>这是本功能最容易出事的地方。界面展示（{@code /iam/me}）与强制判定
 * （{@code @PreAuthorize}）如果各算各的，就会出现**界面显示按钮、点下去全 403**
 * ——包括内置超管。所以 {@code IamUserDetailsService.loadPrincipal}、
 * {@code loadUserByUsername} 与 {@code PermissionChecker} 全部走这里。
 *
 * <h2>缓存 5 秒</h2>
 *
 * <p>一次请求里可能触发多次判定（页面 + 几个按钮），每次都查库没有意义。
 * 5 秒是"改完权限多久生效"的上限，比内省缓存（30 秒）短——IAM 自己没必要更慢。
 *
 * <p>缓存失败不影响正确性：取不到就回源。
 */
@Component
public class EffectivePermissionResolver {

    private static final Logger log = LoggerFactory.getLogger(EffectivePermissionResolver.class);

    /** 上限。超过就整表清掉——这里的规模是"人"，不是"会话"。 */
    private static final int MAX_CACHE_ENTRIES = 2_000;

    private final UserMapper users;
    private final RolePermissionMapper rolePermissions;
    private final IamProperties properties;
    private final long ttlMillis;

    private final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();

    public EffectivePermissionResolver(UserMapper users, RolePermissionMapper rolePermissions,
                                      IamProperties properties) {
        this.users = users;
        this.rolePermissions = rolePermissions;
        this.properties = properties;
        this.ttlMillis = Math.max(0, properties.getFunctionalPermissions().getCacheSeconds() * 1000L);
    }

    /**
     * @param user 已加载的用户行（调用方通常刚查过，避免重复查询）
     * @return 该用户的功能权限码；无角色或角色未配权限时为空集
     */
    public Set<String> resolve(UserRow user) {
        if (user == null) {
            return Set.of();
        }
        if (ttlMillis <= 0) {
            return compute(user);
        }

        long now = System.currentTimeMillis();
        Entry hit = cache.get(user.id());
        if (hit != null && hit.expiresAt() > now) {
            return hit.codes();
        }

        Set<String> codes = compute(user);
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        cache.put(user.id(), new Entry(now + ttlMillis, codes));
        return codes;
    }

    /** 按用户名解析。给只需要"当前登录的是谁"的调用方用（如 {@code @perm}）。 */
    public Set<String> resolve(String username) {
        if (username == null || username.isBlank()) {
            return Set.of();
        }
        return resolve(users.selectByUsername(properties.getTenant(), username));
    }

    /** 角色权限改动后主动失效，避免"改完要等 5 秒"。 */
    public void invalidate(long userId) {
        cache.remove(userId);
    }

    private Set<String> compute(UserRow user) {
        // 角色与权限取同一判据（都排除停用角色），否则两者会不一致
        Set<String> roleCodes = new HashSet<>(users.selectRoleCodes(user.id()));

        // 内置超管：不看库，直接全量。这是防锁死的那道保险
        if (roleCodes.contains(PermissionCatalog.BUILTIN_SUPER_ROLE)) {
            return PermissionCatalog.allCodes();
        }

        Set<String> configured = new LinkedHashSet<>(rolePermissions.selectCodesByUser(user.id()));
        int granted = configured.size();
        // 目录里已经删掉的码不生效（历史数据可能残留）。不能只依赖保存时校验——
        // 删码之后库里就留着永远匹配不上的行
        configured.retainAll(PermissionCatalog.allCodes());
        if (configured.size() < granted) {
            log.debug("用户 {} 的权限里有 {} 个目录中不存在的码，已忽略",
                    user.username(), granted - configured.size());
        }
        return PermissionCatalog.expand(roleCodes, configured);
    }

    private record Entry(long expiresAt, Set<String> codes) {
    }
}

package com.liusongboyy.chatdata.iam.permission;

import com.liusongboyy.chatdata.iam.admin.IamAdminException;
import com.liusongboyy.chatdata.iam.config.IamProperties;
import com.liusongboyy.chatdata.iam.role.RoleMapper;
import com.liusongboyy.chatdata.iam.role.RoleRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 角色功能权限的读写。
 *
 * <h2>保存是整表替换</h2>
 *
 * <p>界面提交的是"这个角色最终的权限集合"，不是增量。增量语义会让"取消勾选"
 * 没法表达——而取消勾选正是最常见的操作。
 *
 * <h2>两道校验</h2>
 *
 * <ol>
 *   <li><strong>未知码拒绝。</strong>库里留下永远不会生效的脏码，排查起来
 *       比直接报错难得多</li>
 *   <li><strong>子码补父码。</strong>勾了"新增用户"却看不到用户页，是自相矛盾的状态</li>
 * </ol>
 */
@Service
public class AdminRolePermissionService {

    private static final Logger log = LoggerFactory.getLogger(AdminRolePermissionService.class);

    private final RoleMapper roles;
    private final RolePermissionMapper rolePermissions;
    private final IamProperties properties;

    public AdminRolePermissionService(RoleMapper roles, RolePermissionMapper rolePermissions,
                                      IamProperties properties) {
        this.roles = roles;
        this.rolePermissions = rolePermissions;
        this.properties = properties;
    }

    /** 目录树。界面据此渲染分组与勾选项。 */
    public List<PermissionCatalog.Node> catalog() {
        return PermissionCatalog.TREE;
    }

    /**
     * 某角色已配置的权限码。
     *
     * <p>内置超管返回**全部**码（且界面应显示为只读）——否则打开它会是空集合，
     * 看起来像配错了。
     */
    public Set<String> codesOf(long roleId) {
        RoleRow role = requireRole(roleId);
        if (PermissionCatalog.BUILTIN_SUPER_ROLE.equals(role.code())) {
            return PermissionCatalog.allCodes();
        }
        return Set.copyOf(rolePermissions.selectCodesByRole(roleId));
    }

    /**
     * 整表替换。
     *
     * @return 实际保存的码数（已补全父码）
     */
    @Transactional(rollbackFor = Exception.class)
    public int save(long roleId, List<String> codes, String operator) {
        RoleRow role = requireRole(roleId);

        if (PermissionCatalog.BUILTIN_SUPER_ROLE.equals(role.code())) {
            throw new IamAdminException(
                    "「" + role.code() + "」是内置超级管理员，自动拥有全部权限，不能单独配置。"
                            + "如需限制某人，请给他一个自定义角色");
        }

        Set<String> cleaned = new LinkedHashSet<>();
        for (String raw : codes == null ? List.<String>of() : codes) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String code = raw.trim();
            if (!PermissionCatalog.contains(code)) {
                throw new IamAdminException("未知的权限码：" + code);
            }
            cleaned.add(code);
        }
        Set<String> normalized = PermissionCatalog.withParents(cleaned);

        rolePermissions.deleteByRole(roleId);
        List<String> ordered = new ArrayList<>(normalized);
        ordered.sort(String::compareTo);
        for (String code : ordered) {
            rolePermissions.insert(roleId, code);
        }

        // 不在这里挨个失效用户的权限缓存：要知道"谁有这个角色"就得再查一次库，
        // 而 5 秒的 TTL 已经足够短。这里只记一笔，避免以后有人以为漏了
        log.debug("角色 {} 的权限已变更，相关用户的权限缓存将在 TTL 内自然过期", roleId);

        log.info("角色权限已更新：role={}(id={}) 共 {} 项 by={}",
                role.code(), roleId, ordered.size(), operator);
        return ordered.size();
    }

    /** 角色列表上的"配了几项"。内置超管报全量，与 {@link #codesOf} 口径一致。 */
    public int countOf(RoleRow role) {
        if (PermissionCatalog.BUILTIN_SUPER_ROLE.equals(role.code())) {
            return PermissionCatalog.allCodes().size();
        }
        return rolePermissions.countByRole(role.id());
    }

    private RoleRow requireRole(long roleId) {
        // RoleMapper 没有 selectById（见其说明），按列表过滤——角色规模是十级
        return roles.selectAll(properties.getTenant()).stream()
                .filter(r -> r.id() == roleId)
                .findFirst()
                .orElseThrow(() -> new IamAdminException("角色不存在：id=" + roleId));
    }
}

package com.liusongboyy.chatdata.iam.admin;

import com.liusongboyy.chatdata.iam.config.IamProperties;
import com.liusongboyy.chatdata.iam.role.RoleMapper;
import com.liusongboyy.chatdata.iam.role.RoleRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 角色管理。
 *
 * <h2>code 不可改</h2>
 *
 * <p>权限配置（数据策略、助手授权）按 code 引用角色，改名等于让这些配置静默失效。
 * 因此更新接口只接受名称、分类、描述与启用状态。
 *
 * <h2>分类沿用 ADR-0004</h2>
 *
 * <p>ADMIN（管理台）/ DATA（数据权限）/ FUNCTION（功能）。混淆三者的后果是提权：
 * 把数据权限角色当管理台角色授予，等于让被权限约束的人去改约束自己的规则。
 */
@Service
public class AdminRoleService {

    private static final Logger log = LoggerFactory.getLogger(AdminRoleService.class);
    private static final Set<String> CATEGORIES = Set.of("ADMIN", "DATA", "FUNCTION");

    private final RoleMapper roles;
    private final IamProperties properties;

    public AdminRoleService(RoleMapper roles, IamProperties properties) {
        this.roles = roles;
        this.properties = properties;
    }

    public List<RoleRow> list() {
        return roles.selectAll(properties.getTenant());
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(AdminInputs.RoleCreate input) {
        String code = require(input.code(), "角色码不能为空");
        String name = require(input.name(), "角色名称不能为空");
        String category = normalizeCategory(input.category());
        try {
            roles.insertRole(properties.getTenant(), code, name, category,
                    blankToNull(input.description()),
                    input.enabled() == null || input.enabled());
        } catch (DuplicateKeyException e) {
            throw new IamAdminException("角色码已存在：" + code);
        }
        log.info("新增角色：code={} category={}", code, category);
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(long id, AdminInputs.RoleUpdate input) {
        RoleRow current = roles.selectAll(properties.getTenant()).stream()
                .filter(r -> r.id() == id)
                .findFirst()
                .orElseThrow(() -> new IamAdminException("角色不存在：id=" + id));

        boolean nextEnabled = input.enabled() == null ? current.enabled() : input.enabled();
        // 内置超管角色一旦停用，所有超管用户立即失去全部权限——而他们
        // 正是唯一还能改回来的人。这个锁死只能靠改库解开
        if (com.liusongboyy.chatdata.iam.permission.PermissionCatalog.BUILTIN_SUPER_ROLE
                .equals(current.code()) && current.enabled() && !nextEnabled) {
            throw new IamAdminException(
                    "「" + current.code() + "」是内置超级管理员角色，不能停用");
        }

        roles.updateRole(id,
                input.name() == null || input.name().isBlank() ? current.name() : input.name().trim(),
                input.category() == null || input.category().isBlank()
                        ? current.category() : normalizeCategory(input.category()),
                input.description() == null ? current.description() : blankToNull(input.description()),
                nextEnabled);
        log.info("更新角色：id={}", id);
    }

    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return "DATA";
        }
        String c = category.trim().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(c)) {
            throw new IamAdminException("角色分类只能是 ADMIN / DATA / FUNCTION");
        }
        return c;
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IamAdminException(message);
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

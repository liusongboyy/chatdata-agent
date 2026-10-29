package com.liusongboyy.chatdata.iam.admin;

import com.liusongboyy.chatdata.iam.config.IamProperties;
import com.liusongboyy.chatdata.iam.org.OrgMapper;
import com.liusongboyy.chatdata.iam.role.RoleMapper;
import com.liusongboyy.chatdata.iam.user.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 用户管理。
 *
 * <h2>密码永远不在这里出现</h2>
 *
 * <p>写入的是 BCrypt 哈希；日志与返回值都不含明文。
 * 重置密码时 {@code token_version + 1}，已签发的令牌立即失效。
 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);
    private static final int MIN_PASSWORD = 6;
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";

    private final UserMapper users;
    private final OrgMapper orgs;
    private final RoleMapper roles;
    private final PasswordEncoder passwordEncoder;
    private final IamProperties properties;
    /**
     * 提权防护用的判定。
     *
     * <p>接口上的 {@code @PreAuthorize} 只能表达"有没有这个码"，表达不了
     * "要授出去的那个角色是不是比我更大"——那必须在服务层判。
     */
    private final com.liusongboyy.chatdata.iam.permission.PermissionChecker perm;

    public AdminUserService(UserMapper users, OrgMapper orgs, RoleMapper roles,
                            PasswordEncoder passwordEncoder, IamProperties properties,
                            com.liusongboyy.chatdata.iam.permission.PermissionChecker perm) {
        this.users = users;
        this.orgs = orgs;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.perm = perm;
    }

    /** 单页最多取多少。前端的分页器与这里对齐，别让一个 ?size=100000 把库拖死。 */
    public static final int MAX_PAGE_SIZE = 200;

    /**
     * 分页列表。
     *
     * <p>页码越界时返回最后一页而不是空页——删到只剩 3 页时停在第 5 页，
     * 空表格比"跳回最后一页"更像故障。
     */
    public UserPage list(int page, int size, String keyword, String status, Long orgId) {
        int pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        int requestedPage = Math.max(1, page);
        String tenant = properties.getTenant();
        String statusFilter = normalizeStatusFilter(status);
        String like = likePattern(keyword);
        String orgPath = requireOrgPath(orgId);

        long total = users.countPage(tenant, statusFilter, orgPath, like);
        if (total == 0) {
            return new UserPage(List.of(), 0, requestedPage, pageSize);
        }
        int actualPage = (int) Math.min(requestedPage, (total + pageSize - 1) / pageSize);
        List<UserView> items = users.selectPage(tenant, statusFilter, orgPath, like,
                        pageSize, (long) (actualPage - 1) * pageSize).stream()
                .map(UserView::of)
                .toList();
        return new UserPage(items, total, actualPage, pageSize);
    }

    /** 顶部统计。全量口径，不随筛选变化（与改造前的界面一致）。 */
    public UserStats stats() {
        UserMapper.UserStatsRow row = users.selectStats(properties.getTenant());
        return new UserStats(row.total(), row.active(), row.disabled(), row.orgs());
    }

    public UserView get(long id) {
        UserMapper.AdminUserRow row = users.selectAdminById(id);
        if (row == null) {
            throw new IamAdminException("用户不存在：id=" + id);
        }
        return UserView.of(row);
    }

    @Transactional(rollbackFor = Exception.class)
    public long create(AdminInputs.UserCreate input) {
        String username = require(input.username(), "用户名不能为空");
        String password = require(input.password(), "密码不能为空");
        if (password.length() < MIN_PASSWORD) {
            throw new IamAdminException("密码至少 " + MIN_PASSWORD + " 位");
        }
        String status = normalizeStatus(input.status());
        List<String> roleCodes = requireRoles(input.roles());
        requireAssignPermissionWhenRolesPresent(roleCodes);
        if (input.orgId() != null) {
            requireOrg(input.orgId());
        }

        Map<String, Object> params = new HashMap<>();
        params.put("tenant", properties.getTenant());
        params.put("username", username);
        params.put("passwordHash", passwordEncoder.encode(password));
        params.put("displayName", blankToNull(input.displayName()));
        params.put("status", status);
        try {
            users.insertUser(params);
        } catch (DuplicateKeyException e) {
            throw new IamAdminException("用户名已存在：" + username);
        }
        Object idValue = params.get("id");
        if (!(idValue instanceof Number n)) {
            throw new IllegalStateException("新增用户后未回填主键");
        }
        long id = n.longValue();

        if (input.orgId() != null) {
            users.insertUserOrg(id, input.orgId(), true);
        }
        assignRoles(id, roleCodes);
        log.info("新增用户：id={} username={}", id, username);
        return id;
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(long id, AdminInputs.UserUpdate input) {
        requireUser(id);
        String status = normalizeStatus(input.status());
        guardSuperuserStaysUsable(id, status);
        users.updateUser(id, blankToNull(input.displayName()), status);
        applyPrimaryOrg(id, input.orgId());
        applyExternalIdentity(id, input.externalProvider(), input.externalId());
        log.info("更新用户：id={} status={} orgId={} external={}",
                id, status, input.orgId(), input.externalProvider());
    }

    /**
     * 设置外部身份（渠道绑定）。
     *
     * <p><b>空白即解绑</b>，与 {@link #applyPrimaryOrg} 同样的权威值语义：
     * 管理员要能把绑定清掉，而 null 表达不了"不改"与"清空"两件事。
     *
     * <p>两个字段必须**一起给或一起空**。只给一个会留下"来源是 feishu、
     * id 是空"这种半截状态——{@code selectBoundToChannel} 恰好会忽略它，
     * 但库里躺着一行自相矛盾的数据，下次排查要花时间想清楚它为什么存在。
     *
     * <p>冲突（同一个飞书号绑了两个人）由唯一索引
     * {@code uk_iam_user_external} 拦下，这里翻译成能读懂的话。
     * <b>不静默覆盖</b>：那等于把别人的绑定抢走，是数据事故不是便利。
     */
    private void applyExternalIdentity(long userId, String provider, String externalId) {
        String p = blankToNull(provider);
        String e = blankToNull(externalId);
        if (p == null && e == null) {
            users.updateExternalIdentity(userId, null, null);
            return;
        }
        if (p == null || e == null) {
            throw new IamAdminException("外部身份来源与外部 ID 必须同时填写或同时清空");
        }
        try {
            users.updateExternalIdentity(userId, p.trim(), e.trim());
        } catch (DuplicateKeyException ex) {
            throw new IamAdminException(
                    "该外部账号已绑定到其他用户（来源=" + p.trim() + "，ID=" + e.trim() + "）");
        }
    }

    /**
     * 设置主组织。
     *
     * <p><strong>{@code orgId} 是权威值：null 表示"不设组织"。</strong>
     * 这与 {@link #update} 里其他字段的"空则不改"刻意不同——组织必须能被清空，
     * 而 null 没法同时表达"不改"和"清空"。前端提交表单时总是带上当前值，
     * 所以"没动它"表现为"传回同一个 id"。
     *
     * <p>组织关系到**数据范围**（行级规则的动态变量取的就是主组织），
     * 所以改完要 `token_version + 1` 让已签发的令牌失效吗？不需要：
     * 内省每次都现查组织（`/iam/principal` 走 `loadPrincipal`），
     * 30 秒缓存内自动生效。改密码才需要踢下线，改组织不需要。
     */
    private void applyPrimaryOrg(long userId, Long orgId) {
        // 覆盖式：先清空再设。用户可能曾经属于多个组织，这里把它收敛成"一个主组织"，
        // 与列表/详情里"取主组织"的展示口径一致——否则界面上会显示 A，实际数据范围按 B 算
        users.deleteUserOrgs(userId);
        if (orgId == null) {
            return;
        }
        requireOrg(orgId);
        users.insertUserOrg(userId, orgId, true);
    }

    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(long id, AdminInputs.PasswordReset input) {
        requireUser(id);
        String password = require(input.password(), "密码不能为空");
        if (password.length() < MIN_PASSWORD) {
            throw new IamAdminException("密码至少 " + MIN_PASSWORD + " 位");
        }
        // updatePassword 内部 token_version + 1：旧令牌立即失效
        users.updatePassword(id, passwordEncoder.encode(password));
        log.info("重置用户密码：id={}", id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(long id, AdminInputs.RoleCodes input) {
        requireUser(id);
        assignRoles(id, requireRoles(input.roles()));
    }

    /** 覆盖式赋值：先删后插。空列表表示收回全部角色。 */
    private void assignRoles(long userId, List<String> roleCodes) {
        users.deleteUserRoles(userId);
        for (String code : roleCodes) {
            users.insertUserRole(userId, properties.getTenant(), code);
        }
        log.info("设置用户角色：id={} roles={}", userId, roleCodes);
    }

    /**
     * 校验角色码存在。**不静默忽略不存在的码**——
     * 那会让人以为授了权，实际什么都没发生（本项目最怕的失败形态）。
     *
     * <p><strong>同时挡住两条提权路径</strong>：
     * <ul>
     *   <li>只给「新增用户」权限的人，在请求体里塞 {@code roles} 造一个超管账号
     *       —— 授角色需要单独的 {@code iam.users.roles}</li>
     *   <li>把内置超管授给别人 —— 授出方必须自己已经是超管</li>
     * </ul>
     */
    private List<String> requireRoles(List<String> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(new LinkedHashSet<>(input));
        for (String code : out) {
            if (roles.selectByCode(properties.getTenant(), code) == null) {
                throw new IamAdminException("角色不存在：" + code);
            }
        }
        if (out.contains(com.liusongboyy.chatdata.iam.permission.PermissionCatalog.BUILTIN_SUPER_ROLE)
                && !perm.isBuiltinSuper()) {
            throw new IamAdminException(
                    "只有超级管理员才能授予「"
                            + com.liusongboyy.chatdata.iam.permission.PermissionCatalog.BUILTIN_SUPER_ROLE
                            + "」角色");
        }
        return out;
    }

    /**
     * 新建用户时若带角色，额外要求 {@code iam.users.roles}。
     *
     * <p>接口上只挂了 {@code iam.users.write}，所以这一步只能在这里补——
     * 否则"能建账号"就等于"能造一个超管账号"。
     */
    private void requireAssignPermissionWhenRolesPresent(List<String> roleCodes) {
        if (roleCodes != null && !roleCodes.isEmpty()
                && !perm.has(com.liusongboyy.chatdata.iam.permission.PermissionCatalog.IAM_USERS_ROLES)) {
            throw new IamAdminException(
                    "新建用户时指定角色需要「分配角色」权限（iam.users.roles）");
        }
    }

    /**
     * 不允许把最后一个启用中的超管账号停用。
     *
     * <p>停用之后除了改库没有别的路能进来——而那时已经没人能改库了。
     */
    private void guardSuperuserStaysUsable(long targetId, String nextStatus) {
        if (!STATUS_DISABLED.equals(nextStatus)) {
            return;
        }
        String superRole = com.liusongboyy.chatdata.iam.permission.PermissionCatalog.BUILTIN_SUPER_ROLE;
        if (users.countUserRole(targetId, superRole) == 0) {
            return;
        }
        long activeSupers = users.countActiveWithRole(properties.getTenant(), superRole);
        if (activeSupers <= 1) {
            throw new IamAdminException(
                    "不能停用最后一个超级管理员账号——之后没有任何人能进入管理台");
        }
    }

    /** 筛选值：空 = 不筛；非法值报错而不是静默忽略（否则界面会显示"全部"却少了人）。 */
    private static String normalizeStatusFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String s = status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!STATUS_ACTIVE.equals(s) && !STATUS_DISABLED.equals(s)) {
            throw new IamAdminException("状态只能是 ACTIVE 或 DISABLED");
        }
        return s;
    }

    /**
     * 组织筛选 = 该组织**及其子树**。
     *
     * <p>物化路径前缀匹配：选中"华东"要能看到华东下面门店的人，只匹配 org_id 会漏。
     */
    private String requireOrgPath(Long orgId) {
        if (orgId == null) {
            return null;
        }
        var org = orgs.selectById(orgId, properties.getTenant());
        if (org == null) {
            throw new IamAdminException("组织不存在：id=" + orgId);
        }
        String path = org.path();
        if (path == null || path.isBlank()) {
            return "/" + org.id() + "/";
        }
        return path.endsWith("/") ? path : path + "/";
    }

    /**
     * 关键字模式。
     *
     * <p>转义 {@code % _ !}：管理员搜 "100%" 时，它应该是字面量而不是通配。
     * 用 {@code !} 作转义符，避免在 Java 字符串里和反斜杠打架。
     */
    private static String likePattern(String keyword) {
        String kw = blankToNull(keyword);
        if (kw == null) {
            return null;
        }
        String escaped = kw.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    private void requireUser(long id) {
        if (users.selectById(id) == null) {
            throw new IamAdminException("用户不存在：id=" + id);
        }
    }

    private void requireOrg(long id) {
        if (orgs.selectById(id, properties.getTenant()) == null) {
            throw new IamAdminException("组织不存在：id=" + id);
        }
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

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return STATUS_ACTIVE;
        }
        String s = status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!STATUS_ACTIVE.equals(s) && !STATUS_DISABLED.equals(s)) {
            throw new IamAdminException("状态只能是 ACTIVE 或 DISABLED");
        }
        return s;
    }

    /** 一页用户。{@code page} 是服务端纠正过的实际页码。 */
    public record UserPage(List<UserView> items, long total, int page, int size) {
    }

    /** 顶部三个数字。 */
    public record UserStats(long total, long active, long disabled, long orgs) {
    }

    /** 列表/详情用的一行。{@code roles} 由 GROUP_CONCAT 的逗号串拆开。 */
    public record UserView(long id, String username, String displayName, String status,
                           String createdAt, String orgName, Long orgId, List<String> roles,
                           /** 外部身份来源（feishu/dingtalk…），未绑定为 null。 */
                           String externalProvider,
                           /** 外部身份 id（如飞书 open_id），未绑定为 null。 */
                           String externalId) {
        static UserView of(UserMapper.AdminUserRow row) {
            List<String> roleList = row.roleCodes() == null || row.roleCodes().isBlank()
                    ? List.of()
                    : List.of(row.roleCodes().split(","));
            return new UserView(row.id(), row.username(), row.displayName(), row.status(),
                    row.createdAt() == null ? null : row.createdAt().toString(),
                    row.orgName(), row.orgId(), roleList,
                    row.externalProvider(), row.externalId());
        }
    }
}

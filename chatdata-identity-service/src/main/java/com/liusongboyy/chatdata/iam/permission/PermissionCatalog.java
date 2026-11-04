package com.liusongboyy.chatdata.iam.permission;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 功能权限目录：**唯一的一份定义**。
 *
 * <h2>它同时是三样东西</h2>
 *
 * <ol>
 *   <li>角色权限配置界面的数据源（{@code GET /iam/admin/permissions}）</li>
 *   <li>保存时的校验基准——未知权限码直接拒绝，库里不会积累永远不会生效的脏码</li>
 *   <li>{@code @PreAuthorize("@perm.has(...)")} 里那些字符串的出处</li>
 * </ol>
 *
 * <p>前端的 {@code v-if="can(...)"} 仍需各自写一遍码（组件得知道自己的码），
 * 但**标签与分组来自接口**，所以文案不会两边漂移。{@code CatalogCoverageTest}
 * 会扫描两侧所有 {@code @PreAuthorize} 字面量，断言它们都在这里。
 *
 * <h2>为什么粒度是「页面 + 写 + 删」而不是每个按钮一个码</h2>
 *
 * <p>创建与编辑几乎总是一起授予，拆开只会让配置界面从 3 行变成 9 行；
 * 但**删除单独留下**——它不可逆。问数页的图表形态收敛成一个
 * {@link #ASK_UI_CHARTS}：四种图形是展示偏好，数据已整份返回，
 * 拆成四个码是假的安全感。
 *
 * <h2>三层结构：模块 → 页面 → 动作</h2>
 *
 * <p>模块节点（{@code iam} / {@code ask.admin} / {@code ask.ui}）只是分组，
 * **不可勾选**；它的码不会出现在任何 {@code @PreAuthorize} 里。
 */
public final class PermissionCatalog {

    // ── IAM 账号中心 ──
    public static final String IAM_USERS = "iam.users";
    public static final String IAM_USERS_WRITE = "iam.users.write";
    public static final String IAM_USERS_ROLES = "iam.users.roles";
    public static final String IAM_USERS_RESET_PASSWORD = "iam.users.resetPassword";
    public static final String IAM_ORGS = "iam.orgs";
    public static final String IAM_ORGS_WRITE = "iam.orgs.write";
    public static final String IAM_ROLES = "iam.roles";
    public static final String IAM_ROLES_WRITE = "iam.roles.write";
    public static final String IAM_ROLES_PERMISSIONS = "iam.roles.permissions";

    // ── 问数管理台 ──
    public static final String ASK_ADMIN_DATASOURCES = "ask.admin.datasources";
    public static final String ASK_ADMIN_DATASOURCES_WRITE = "ask.admin.datasources.write";
    public static final String ASK_ADMIN_DATASOURCES_DELETE = "ask.admin.datasources.delete";
    public static final String ASK_ADMIN_DATASOURCES_TEST = "ask.admin.datasources.test";
    public static final String ASK_ADMIN_MODELS = "ask.admin.models";
    public static final String ASK_ADMIN_MODELS_WRITE = "ask.admin.models.write";
    public static final String ASK_ADMIN_MODELS_TEST = "ask.admin.models.test";
    public static final String ASK_ADMIN_DOMAINS = "ask.admin.domains";
    public static final String ASK_ADMIN_DOMAINS_WRITE = "ask.admin.domains.write";
    public static final String ASK_ADMIN_DOMAINS_DELETE = "ask.admin.domains.delete";
    public static final String ASK_ADMIN_DOMAINS_TABLES = "ask.admin.domains.tables";
    public static final String ASK_ADMIN_ASSISTANTS = "ask.admin.assistants";
    public static final String ASK_ADMIN_ASSISTANTS_WRITE = "ask.admin.assistants.write";
    public static final String ASK_ADMIN_ASSISTANTS_DELETE = "ask.admin.assistants.delete";
    public static final String ASK_ADMIN_FEEDBACK = "ask.admin.feedback";
    public static final String ASK_ADMIN_FEEDBACK_REVIEW = "ask.admin.feedback.review";

    // ── 问数页 ──
    public static final String ASK_UI_CHARTS = "ask.ui.charts";
    public static final String ASK_UI_FEEDBACK = "ask.ui.feedback";
    public static final String ASK_UI_FEEDBACK_CORRECT = "ask.ui.feedback.correct";

    /**
     * 内置超管角色码。
     *
     * <p>持有它的用户**自动拥有全部权限**：不落 {@code iam_role_permission}、界面只读、
     * 不可停用。这样：
     *
     * <ul>
     *   <li>改错任何别的角色，都还有一条路进来</li>
     *   <li>不必再造一个"第五种角色"的概念</li>
     * </ul>
     *
     * <h2>为什么是独立的一个角色码，而不是复用 {@code chat2bi}</h2>
     *
     * <p>{@code chat2bi} 的名字是「问数管理台」，它在业务上指的是"能配数据源/语义域/助理"，
     * 把它同时当成"不能删的超级管理员"会让运维在角色列表里**认不出哪一个才是那道保险**。
     * 两者拆开之后语义各归其位：
     *
     * <table>
     *   <tr><th></th><th>{@code super_admin}</th><th>{@code chat2bi}</th></tr>
     *   <tr><td>权限</td><td>全部，代码展开</td><td>按配置，预置了管理台的码</td></tr>
     *   <tr><td>可否编辑</td><td>不可</td><td>可以</td></tr>
     *   <tr><td>可否停用</td><td>不可</td><td>可以</td></tr>
     *   <tr><td>用途</td><td>兜底（break-glass）</td><td>日常的管理台角色</td></tr>
     * </table>
     */
    public static final String BUILTIN_SUPER_ROLE = "super_admin";

    /** 内置超管的显示名。建角色时用它。 */
    public static final String BUILTIN_SUPER_ROLE_NAME = "超级管理员";

    /**
     * 曾经的超管角色码。
     *
     * <p>迁移用：{@code 08-bootstrap.sql} 会把持有它的用户一并授上
     * {@link #BUILTIN_SUPER_ROLE}，否则切换的那一刻现有管理员会被锁在门外。
     */
    public static final String LEGACY_SUPER_ROLE = "chat2bi";

    /** 节点类型。模块节点只用于分组，不可勾选。 */
    public enum Kind {
        MODULE,
        PAGE,
        ACTION
    }

    /**
     * @param code      权限码；模块节点也有码但不可选
     * @param label     显示名
     * @param kind      节点类型
     * @param sensitive 是否是"等于部分超管能力"的码。配置界面要单独提示
     * @param children  子节点
     */
    public record Node(String code, String label, Kind kind, boolean sensitive,
                       List<Node> children) {
        public Node {
            children = children == null ? List.of() : List.copyOf(children);
        }

        static Node module(String code, String label, Node... children) {
            return new Node(code, label, Kind.MODULE, false, List.of(children));
        }

        static Node page(String code, String label, Node... children) {
            return new Node(code, label, Kind.PAGE, false, List.of(children));
        }

        static Node action(String code, String label) {
            return new Node(code, label, Kind.ACTION, false, List.of());
        }

        /** 敏感动作：单项就等于相当大的权限，配置时要额外提示。 */
        static Node sensitiveAction(String code, String label) {
            return new Node(code, label, Kind.ACTION, true, List.of());
        }
    }

    /**
     * 目录树。顺序即界面顺序。
     *
     * <p>{@code sensitive} 的判据是"漏授/误授会造成实质损害"：
     * 重置密码能接管账号、配角色权限等于给自己发权限、改数据策略能改行/列可见性、
     * 数据源写/试连能改连接受控库与探测内网。
     */
    public static final List<Node> TREE = List.of(
            Node.module("iam", "IAM 账号中心",
                    Node.page(IAM_USERS, "用户管理",
                            Node.action(IAM_USERS_WRITE, "新增 / 编辑用户"),
                            Node.sensitiveAction(IAM_USERS_ROLES, "分配角色"),
                            Node.sensitiveAction(IAM_USERS_RESET_PASSWORD, "重置密码")),
                    Node.page(IAM_ORGS, "组织管理",
                            Node.action(IAM_ORGS_WRITE, "新增 / 编辑组织")),
                    Node.page(IAM_ROLES, "角色管理",
                            Node.action(IAM_ROLES_WRITE, "新增 / 编辑角色"),
                            Node.sensitiveAction(IAM_ROLES_PERMISSIONS, "配置功能权限"))),

            Node.module("ask.admin", "问数管理台",
                    Node.page(ASK_ADMIN_DATASOURCES, "数据源",
                            Node.action(ASK_ADMIN_DATASOURCES_WRITE, "新增 / 编辑数据源"),
                            Node.action(ASK_ADMIN_DATASOURCES_DELETE, "删除数据源"),
                            Node.sensitiveAction(ASK_ADMIN_DATASOURCES_TEST, "测试连接")),
                    Node.page(ASK_ADMIN_MODELS, "模型",
                            Node.action(ASK_ADMIN_MODELS_WRITE, "新增 / 编辑 / 启停"),
                            // 测试会真发一次请求，产生真实费用，故与"测试连接"同级敏感
                            Node.sensitiveAction(ASK_ADMIN_MODELS_TEST, "测试连通性")),
                    Node.page(ASK_ADMIN_DOMAINS, "语义域",
                            Node.action(ASK_ADMIN_DOMAINS_WRITE, "新增 / 编辑语义域"),
                            Node.action(ASK_ADMIN_DOMAINS_DELETE, "删除语义域"),
                            Node.action(ASK_ADMIN_DOMAINS_TABLES, "注册表 / 术语 / 主题 / 关联")),
                    Node.page(ASK_ADMIN_ASSISTANTS, "助理",
                            Node.action(ASK_ADMIN_ASSISTANTS_WRITE, "新增 / 编辑助理"),
                            Node.action(ASK_ADMIN_ASSISTANTS_DELETE, "删除助理")),
                    Node.page(ASK_ADMIN_FEEDBACK, "反馈审核",
                            Node.action(ASK_ADMIN_FEEDBACK_REVIEW, "通过 / 拒绝"))),

            Node.module("ask.ui", "问数页",
                    Node.action(ASK_UI_CHARTS, "图表形态切换"),
                    Node.action(ASK_UI_FEEDBACK, "反馈（有帮助 / 答得不对）"),
                    Node.action(ASK_UI_FEEDBACK_CORRECT, "提交修正 SQL"))
    );

    private PermissionCatalog() {
    }

    /** 全部可勾选的码（不含模块节点）。 */
    public static Set<String> allCodes() {
        Set<String> out = new LinkedHashSet<>();
        collect(TREE, out);
        return Set.copyOf(out);
    }

    /** 所有管理台的页面码。用于"有任一管理页权限即可进入管理台"。 */
    public static List<String> adminPageCodes() {
        List<String> out = new ArrayList<>();
        for (Node module : TREE) {
            if (!"ask.ui".equals(module.code())) {
                for (Node page : module.children()) {
                    out.add(page.code());
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * 「问数管理台」角色的预置权限：除问数页的展示偏好之外的全部。
     *
     * <p>{@code chat2bi} 的描述是"可配置数据源、语义域、助手与反馈审核。不参与问数"，
     * 所以它要管理面的一切、但不要 {@code ask.ui.*}——那些是问数页里的图表与反馈，
     * 一个不参与问数的管理角色不该有。
     *
     * <p>这不是超管：它是**可编辑**的普通角色，管理员可以按需收窄。
     */
    public static Set<String> adminConsolePreset() {
        Set<String> out = new LinkedHashSet<>();
        for (String code : allCodes()) {
            if (!code.startsWith("ask.ui.")) {
                out.add(code);
            }
        }
        return Set.copyOf(out);
    }

    public static boolean contains(String code) {
        return allCodes().contains(code);
    }

    /**
     * 把角色展开成权限集合。
     *
     * <p>持有 {@link #BUILTIN_SUPER_ROLE} 就是全部权限——**不看库**。这是防锁死的那道保险。
     */
    public static Set<String> expand(Set<String> roleCodes, Set<String> configured) {
        if (roleCodes != null && roleCodes.contains(BUILTIN_SUPER_ROLE)) {
            return allCodes();
        }
        return configured == null ? Set.of() : Set.copyOf(configured);
    }

    /**
     * 补全父级：勾了子项页面就必须能看到。
     *
     * <p>不补的话会出现"能点新增但看不到那个页面"的自相矛盾状态。
     */
    public static Set<String> withParents(Set<String> codes) {
        Set<String> out = new LinkedHashSet<>(codes == null ? Set.of() : codes);
        for (String code : new LinkedHashSet<>(out)) {
            String parent = parentOf(code);
            if (parent != null) {
                out.add(parent);
            }
        }
        return out;
    }

    /** @return 该码所属的页面码；本身是页面码或未知时返回 {@code null} */
    public static String parentOf(String code) {
        for (Node module : TREE) {
            for (Node page : module.children()) {
                for (Node action : page.children()) {
                    if (action.code().equals(code)) {
                        return page.code();
                    }
                }
            }
        }
        return null;
    }

    private static void collect(List<Node> nodes, Set<String> out) {
        for (Node node : nodes) {
            if (node.kind() != Kind.MODULE) {
                out.add(node.code());
            }
            collect(node.children(), out);
        }
    }
}

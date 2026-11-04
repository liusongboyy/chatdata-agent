package com.liusongboyy.chatdata.ask.engine;

import java.util.List;
import java.util.Set;

/**
 * 问数管理面每个接口需要的功能权限。
 *
 * <h2>为什么是集中一张表，而不是 39 个 {@code @PreAuthorize}</h2>
 *
 * <p>{@code AdminEngineController} 有 39 个端点，而且其中一批是**被多个页面共用**的
 * 读接口（角色清单、数据源清单、域清单）。后者要表达成"任一页面权限即可"，
 * 逐个注解写出来既啰嗦又容易漏。
 *
 * <p>集中之后可以用一个**目录覆盖测试**兜住漂移：扫描控制器上所有
 * {@code @RequestMapping} 的路径，断言每条都在表里。漏一条就是测试失败，
 * 而不是线上某个接口悄悄用不了（或悄悄放行）。
 *
 * <p><strong>匹配不到任何规则 → 拒绝。</strong>fail-closed，与安全配置的白名单模式一致。
 *
 * <h2>共享读接口为什么是"任一"</h2>
 *
 * <p>{@code DomainListView} 用 {@code Promise.all([listDatasources(), listDomains()])}，
 * {@code AssistantListView} 用 {@code [listAssistants(), listDomains()]}。
 * 如果 {@code GET /datasources} 只认数据源页权限，那么只有"语义域"权限的人
 * 打开语义域页时会因为这一个 403 而**整页空白**——而真正的拒绝原因是无关的。
 */
public final class AdminApiPermissions {

    /** 管理面的公共前缀。 */
    private static final String BASE = "/api/engine/admin";

    /**
     * 一条规则。
     *
     * @param method      HTTP 方法
     * @param path        相对 {@link #BASE} 的 Ant 风格路径（{@code *} 只跨单段）
     * @param codes       任一即可
     */
    private record Rule(String method, String path, String... codes) {
    }

    // 权限码常量直接写字面量：ask 不依赖 IAM 模块，import 不过来。
    // 漂移由 CatalogCoverageTest 兜住（它核对两侧所有字面量都在目录里）
    private static final String DS = "ask.admin.datasources";
    private static final String DS_WRITE = "ask.admin.datasources.write";
    private static final String DS_DELETE = "ask.admin.datasources.delete";
    private static final String DS_TEST = "ask.admin.datasources.test";

    private static final String DM = "ask.admin.domains";
    private static final String DM_WRITE = "ask.admin.domains.write";
    private static final String DM_DELETE = "ask.admin.domains.delete";
    private static final String DM_TABLES = "ask.admin.domains.tables";

    private static final String AS = "ask.admin.assistants";
    private static final String AS_WRITE = "ask.admin.assistants.write";
    private static final String AS_DELETE = "ask.admin.assistants.delete";

    private static final String FB = "ask.admin.feedback";
    private static final String FB_REVIEW = "ask.admin.feedback.review";

    private static final List<Rule> RULES = List.of(
            // ── 共用读接口 ──
            new Rule("GET", "/roles", DM, AS),
            new Rule("GET", "/datasources", DS, DM, DM_TABLES),
            new Rule("GET", "/domains", DM, AS, FB),
            new Rule("GET", "/user-options", DM, AS, DM_WRITE, AS_WRITE),

            // ── 数据源 ──
            new Rule("POST", "/datasources", DS_WRITE),
            new Rule("PUT", "/datasources/*", DS_WRITE),
            new Rule("PUT", "/datasources/*/password", DS_WRITE),
            new Rule("DELETE", "/datasources/*", DS_DELETE),
            new Rule("POST", "/datasources/test", DS_TEST),
            new Rule("POST", "/datasources/*/test", DS_TEST),
            new Rule("GET", "/datasources/*/tables", DS, DM_TABLES),
            new Rule("GET", "/datasources/*/tables/*/columns", DS, DM_TABLES),
            // 生成建模草稿：读数据源元数据 + 返回可编辑草稿，**不落库**。
            // 与 tables 同档——它做的事等价于"把表结构导进注册表"，只是多了一层推断
            new Rule("POST", "/datasources/*/modeling-draft", DS, DM_TABLES),

            // 健康度概览：读审计日志 + 各域覆盖度。
            // 与「语义域」同档——**看健康度的人就是配域的人**，
            // 不另设权限码：多一个码就多一次"谁能看"的讨论，而收益为零
            new Rule("GET", "/health/overview", DM),

            // ── 语义域 ──
            new Rule("POST", "/domains", DM_WRITE),
            new Rule("PUT", "/domains/*", DM_WRITE),
            new Rule("DELETE", "/domains/*", DM_DELETE),
            new Rule("PUT", "/domains/*/joins", DM_TABLES),
            // 覆盖度检查只读配置，与 tables 同一档权限
            new Rule("GET", "/domains/*/modeling-check", DM_TABLES),
            // 列画像会写回 columns_json，属于注册表维护，与 tables 同一档权限
            new Rule("POST", "/domains/*/profile", DM_TABLES),

            // ── 注册表 / 术语 / 主题 / SQL 范例：都归"注册表"这一个内容码 ──
            new Rule("GET", "/domains/*/tables", DM_TABLES),
            new Rule("POST", "/domains/*/tables", DM_TABLES),
            new Rule("PUT", "/tables/*", DM_TABLES),
            new Rule("DELETE", "/tables/*", DM_TABLES),
            new Rule("GET", "/domains/*/terms", DM_TABLES),
            new Rule("POST", "/domains/*/terms", DM_TABLES),
            new Rule("PUT", "/terms/*", DM_TABLES),
            new Rule("DELETE", "/terms/*", DM_TABLES),
            new Rule("GET", "/domains/*/topics", DM_TABLES),
            new Rule("POST", "/domains/*/topics", DM_TABLES),
            new Rule("PUT", "/topics/*", DM_TABLES),
            new Rule("DELETE", "/topics/*", DM_TABLES),
            new Rule("GET", "/domains/*/sql-examples", DM_TABLES),
            new Rule("POST", "/domains/*/sql-examples", DM_TABLES),
            new Rule("PUT", "/sql-examples/*", DM_TABLES),
            new Rule("DELETE", "/sql-examples/*", DM_TABLES),

            // ── 助理 ──
            new Rule("GET", "/assistants", AS),
            new Rule("POST", "/assistants", AS_WRITE),
            new Rule("PUT", "/assistants/*", AS_WRITE),
            new Rule("DELETE", "/assistants/*", AS_DELETE),

            // ── 反馈审核 ──
            new Rule("GET", "/feedback", FB),
            new Rule("POST", "/feedback/*/approve", FB_REVIEW),
            new Rule("POST", "/feedback/*/reject", FB_REVIEW)
    );

    private AdminApiPermissions() {
    }

    /**
     * @param method  HTTP 方法
     * @param path    请求路径（含 {@link #BASE} 前缀）
     * @param granted 当前主体持有的功能权限
     * @return 匹配到规则且持有其中一个码时为 true；**匹配不到返回 false**
     */
    public static boolean allows(String method, String path, Set<String> granted) {
        if (method == null || path == null) {
            return false;
        }
        if (!path.startsWith(BASE)) {
            return false;
        }
        String rest = path.substring(BASE.length());
        if (rest.isEmpty()) {
            rest = "/";
        }

        for (Rule rule : RULES) {
            if (!rule.method().equalsIgnoreCase(method)) {
                continue;
            }
            if (!matches(rule.path(), rest)) {
                continue;
            }
            for (String code : rule.codes()) {
                if (granted.contains(code)) {
                    return true;
                }
            }
            return false;   // 路径匹配上了但没权限：明确拒绝，不再去找下一条
        }
        return false;       // 没有规则 = 拒绝（fail-closed）
    }

    /** Ant 风格的单段匹配。只用 {@code *}，不引入完整 AntPathMatcher 的复杂度。 */
    private static boolean matches(String pattern, String path) {
        String[] p = pattern.split("/", -1);
        String[] a = path.split("/", -1);
        if (p.length != a.length) {
            return false;
        }
        for (int i = 0; i < p.length; i++) {
            if (!"*".equals(p[i]) && !p[i].equals(a[i])) {
                return false;
            }
        }
        return true;
    }

    /** 供覆盖测试用：表里所有路径模板。 */
    public static List<String> patterns() {
        return RULES.stream().map(r -> r.method() + " " + r.path()).toList();
    }

    /** 供覆盖测试用：表里出现的全部权限码。 */
    public static Set<String> allCodes() {
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        for (Rule rule : RULES) {
            out.addAll(List.of(rule.codes()));
        }
        return out;
    }
}

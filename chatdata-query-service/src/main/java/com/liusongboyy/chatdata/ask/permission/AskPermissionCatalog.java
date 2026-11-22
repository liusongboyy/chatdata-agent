package com.liusongboyy.chatdata.ask.permission;

import java.util.List;

/**
 * 问数的权限清单：**权限码的事实来源**。
 *
 * <h2>它为什么在问数这边，而不是 IAM</h2>
 *
 * <p>权限码是代码的一部分——{@code @PreAuthorize("@perm.has('ask.admin.models')")}
 * 写在问数的控制器上，那个字符串就是事实。
 *
 * <p>阶段 3 之前这份清单躺在 IAM 的 {@code PermissionCatalog} 里（27 条
 * {@code ASK_ADMIN_*} 常量）。那时问数加一个管理页，要在 IAM 改代码、
 * 重新发布 IAM——两个仓库拆开了，这个反向耦合反而更刺眼。
 *
 * <p>现在由问数启动时上报给 IAM（见 {@link PermissionReporter}），
 * IAM 存下来、审核后生效。**改权限清单只改这个文件，IAM 不用发版。**
 *
 * <h2>与前端的关系</h2>
 *
 * <p>前端 {@code chatdata-web/src/permissions.ts} 里有一份同样的码。
 * 两份都要改——但这是**编译期**的重复，而不是运行时的耦合：
 * 改错了前端的表现为"按钮不出现"，而不是权限静默失效。
 * 由 {@code AskPermissionCatalogTest} 扫描两侧字面量兜住。
 */
public final class AskPermissionCatalog {

    // ── 模块节点（不可勾选，只做分组）──
    public static final String ADMIN = "ask.admin";
    public static final String UI = "ask.ui";

    // ── 管理台 ──
    public static final String ADMIN_DATASOURCES = "ask.admin.datasources";
    public static final String ADMIN_DATASOURCES_WRITE = "ask.admin.datasources.write";
    public static final String ADMIN_DATASOURCES_DELETE = "ask.admin.datasources.delete";
    public static final String ADMIN_DATASOURCES_TEST = "ask.admin.datasources.test";

    public static final String ADMIN_MODELS = "ask.admin.models";
    public static final String ADMIN_MODELS_WRITE = "ask.admin.models.write";
    public static final String ADMIN_MODELS_TEST = "ask.admin.models.test";

    public static final String ADMIN_DOMAINS = "ask.admin.domains";
    public static final String ADMIN_DOMAINS_WRITE = "ask.admin.domains.write";
    public static final String ADMIN_DOMAINS_DELETE = "ask.admin.domains.delete";
    public static final String ADMIN_DOMAINS_TABLES = "ask.admin.domains.tables";

    public static final String ADMIN_ASSISTANTS = "ask.admin.assistants";
    public static final String ADMIN_ASSISTANTS_WRITE = "ask.admin.assistants.write";
    public static final String ADMIN_ASSISTANTS_DELETE = "ask.admin.assistants.delete";

    public static final String ADMIN_FEEDBACK = "ask.admin.feedback";
    public static final String ADMIN_FEEDBACK_REVIEW = "ask.admin.feedback.review";

    // ── 问数页 ──
    public static final String UI_CHARTS = "ask.ui.charts";
    public static final String UI_FEEDBACK = "ask.ui.feedback";
    public static final String UI_FEEDBACK_CORRECT = "ask.ui.feedback.correct";

    private AskPermissionCatalog() {
    }

    /**
     * 上报给它 IAM 的完整清单。
     *
     * <p>{@code parent} 显式给，不从 code 推导：{@code ask.ui.feedback.correct}
     * 的父级是 {@code ask.ui.feedback}，而如果按"去掉最后一段"的规则推导，
     * 会得到 {@code ask.ui.feedback}——这次恰好对。但 {@code ask.admin.datasources}
     * 的父是 {@code ask.admin}，推导也对。**规则看起来总能对，是因为现在的命名
     * 恰好规整**；某天出现一个不规整的码时，推导会让权限树显示错乱，而错乱不报错。
     */
    public static final List<Item> ALL = List.of(
            // 管理台
            new Item(ADMIN, "问数管理台", Kind.MODULE, null, false),

            new Item(ADMIN_DATASOURCES, "数据源", Kind.PAGE, ADMIN, false),
            new Item(ADMIN_DATASOURCES_WRITE, "新增 / 编辑数据源", Kind.ACTION,
                    ADMIN_DATASOURCES, false),
            new Item(ADMIN_DATASOURCES_DELETE, "删除数据源", Kind.ACTION,
                    ADMIN_DATASOURCES, false),
            // 测试连接会探测内网，与"能看数据源"不是一档
            new Item(ADMIN_DATASOURCES_TEST, "测试连接", Kind.ACTION,
                    ADMIN_DATASOURCES, true),

            new Item(ADMIN_MODELS, "模型", Kind.PAGE, ADMIN, false),
            new Item(ADMIN_MODELS_WRITE, "新增 / 编辑 / 启停", Kind.ACTION,
                    ADMIN_MODELS, false),
            // 测试会真发一次请求、产生真实费用
            new Item(ADMIN_MODELS_TEST, "测试连通性", Kind.ACTION, ADMIN_MODELS, true),

            new Item(ADMIN_DOMAINS, "语义域", Kind.PAGE, ADMIN, false),
            new Item(ADMIN_DOMAINS_WRITE, "新增 / 编辑语义域", Kind.ACTION,
                    ADMIN_DOMAINS, false),
            new Item(ADMIN_DOMAINS_DELETE, "删除语义域", Kind.ACTION,
                    ADMIN_DOMAINS, false),
            new Item(ADMIN_DOMAINS_TABLES, "注册表 / 术语 / 主题 / 关联", Kind.ACTION,
                    ADMIN_DOMAINS, false),

            new Item(ADMIN_ASSISTANTS, "助理", Kind.PAGE, ADMIN, false),
            new Item(ADMIN_ASSISTANTS_WRITE, "新增 / 编辑助理", Kind.ACTION,
                    ADMIN_ASSISTANTS, false),
            new Item(ADMIN_ASSISTANTS_DELETE, "删除助理", Kind.ACTION,
                    ADMIN_ASSISTANTS, false),

            new Item(ADMIN_FEEDBACK, "反馈审核", Kind.PAGE, ADMIN, false),
            new Item(ADMIN_FEEDBACK_REVIEW, "通过 / 拒绝", Kind.ACTION,
                    ADMIN_FEEDBACK, false),

            // 问数页
            new Item(UI, "问数页", Kind.MODULE, null, false),
            new Item(UI_CHARTS, "图表形态切换", Kind.ACTION, UI, false),
            new Item(UI_FEEDBACK, "反馈（有帮助 / 答得不对）", Kind.ACTION, UI, false),
            new Item(UI_FEEDBACK_CORRECT, "提交修正 SQL", Kind.ACTION, UI_FEEDBACK, false)
    );

    /** 上报的节点类型。与 IAM 的 {@code PermissionKind} 一一对应。 */
    public enum Kind {
        MODULE,
        PAGE,
        ACTION,
        DATA
    }

    /**
     * 上报的一项。
     *
     * @param parent    显式给，不从 code 推导（见 {@link #ALL} 的说明）
     * @param sensitive 是否是"等于部分超管能力"的码，配置界面要单独提示
     */
    public record Item(String code, String label, Kind kind, String parent,
                       boolean sensitive) {
    }
}

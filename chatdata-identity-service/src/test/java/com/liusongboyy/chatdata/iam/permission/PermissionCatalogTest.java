package com.liusongboyy.chatdata.iam.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限目录。
 *
 * <p>目录是**唯一的一份定义**：配置界面、保存校验、{@code @PreAuthorize} 的字符串都从它来。
 * 它错了不会报错，只会表现为"某个按钮永远不出现"或"某个接口谁也用不了"。
 */
class PermissionCatalogTest {

    @Test
    @DisplayName("权限码不重复")
    void codesAreUnique() {
        List<String> all = List.copyOf(PermissionCatalog.allCodes());
        assertThat(new HashSet<>(all)).hasSameSizeAs(all);
    }

    @Test
    @DisplayName("每个节点都有码和标签，模块节点不参与勾选")
    void nodesAreWellFormed() {
        for (PermissionCatalog.Node module : PermissionCatalog.TREE) {
            assertThat(module.code()).isNotBlank();
            assertThat(module.label()).isNotBlank();
            assertThat(module.kind()).isEqualTo(PermissionCatalog.Kind.MODULE);

            for (PermissionCatalog.Node child : module.children()) {
                assertThat(child.code()).startsWith(module.code() + ".");
                assertThat(child.label()).isNotBlank();
                // 模块的直接子节点可能是页面（管理台），也可能是动作（问数页——
                // 那页本身不做准入，只有图表/反馈这些可配项）
                assertThat(child.kind()).isIn(
                        PermissionCatalog.Kind.PAGE, PermissionCatalog.Kind.ACTION);

                if (child.kind() == PermissionCatalog.Kind.PAGE) {
                    for (PermissionCatalog.Node action : child.children()) {
                        assertThat(action.code()).startsWith(child.code() + ".");
                        assertThat(action.label()).isNotBlank();
                        assertThat(action.kind()).isEqualTo(PermissionCatalog.Kind.ACTION);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("★ 模块节点不进 allCodes：它不可勾选，进了就会存下一个永远不会被判定的码")
    void moduleCodesAreNotSelectable() {
        Set<String> all = PermissionCatalog.allCodes();
        for (PermissionCatalog.Node module : PermissionCatalog.TREE) {
            assertThat(all).doesNotContain(module.code());
        }
        assertThat(all).contains("iam.users", "ask.admin.feedback");
    }

    @Test
    @DisplayName("★ 子码蕴含父页面码：否则会出现「能点新增但看不到那个页面」")
    void childImpliesParent() {
        Set<String> normalized = PermissionCatalog.withParents(Set.of("iam.users.write"));

        assertThat(normalized).containsExactlyInAnyOrder("iam.users.write", "iam.users");
    }

    @Test
    @DisplayName("页面码没有父级，补全后不变")
    void pageHasNoParent() {
        assertThat(PermissionCatalog.parentOf("iam.users")).isNull();
        assertThat(PermissionCatalog.withParents(Set.of("iam.users")))
                .containsExactly("iam.users");
    }

    @Test
    @DisplayName("★ 内置超管展开为全部权限——这是防锁死的那道保险")
    void builtinSuperGetsEverything() {
        Set<String> expanded = PermissionCatalog.expand(
                Set.of(PermissionCatalog.BUILTIN_SUPER_ROLE), Set.of());

        assertThat(expanded).isEqualTo(PermissionCatalog.allCodes());
        assertThat(expanded).isNotEmpty();
    }

    @Test
    @DisplayName("普通角色只拿到配置的那些码")
    void normalRoleGetsConfiguredOnly() {
        Set<String> expanded = PermissionCatalog.expand(Set.of("分析师"), Set.of("iam.users"));

        assertThat(expanded).containsExactly("iam.users");
    }

    @Test
    @DisplayName("管理页清单不含 ask.ui.*：那是问数页的展示偏好，不该让人进管理台")
    void adminPagesExcludeAskUi() {
        List<String> pages = PermissionCatalog.adminPageCodes();

        assertThat(pages).contains("ask.admin.datasources", "iam.users");
        assertThat(pages).noneMatch(code -> code.startsWith("ask.ui."));
    }

    @Test
    @DisplayName("★ 内置超管是独立角色码，不复用 chat2bi")
    void builtinSuperIsItsOwnRole() {
        // chat2bi 的名字是「问数管理台」。把它同时当成"不能删的超级管理员"，
        // 运维在角色列表里就认不出哪一个才是那道保险
        assertThat(PermissionCatalog.BUILTIN_SUPER_ROLE).isEqualTo("super_admin");
        assertThat(PermissionCatalog.BUILTIN_SUPER_ROLE)
                .isNotEqualTo(PermissionCatalog.LEGACY_SUPER_ROLE);
    }

    @Test
    @DisplayName("★ 问数管理台的预置权限：管理面全给，但不含 ask.ui.*（它不参与问数）")
    void adminConsolePresetExcludesAskUi() {
        Set<String> preset = PermissionCatalog.adminConsolePreset();

        assertThat(preset).contains(
                PermissionCatalog.IAM_USERS,
                PermissionCatalog.IAM_ROLES_PERMISSIONS,
                PermissionCatalog.ASK_ADMIN_FEEDBACK_REVIEW);
        assertThat(preset).noneMatch(code -> code.startsWith("ask.ui."));
        // 它是**可编辑的普通角色**，不是超管：所以不等于全量
        assertThat(preset).isNotEqualTo(PermissionCatalog.allCodes());
        assertThat(PermissionCatalog.allCodes()).containsAll(preset);
    }

    @Test
    @DisplayName("★ 敏感码被标出来（重置密码、配角色权限、测数据源…）")
    void sensitiveCodesAreMarked() {
        Set<String> sensitive = new HashSet<>();
        collectSensitive(PermissionCatalog.TREE, sensitive);

        assertThat(sensitive).contains(
                PermissionCatalog.IAM_USERS_ROLES,
                PermissionCatalog.IAM_USERS_RESET_PASSWORD,
                PermissionCatalog.IAM_ROLES_PERMISSIONS,
                PermissionCatalog.ASK_ADMIN_DATASOURCES_TEST);
        // 普通动作不该被标成敏感，否则提示会失去意义
        assertThat(sensitive).doesNotContain(PermissionCatalog.IAM_ORGS_WRITE);
    }

    /**
     * 问数侧的权限码必须都在目录里。
     *
     * <p>这份清单与 {@code chatdata-query-service} 的 {@code AdminApiPermissions} 是**重复的**——
     * ask 不依赖 IAM 模块，import 不过来。重复是可接受的代价：两边不一致时这个测试会失败，
     * 而不是等到线上某个接口对所有人 403 才发现。真正的修法是把目录挪进一个共享模块。
     */
    @Test
    @DisplayName("★ ask 侧用到的码都在目录里（跨模块防漂移）")
    void askCodesExist() {
        List<String> askCodes = List.of(
                "ask.admin.datasources", "ask.admin.datasources.write",
                "ask.admin.datasources.delete", "ask.admin.datasources.test",
                "ask.admin.domains", "ask.admin.domains.write",
                "ask.admin.domains.delete", "ask.admin.domains.tables",
                "ask.admin.assistants", "ask.admin.assistants.write",
                "ask.admin.assistants.delete",
                "ask.admin.feedback", "ask.admin.feedback.review",
                "ask.ui.charts", "ask.ui.feedback", "ask.ui.feedback.correct");

        assertThat(PermissionCatalog.allCodes()).containsAll(askCodes);
    }

    private static void collectSensitive(List<PermissionCatalog.Node> nodes, Set<String> out) {
        for (PermissionCatalog.Node node : nodes) {
            if (node.sensitive()) {
                out.add(node.code());
            }
            collectSensitive(node.children(), out);
        }
    }
}

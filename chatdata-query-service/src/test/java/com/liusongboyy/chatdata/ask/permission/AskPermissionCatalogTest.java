package com.liusongboyy.chatdata.ask.permission;

import com.liusongboyy.chatdata.ask.engine.AdminApiPermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限清单的自洽性。
 *
 * <h2>它挡的是"代码里用了但清单里没有"</h2>
 *
 * <p>{@code AdminApiPermissions} 里的码必须全部出现在
 * {@link AskPermissionCatalog#ALL} 里。漏一个的后果是：IAM 里没有这个码 →
 * 角色配不了它 → 那个接口**谁也调不通**。
 *
 * <p>而 {@code AdminApiPermissions} 是 fail-closed 的（匹配不到规则就拒绝），
 * 所以症状是"某个管理页打不开"，日志里只有 403，看不出是权限码缺失。
 */
class AskPermissionCatalogTest {

    @Test
    @DisplayName("★ 权限码唯一")
    void codesAreUnique() {
        Set<String> seen = new LinkedHashSet<>();
        for (var item : AskPermissionCatalog.ALL) {
            assertThat(seen.add(item.code()))
                    .as("重复的权限码：%s", item.code())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("★ 都以 ask. 开头（IAM 会按前缀校验，不合规直接拒收）")
    void allPrefixedWithAsk() {
        for (var item : AskPermissionCatalog.ALL) {
            assertThat(item.code())
                    .as("IAM 会拒收非 ask. 前缀的码")
                    .startsWith("ask.");
        }
    }

    @Test
    @DisplayName("★ parent 都在清单里")
    void parentsExist() {
        Set<String> codes = new LinkedHashSet<>();
        AskPermissionCatalog.ALL.forEach(i -> codes.add(i.code()));

        for (var item : AskPermissionCatalog.ALL) {
            if (item.parent() != null && !item.parent().isBlank()) {
                assertThat(codes)
                        .as("%s 的 parent「%s」不在清单里，IAM 会拒收整批",
                                item.code(), item.parent())
                        .contains(item.parent());
            }
        }
    }

    @Test
    @DisplayName("★ AdminApiPermissions 用到的码全部在清单里")
    void adminApiCodesAreAllDeclared() {
        Set<String> declared = new LinkedHashSet<>();
        AskPermissionCatalog.ALL.forEach(i -> declared.add(i.code()));

        assertThat(AdminApiPermissions.allCodes())
                .as("漏声明会让那个接口谁也调不通——而症状只是 403，看不出是权限码缺失")
                .isSubsetOf(declared);
    }

    @Test
    @DisplayName("★ MODULE 不可勾选（不该出现在 AdminApiPermissions 里）")
    void moduleCodesNotUsedAsRules() {
        Set<String> modules = new LinkedHashSet<>();
        for (var item : AskPermissionCatalog.ALL) {
            if (item.kind() == AskPermissionCatalog.Kind.MODULE) {
                modules.add(item.code());
            }
        }

        assertThat(AdminApiPermissions.allCodes())
                .as("勾一个分组没有语义——它不该被当成接口的准入判据")
                .doesNotContainAnyElementsOf(modules);
    }

    @Test
    @DisplayName("★ 导航项引用的权限码都在清单里")
    void navCodesAreDeclared() {
        Set<String> declared = new LinkedHashSet<>();
        AskPermissionCatalog.ALL.forEach(i -> declared.add(i.code()));

        for (var entry : AskPermissionCatalog.NAV) {
            java.util.List<String> codes = entry.anyPermission() != null
                    && !entry.anyPermission().isEmpty()
                    ? entry.anyPermission()
                    : (entry.permission() == null
                            ? java.util.List.of() : java.util.List.of(entry.permission()));

            assertThat(codes)
                    .as("导航项「%s」必须给 permission 或 anyPermission", entry.label())
                    .isNotEmpty();
            assertThat(declared)
                    .as("导航项「%s」引用了未声明的码——它永远不会显示",
                            entry.label())
                    .containsAll(codes);
        }
    }

    @Test
    @DisplayName("★ 导航 path 含 /app 前缀（IAM 渲染的是 <a href>，不知道应用怎么部署）")
    void navPathsAreAbsoluteAndScoped() {
        for (var entry : AskPermissionCatalog.NAV) {
            assertThat(entry.path())
                    .as("路径必须是浏览器真实路径")
                    .startsWith("/app/admin/");
        }
    }

    @Test
    @DisplayName("★ 管理台页面码都声明为 PAGE")
    void adminPagesArePages() {
        assertThat(AskPermissionCatalog.ALL)
                .filteredOn(i -> i.code().equals("ask.admin.models"))
                .singleElement()
                .satisfies(i -> assertThat(i.kind()).isEqualTo(AskPermissionCatalog.Kind.PAGE));
    }
}

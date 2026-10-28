package com.liusongboyy.chatdata.ask.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理面接口的权限规则表。
 *
 * <p>最重要的一条是**覆盖测试**：扫描 {@code AdminEngineController} 上所有
 * {@code @*Mapping} 的路径，断言每条都能在表里匹配到规则。
 * 漏一条的后果是不对称的——匹配不到就是拒绝，所以漏掉的接口会**悄悄用不了**
 * （而不是悄悄放行），但排查起来同样费劲。
 */
class AdminApiPermissionsTest {

    private static final String BASE = "/api/engine/admin";

    @Test
    @DisplayName("★ 控制器上每个映射都有对应规则（漏一个 = 那个接口谁也用不了）")
    void everyEndpointHasARule() {
        List<String> missing = new ArrayList<>();
        // 用"表里全部码"去问：匹配不到规则时返回 false，与持有多少权限无关
        Set<String> allTableCodes = AdminApiPermissions.allCodes();
        for (String endpoint : endpointsOf(AdminEngineController.class)) {
            int space = endpoint.indexOf(' ');
            String method = endpoint.substring(0, space);
            String path = endpoint.substring(space + 1);
            if (!AdminApiPermissions.allows(method, path, allTableCodes)) {
                missing.add(endpoint);
            }
        }
        assertThat(missing).as("以下接口没有权限规则").isEmpty();
    }

    @Test
    @DisplayName("★ 表里的码都是 ask.admin.*（写错一个字母 = 那个接口永远拒绝）")
    void tableCodesAreAdminCodes() {
        assertThat(AdminApiPermissions.allCodes())
                .isNotEmpty()
                .allMatch(code -> code.startsWith("ask.admin."));
    }

    @Test
    @DisplayName("★ 共享读接口：任一相关页面权限即可，否则另一个页面会整页空白")
    void sharedReadersAllowAnyConsumer() {
        // 域页要选数据源；助理页与反馈页要选域
        assertThat(AdminApiPermissions.allows("GET", BASE + "/datasources", Set.of("ask.admin.domains")))
                .isTrue();
        assertThat(AdminApiPermissions.allows("GET", BASE + "/domains", Set.of("ask.admin.assistants")))
                .isTrue();
        assertThat(AdminApiPermissions.allows("GET", BASE + "/domains", Set.of("ask.admin.feedback")))
                .isTrue();
        assertThat(AdminApiPermissions.allows("GET", BASE + "/roles", Set.of("ask.admin.assistants")))
                .isTrue();
        assertThat(AdminApiPermissions.allows("GET", BASE + "/user-options", Set.of("ask.admin.domains")))
                .isTrue();
        assertThat(AdminApiPermissions.allows("GET", BASE + "/user-options", Set.of("ask.admin.assistants")))
                .isTrue();
    }

    @Test
    @DisplayName("★ 匹配到规则但没权限 → 拒绝（不再去找下一条）")
    void matchedButUnauthorizedDenies() {
        assertThat(AdminApiPermissions.allows("DELETE", BASE + "/datasources/5",
                Set.of("ask.admin.datasources"))).isFalse();
    }

    @Test
    @DisplayName("★ 匹配不到规则 → 拒绝（fail-closed）")
    void unmatchedDenies() {
        assertThat(AdminApiPermissions.allows("GET", BASE + "/something-new",
                AdminApiPermissions.allCodes())).isFalse();
        assertThat(AdminApiPermissions.allows("GET", "/api/ask/me",
                AdminApiPermissions.allCodes())).isFalse();
        assertThat(AdminApiPermissions.allows(null, BASE + "/roles",
                AdminApiPermissions.allCodes())).isFalse();
    }

    @Test
    @DisplayName("路径按单段匹配：/datasources/* 不该吃掉 /datasources/5/password")
    void singleSegmentMatching() {
        // 只有 delete 码、没有 write 码：改密码必须被挡住
        assertThat(AdminApiPermissions.allows("PUT", BASE + "/datasources/5/password",
                Set.of("ask.admin.datasources.delete"))).isFalse();
        assertThat(AdminApiPermissions.allows("PUT", BASE + "/datasources/5/password",
                Set.of("ask.admin.datasources.write"))).isTrue();
    }

    @Test
    @DisplayName("测试连接是敏感码：只有数据源页面权限不够")
    void testConnectionNeedsItsOwnCode() {
        assertThat(AdminApiPermissions.allows("POST", BASE + "/datasources/5/test",
                Set.of("ask.admin.datasources"))).isFalse();
        assertThat(AdminApiPermissions.allows("POST", BASE + "/datasources/5/test",
                Set.of("ask.admin.datasources.test"))).isTrue();
    }

    @Test
    @DisplayName("审核动作单独成码：能看列表不等于能通过")
    void reviewNeedsItsOwnCode() {
        assertThat(AdminApiPermissions.allows("POST", BASE + "/feedback/3/approve",
                Set.of("ask.admin.feedback"))).isFalse();
        assertThat(AdminApiPermissions.allows("POST", BASE + "/feedback/3/approve",
                Set.of("ask.admin.feedback.review"))).isTrue();
    }

    /** 扫描控制器上的所有映射，拼成 {@code METHOD /path}。 */
    private static List<String> endpointsOf(Class<?> controller) {
        String base = controller.getAnnotation(RequestMapping.class).value()[0];
        Set<String> out = new LinkedHashSet<>();
        for (Method method : controller.getDeclaredMethods()) {
            out.addAll(pathsFor(method, GetMapping.class, base, "GET"));
            out.addAll(pathsFor(method, PostMapping.class, base, "POST"));
            out.addAll(pathsFor(method, PutMapping.class, base, "PUT"));
            out.addAll(pathsFor(method, DeleteMapping.class, base, "DELETE"));
        }
        return List.copyOf(out);
    }

    private static List<String> pathsFor(Method method, Class<? extends Annotation> type,
                                         String base, String verb) {
        Annotation ann = method.getAnnotation(type);
        if (ann == null) {
            return List.of();
        }
        try {
            String[] value = (String[]) type.getMethod("value").invoke(ann);
            List<String> out = new ArrayList<>();
            for (String path : value) {
                out.add(verb + " " + base + path);
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}

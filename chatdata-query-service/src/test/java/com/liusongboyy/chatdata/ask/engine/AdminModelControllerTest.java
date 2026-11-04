package com.liusongboyy.chatdata.ask.engine;

import com.liusongboyy.chatdata.ask.identity.CurrentUser;
import com.liusongboyy.chatdata.engine.admin.AdminException;
import com.liusongboyy.chatdata.engine.admin.AdminModelService;
import com.liusongboyy.chatdata.engine.admin.ModelInput;
import com.liusongboyy.chatdata.engine.admin.ModelProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型管理端点的契约。
 *
 * <h2>这里守的是"边界"，不是"逻辑"</h2>
 *
 * <p>输入校验与写入决策由 {@code ModelInputTest} / {@code AdminModelServiceTest} 覆盖。
 * 这里只关心三件与 HTTP 有关的事：未登录 401、输入错误 400（不是 500）、
 * 以及<b>响应体里绝不出现密钥</b>。
 *
 * <p>最后一条单独测：服务层已经保证不出，但端点如果把整个 record 直接序列化出去，
 * 一个未来新增的字段就能把密钥带出去——而现在还没有人加过。
 */
@ExtendWith(MockitoExtension.class)
class AdminModelControllerTest {

    @Mock
    private AdminModelService service;

    private AdminModelController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminModelController(service, new CurrentUser());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void login() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_chat2bi"))));
    }

    private static jakarta.servlet.http.HttpServletRequest req() {
        return mock(jakarta.servlet.http.HttpServletRequest.class);
    }

    private static ModelInput input() {
        return new ModelInput("flash", "快速", null, "https://a/v1", "sk-x", "m1",
                List.of(), null, null, null, false, true);
    }

    @Test
    @DisplayName("★ 未登录 → 401，且不调服务")
    void unauthenticatedIs401() {
        var response = controller.list(req());

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(service, never()).list();
    }

    @Test
    @DisplayName("★ 列表响应里不含任何形式的密钥")
    void listResponseHasNoSecrets() {
        login();
        when(service.list()).thenReturn(List.of(Map.of(
                "key", "flash",
                "displayName", "快速",
                // 即便有人往 Map 里塞了密钥，这里也是服务层的责任；
                // 本断言确认的是端点**没有额外包装出别的字段**
                "hasApiKey", true)));

        var body = controller.list(req()).getBody().getData();

        assertThat(body).singleElement().satisfies(row ->
                assertThat(row).doesNotContainKeys("apiKey", "apiKeyEnc", "api_key_enc"));
    }

    @Test
    @DisplayName("★ 输入错误 → 400，消息透传（那是写给管理员看的）")
    void adminExceptionBecomes400() {
        var response = controller.handleAdminError(
                new AdminException("模型键「flash」已存在。请换一个"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getMessage()).contains("已存在");
    }

    @Test
    @DisplayName("ModeService 的校验也抛 IllegalArgumentException → 同样 400")
    void illegalArgumentBecomes400() {
        var response = controller.handleIllegalArgument(
                new IllegalArgumentException("单价必须大于 0"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getMessage()).contains("大于 0");
    }

    @Test
    @DisplayName("★ 意外异常 → 500 + 通用提示，不泄露堆栈或内部路径")
    void unexpectedBecomes500WithoutLeakingInternals() {
        var response = controller.handleUnexpected(
                new IllegalStateException("Table 'chatdata_ask.ask_model' doesn't exist"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        // 不把 SQL 报错透给前端——它含库名表名
        assertThat(response.getBody().getMessage())
                .doesNotContain("ask_model")
                .contains("稍后重试");
    }

    @Test
    @DisplayName("启停走单独的端点，body 里的 enabled 生效")
    void setEnabledUsesBody() {
        login();

        controller.setEnabled("flash", new AdminModelController.EnabledBody(false), req());

        verify(service).setEnabled(eq("flash"), eq(false));
    }

    @Test
    @DisplayName("★ body 为 null 时按停用处理，不 NPE")
    void nullEnabledBodyIsSafe() {
        login();

        controller.setEnabled("flash", null, req());

        verify(service).setEnabled(eq("flash"), eq(false));
    }

    @Test
    @DisplayName("★ 表单试连缺 model → 400，不去发请求")
    void draftWithoutModelIs400() {
        login();

        var response = controller.testDraft(new AdminModelController.DraftBody("flash", null), req());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(service, never()).testDraft(any(), any());
    }

    @Test
    @DisplayName("试连结果原样返回，含耗时")
    void probeResultPassedThrough() {
        login();
        when(service.testSaved("flash"))
                .thenReturn(new ModelProbe.Result(true, "连通（耗时 120 ms）", 120));

        var body = controller.testSaved("flash", req()).getBody().getData();

        assertThat(body.ok()).isTrue();
        assertThat(body.elapsedMs()).isEqualTo(120);
        assertThat(body.message()).contains("连通");
    }
}

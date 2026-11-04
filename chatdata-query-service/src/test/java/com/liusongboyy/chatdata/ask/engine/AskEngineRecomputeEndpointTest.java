package com.liusongboyy.chatdata.ask.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liusongboyy.chatdata.ask.conversation.ConversationService;
import com.liusongboyy.chatdata.ask.identity.CurrentUser;
import com.liusongboyy.chatdata.engine.agent.AskDeniedException;
import com.liusongboyy.chatdata.engine.chat.AskResult;
import com.liusongboyy.chatdata.engine.chat.AskService;
import com.liusongboyy.chatdata.engine.chat.AskServiceFactory;
import com.liusongboyy.chatdata.engine.chat.AskUnderstanding;
import com.liusongboyy.chatdata.engine.chat.ChartSpec;
import com.liusongboyy.chatdata.engine.datasource.QueryResult;
import com.liusongboyy.chatdata.engine.datasource.SqlExecutor;
import com.liusongboyy.chatdata.engine.nlsql.AskDsl;
import com.liusongboyy.chatdata.engine.nlsql.RenderedSql;
import com.liusongboyy.chatdata.engine.permission.PermissionDecision;
import com.liusongboyy.chatdata.engine.semantic.SemanticException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 重算端点的契约测试。
 *
 * <h2>为什么这条链路值得单独测</h2>
 *
 * <p>它是**唯一一条不需要模型就能走完的完整问数链路**——也正因如此，
 * 在这里能验到别处验不到的东西：返回体的 JSON 形状、状态码的分类，
 * 以及"客户端传来的 DSL 到底被当成了什么"。
 *
 * <p>用 mock 而不是真库：要验的是端点契约（状态码 + 载荷形状），
 * 不是 SQL 正确性——后者由 {@code AskSqlRendererTest} 与真库测试覆盖。
 */
@ExtendWith(MockitoExtension.class)
class AskEngineRecomputeEndpointTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Mock
    private AskServiceFactory serviceFactory;
    @Mock
    private AsyncTaskExecutor workerExecutor;
    @Mock
    private ConversationService conversations;
    @Mock
    private HttpServletRequest request;
    @Mock
    private AskService askService;

    private AskEngineStreamController controller;

    @BeforeEach
    void setUp() {
        // 身份来自 SecurityContext（资源服务器内省后写入），测试里直接放一个进去
        lenient().when(conversations.ensure(any(), any(), any(), any()))
                .thenAnswer(inv -> new ConversationService.ConversationRef(
                        inv.getArgument(0), inv.getArgument(2)));
        // 这个测试只覆盖重算端点，不碰模型选择——传 null 即可。
        // 若将来它开始测 /models，要换成真的 ModelService
        controller = new AskEngineStreamController(
                serviceFactory, new CurrentUser(), conversations, workerExecutor, null);
    }

    /** 模拟已登录：资源服务器内省成功后会把 authorities 放进 SecurityContext。 */
    private void loggedInAs(String username) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                username, "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_分析师"))));
        SecurityContextHolder.setContext(context);
    }

    private void loggedOut() {
        SecurityContextHolder.clearContext();
    }

    private static AskResult sampleResult() {
        RenderedSql sql = new RenderedSql(
                "SELECT SUM(`t1`.`premium`) AS `保费` FROM `ins_policy` `t1` LIMIT 1000",
                List.of(),
                List.of(new RenderedSql.OutputColumn("保费", "SUM(`t1`.`premium`)", false)),
                "t1");
        AskDsl dsl = new AskDsl(List.of("保费"), List.of(), null, List.of());
        return new AskResult(dsl, sql, null,
                new QueryResult(List.of("保费"), List.of(List.of(100))),
                Set.of(), 12L,
                ChartSpec.table("只有一个数，用表格"),
                new AskUnderstanding(List.of("保费"), List.of(), null, List.of(),
                        new AskUnderstanding.DataScope(false, null)),
                null,
                com.liusongboyy.chatdata.engine.semantic.Domain.Mode.MODELED);
    }

    /**
     * 请求体里的 dsl 是**普通 JSON 对象**，不是任何 Jackson 的类型。
     *
     * <p>这不是随手写的：见 {@code AskEngineStreamController.RecomputeBody} 上
     * 那段关于两个 Jackson 并存的说明——用 Jackson 类型做记录组件，
     * 请求会在进入方法体之前就反序列化失败。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> dslMap() {
        try {
            return M.readValue("""
                    {"metrics":["保费"],"dimensions":[],"timeRange":null,"filters":[]}""",
                    Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private AskEngineStreamController.RecomputeBody body() {
        return new AskEngineStreamController.RecomputeBody(
                "上个月华东的保费是多少", null, "s1", null, dslMap());
    }

    @Test
    @DisplayName("未登录：401，且不去碰引擎")
    void unauthenticated() {
        loggedOut();

        assertThat(controller.recompute(body(), request).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * 请求体必须能被 **HTTP 消息转换器**反序列化。
     *
     * <h2>这条测试补的是一个大洞</h2>
     *
     * <p>上面那些用例都是直接 {@code new RecomputeBody(...)} 再调方法的，
     * **绕过了反序列化**。而端点上线后第一次真调用就 500 了：
     *
     * <pre>
     *   Cannot construct instance of `com.fasterxml.jackson.databind.JsonNode`
     *   at tools.jackson.databind-3.1.4.jar
     * </pre>
     *
     * <p>原因：classpath 上同时有 Jackson 2（{@code com.fasterxml}，agentscope/vendor 带进来的）
     * 与 Jackson 3（{@code tools.jackson}，Spring Boot 4 的默认）。**Spring MVC 用的是
     * Jackson 3**，而记录里写的是 Jackson 2 的 {@code JsonNode}——转换器构造不出来，
     * 请求在进入方法体之前就炸了。
     *
     * <p>故这里刻意让 MockMvc 用真实转换器、发真实 JSON 文本，
     * 把"签名里的类型能不能被绑定"这件事纳入测试范围。
     */
    /**
     * SSE 那条链路此前**完全没有测试**，而助理 id 丢失正好发生在这里。
     *
     * <p>做法是把假线程池改成**同步执行**：{@code ask()} 把工作丢给
     * {@code AsyncTaskExecutor}，这里让它在当前线程跑完，
     * 于是整条 SSE 链路（身份解析 → 建 AskRequest → 调服务 → 推事件）
     * 就能像普通方法一样被断言，不必去啃 {@code SseEmitter} 的异步时序。
     */
    @Nested
    @DisplayName("SSE 链路：AskRequest 的组装")
    @ExtendWith(MockitoExtension.class)
    class OverSse {

        @BeforeEach
        void runWorkerSynchronously() {
            org.mockito.Mockito.doAnswer(inv -> {
                ((Runnable) inv.getArgument(0)).run();
                return null;
            }).when(workerExecutor).execute(any(Runnable.class));
        }

        private void ask(String assistantId) {
            loggedInAs("u1");
            lenient().when(serviceFactory.forAssistant(eq(assistantId), any(), any(), org.mockito.ArgumentMatchers.nullable(String.class)))
                    .thenReturn(askService);
            lenient().when(askService.askStreaming(any(), any())).thenReturn(sampleResult());

            controller.ask(new AskEngineStreamController.EngineAskBody(
                    "上个月华东的保费是多少", null, "s1", assistantId), request);
        }

        @Test
        @DisplayName("★ 助理 id 必须组装进 AskRequest —— 这里曾经硬编码传了 null")
        void assistantIdIsCarriedIntoTheRequest() {
            ask("2");

            org.mockito.ArgumentCaptor<com.liusongboyy.chatdata.engine.chat.AskRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(
                            com.liusongboyy.chatdata.engine.chat.AskRequest.class);
            org.mockito.Mockito.verify(askService).askStreaming(captor.capture(), any());

            assertThat(captor.getValue().assistantId()).isEqualTo("2");
            assertThat(captor.getValue().sessionId()).isEqualTo("s1");
        }

        @Test
        @DisplayName("未选助理时为空 —— 空串归一成 null，别让审计里出现空字符串")
        void blankAssistantBecomesNull() {
            ask("");

            org.mockito.ArgumentCaptor<com.liusongboyy.chatdata.engine.chat.AskRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(
                            com.liusongboyy.chatdata.engine.chat.AskRequest.class);
            org.mockito.Mockito.verify(askService).askStreaming(captor.capture(), any());

            assertThat(captor.getValue().assistantId()).isNull();
        }
    }

    @Nested
    @DisplayName("HTTP 层：请求体真的能被反序列化")
    @ExtendWith(MockitoExtension.class)
    class OverHttp {

        private MockMvc mvc;

        @BeforeEach
        void setUp() {
            mvc = MockMvcBuilders.standaloneSetup(controller).build();
        }

        private static final String BODY = """
                {"question":"上个月华东的保费是多少","sessionId":"s1",
                 "dsl":{"metrics":["保费"],"dimensions":[],
                        "timeRange":{"kind":"上个月","start":null,"end":null},
                        "filters":[{"field":"区域","op":"=","value":"华东"}],
                        "granularity":"DAY"}}""";

        @Test
        @DisplayName("★ 带 dsl 对象的 JSON 请求体必须能绑定 —— 否则是 500 而不是业务错误")
        void bodyIsBindable() throws Exception {
            loggedOut();

            mvc.perform(post("/api/ask/recompute")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("空的 dsl 也是 400 而不是 500")
        void missingDslOverHttp() throws Exception {
            loggedInAs("u1");

            mvc.perform(post("/api/ask/recompute")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"q\",\"dsl\":null}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("没带 DSL：400 —— 没有条件可算")
    void missingDsl() {
        loggedInAs("u1");

        assertThat(controller.recompute(new AskEngineStreamController.RecomputeBody(
                "q", null, "s1", null, null), request).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("★ 成功：返回体与 SSE 的 result 事件同一个形状，且带上理解回显与 DSL")
    void successPayloadShape() {
        loggedInAs("u1");
        when(serviceFactory.forAssistant(any(), any(), any(), org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(askService);
        when(askService.recompute(any(), any())).thenReturn(sampleResult());

        ResponseEntity<?> response = controller.recompute(body(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getBody();
        assertThat(payload).containsKeys(
                "type", "columns", "rows", "rowCount", "maskedColumns",
                "durationMs", "chart", "sql", "understanding", "dsl");
        assertThat(payload.get("type")).isEqualTo("result");
        assertThat(payload.get("rowCount")).isEqualTo(1);
        // 回显必须带上——重算后的结果同样要让用户看出"这次按什么查的"
        assertThat(payload.get("understanding")).isInstanceOf(AskUnderstanding.class);
        assertThat(payload.get("dsl")).isInstanceOf(AskDsl.class);
    }

    @Test
    @DisplayName("★ 载荷能被 Jackson 序列化 —— 否则前端拿到的是 500 而不是结果")
    void payloadIsSerializable() throws Exception {
        loggedInAs("u1");
        when(serviceFactory.forAssistant(any(), any(), any(), org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(askService);
        when(askService.recompute(any(), any())).thenReturn(sampleResult());

        String json = M.writeValueAsString(controller.recompute(body(), request).getBody());

        assertThat(json).contains("\"type\":\"result\"")
                .contains("\"understanding\"")
                .contains("\"dsl\"")
                // 回显里的空槽也要出现在 JSON 里，前端才画得出「无」
                .contains("\"filters\":[]");
    }

    @Test
    @DisplayName("★ 审计里的问题带「重算」前缀 —— 否则同一个问题在审计里无法区分来源")
    void auditQuestionIsMarked() {
        loggedInAs("u1");
        when(serviceFactory.forAssistant(any(), any(), any(), org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(askService);
        when(askService.recompute(any(), any())).thenReturn(sampleResult());

        controller.recompute(body(), request);

        org.mockito.ArgumentCaptor<com.liusongboyy.chatdata.engine.chat.AskRequest> captor =
                org.mockito.ArgumentCaptor.forClass(
                        com.liusongboyy.chatdata.engine.chat.AskRequest.class);
        org.mockito.Mockito.verify(askService).recompute(any(), captor.capture());

        assertThat(captor.getValue().question()).startsWith("〔重算〕");
        // 判定主体只从令牌来，绝不接受请求体里的角色
        assertThat(captor.getValue().roles()).containsExactly("分析师");
    }

    @Test
    @DisplayName("权限拒绝 → 403，不是 200 + blocked:true（ADR-0002 那个坑）")
    void deniedMapsTo403() {
        loggedInAs("u1");
        when(serviceFactory.forAssistant(any(), any(), any(), org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(askService);
        when(askService.recompute(any(), any())).thenThrow(new AskDeniedException(
                PermissionDecision.Reason.DOMAIN_NOT_GRANTED, "您没有该数据域的访问权限"));

        ResponseEntity<?> response = controller.recompute(body(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isEqualTo(
                Map.of("code", "DENIED", "message", "您没有该数据域的访问权限"));
    }

    @Test
    @DisplayName("没听懂 → 400，且消息是给用户看的")
    void notUnderstoodMapsTo400() {
        loggedInAs("u1");
        when(serviceFactory.forAssistant(any(), any(), any(), org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(askService);
        when(askService.recompute(any(), any())).thenThrow(
                new AskService.NotUnderstoodException("这次重算没有可用的查询条件，请重新提问"));

        ResponseEntity<?> response = controller.recompute(body(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(response.getBody())).contains("没有可用的查询条件");
    }

    @Test
    @DisplayName("语义配置问题 → 400，但**不把内部细节给用户**")
    void semanticErrorHidesInternals() {
        loggedInAs("u1");
        when(serviceFactory.forAssistant(any(), any(), any(), org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(askService);
        when(askService.recompute(any(), any())).thenThrow(new SemanticException(
                SemanticException.Reason.FIELD_NOT_FOUND, "本数据域内找不到字段「秘密列」"));

        ResponseEntity<?> response = controller.recompute(body(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(response.getBody()))
                .doesNotContain("秘密列")
                .contains("请联系管理员");
    }

    @Test
    @DisplayName("执行失败 → 500，SQL 报错原文不进响应（可能含表名列名）")
    void executionFailureHidesSqlDetail() {
        loggedInAs("u1");
        when(serviceFactory.forAssistant(any(), any(), any(), org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(askService);
        when(askService.recompute(any(), any())).thenThrow(
                new SqlExecutor.SqlExecutionException("Unknown column 'ins_policy.secret' in 'field list'"));

        ResponseEntity<?> response = controller.recompute(body(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(String.valueOf(response.getBody()))
                .doesNotContain("ins_policy")
                .doesNotContain("secret");
    }

    @Test
    @DisplayName("问的域不存在 → 走同一条错误映射，不抛到容器变成 500 白页")
    void unknownDomainIsMapped() {
        loggedInAs("u1");
        when(serviceFactory.forAssistant(any(), any(), any(), org.mockito.ArgumentMatchers.nullable(String.class))).thenThrow(new SemanticException(
                SemanticException.Reason.INVALID_TABLE_DEFINITION, "数据域 id=99 不存在"));

        assertThat(controller.recompute(body(), request).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("★ 助理 id 进审计 —— 否则「这次走哪个助理」永远查不到")
    void assistantIdReachesAudit() {
        loggedInAs("u1");
        when(serviceFactory.forAssistant(eq("2"), any(), any(), org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(askService);
        when(askService.recompute(any(), any())).thenReturn(sampleResult());

        controller.recompute(new AskEngineStreamController.RecomputeBody(
                "q", null, "s1", "2", dslMap()), request);

        org.mockito.ArgumentCaptor<com.liusongboyy.chatdata.engine.chat.AskRequest> captor =
                org.mockito.ArgumentCaptor.forClass(
                        com.liusongboyy.chatdata.engine.chat.AskRequest.class);
        org.mockito.Mockito.verify(askService).recompute(any(), captor.capture());

        assertThat(captor.getValue().assistantId()).isEqualTo("2");
    }

    @Test
    @DisplayName("会话 id 原样传给引擎 —— 重算属于同一段对话")
    void sessionIdIsForwarded() {
        loggedInAs("u1");
        when(serviceFactory.forAssistant(any(), any(), any(), org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(askService);
        when(askService.recompute(any(), any())).thenReturn(sampleResult());

        controller.recompute(new AskEngineStreamController.RecomputeBody(
                "q", null, "sess-42", null, dslMap()), request);

        org.mockito.ArgumentCaptor<com.liusongboyy.chatdata.engine.chat.AskRequest> captor =
                org.mockito.ArgumentCaptor.forClass(
                        com.liusongboyy.chatdata.engine.chat.AskRequest.class);
        org.mockito.Mockito.verify(askService).recompute(any(), captor.capture());

        assertThat(captor.getValue().sessionId()).isEqualTo("sess-42");
    }

    @Test
    @DisplayName("助理与域原样下传 —— 重算必须落在同一个域上")
    void assistantAndDomainAreForwarded() {
        loggedInAs("u1");
        when(serviceFactory.forAssistant(eq("7"), eq("d9"), any(), org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(askService);
        when(askService.recompute(any(), any())).thenReturn(sampleResult());

        controller.recompute(new AskEngineStreamController.RecomputeBody(
                "q", "d9", "s1", "7", dslMap()), request);

        org.mockito.Mockito.verify(serviceFactory).forAssistant(eq("7"), eq("d9"), any(), org.mockito.ArgumentMatchers.nullable(String.class));
    }
}

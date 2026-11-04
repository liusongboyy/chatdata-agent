package com.liusongboyy.chatdata.ask.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liusongboyy.chatdata.ask.conversation.ConversationService;
import com.liusongboyy.chatdata.engine.agent.AskDeniedException;
import com.liusongboyy.chatdata.engine.agent.DslParser;
import com.liusongboyy.chatdata.engine.agent.EngineNotConfiguredException;
import com.liusongboyy.chatdata.engine.chat.AskEvent;
import com.liusongboyy.chatdata.engine.chat.AskRequest;
import com.liusongboyy.chatdata.engine.chat.AskResult;
import com.liusongboyy.chatdata.engine.chat.AskFacade;
import com.liusongboyy.chatdata.engine.chat.AskService;
import com.liusongboyy.chatdata.engine.chat.AskServiceFactory;
import com.liusongboyy.chatdata.engine.datasource.SqlExecutor;
import com.liusongboyy.chatdata.engine.nlsql.AskDsl;
import com.liusongboyy.chatdata.engine.semantic.SemanticException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

/**
 * 自研引擎的流式问数入口。
 *
 * <p>形态与厂商集成那条链路的 {@code AskStreamController} 一致（见 ADR-0003）：
 * 事件级 SSE，事件名 {@code started / progress / result / error}。
 * 前端可以按事件名复用同一套渲染逻辑。
 *
 * <h2>与那条链路的关键差异</h2>
 *
 * <p>厂商那条必须把 {@code RequestAttributes} 搬到工作线程上，因为它在工作线程里
 * 通过 {@code RequestContextHolder} 反查请求。**这里不需要**——
 * 身份在请求线程上就解析完了，工作线程只拿到一个不可变的
 * {@link AskRequest}。少一处 ThreadLocal 传递就少一类"偶发 NPE"。
 *
 * <h2>错误只给用户可读的信息</h2>
 *
 * <p>SQL 报错里可能含表名、列名甚至连接串。给用户的是"查询失败，请稍后重试或联系管理员"，
 * 原文进日志与审计。**唯一例外是权限拒绝与"没听懂"**——那两条用户能自己处理，
 * 说清楚反而有用。
 */
@RestController
@RequestMapping("/api/ask")
public class AskEngineStreamController {

    private static final Logger log = LoggerFactory.getLogger(AskEngineStreamController.class);
    private static final ObjectMapper M = new ObjectMapper();

    private final AskServiceFactory serviceFactory;
    /** 身份与角色来自 IAM 令牌（内省后进 SecurityContext），不再查本地表。 */
    private final com.liusongboyy.chatdata.ask.identity.CurrentUser currentUser;
    private final AsyncTaskExecutor workerExecutor;
    private final com.liusongboyy.chatdata.engine.model.ModelService models;
    /** 会话历史（产品视图）。与 agentscope 状态分开存，见 ConversationService。 */
    private final ConversationService conversations;

    public AskEngineStreamController(AskServiceFactory serviceFactory,
                                     com.liusongboyy.chatdata.ask.identity.CurrentUser currentUser,
                                     ConversationService conversations,
                                     @Qualifier("askWorkerExecutor") AsyncTaskExecutor workerExecutor,
                                     com.liusongboyy.chatdata.engine.model.ModelService models) {
        this.serviceFactory = serviceFactory;
        this.currentUser = currentUser;
        this.conversations = conversations;
        this.workerExecutor = workerExecutor;
        this.models = models;
    }

    /**
     * 请求体。
     *
     * @param analyze 是否走分析编排（拆成多次查询 + 结论）。
     *                缺省 false——**默认仍是单查询**，避免一次误点就多花几次查询与模型调用
     */
    public record EngineAskBody(String question, String domainId, String sessionId,
                                String assistantId, Boolean analyze) {

        public EngineAskBody(String question, String domainId, String sessionId, String assistantId) {
            this(question, domainId, sessionId, assistantId, null);
        }

        boolean wantsAnalysis() {
            return Boolean.TRUE.equals(analyze);
        }
    }

    /**
     * 当前用户**能用**的助理。
     *
     * <p>界面用它渲染助理选择器。未登录返回空列表而不是 401——
     * 登录页也要能正常加载，不该因为一个可选接口报错。
     */
    @org.springframework.web.bind.annotation.GetMapping("/assistants")
    public ResponseEntity<List<AskServiceFactory.UsableAssistant>> assistants(HttpServletRequest request) {
        String username = currentUser.username().orElse(null);
        if (username == null) {
            return ResponseEntity.ok(List.of());
        }
        try {
            return ResponseEntity.ok(serviceFactory.listUsableAssistants(
                    permissionContext(username)));
        } catch (Exception e) {
            // 走到这里说明**整条列表**读不出来（业务库不可用之类），
            // 而不是某一个助理的问题：助理级的失败（配置坏掉、IAM 策略读不到）
            // 已在 listUsableAssistants 内部按助理隔离并记了日志。
            // 这个区分是必要的——否则一次 IAM 抖动会表现为"你没有助理"，
            // 一个 200 响应，运维侧什么都看不到
            log.error("读取可用助理列表失败（整条列表不可用，不是某个助理的问题）：{}", e.getMessage(), e);
            return ResponseEntity.ok(List.of());
        }
    }

    /**
     * 当前用户**能用**的模型，供问数页的下拉。
     *
     * <h2>为什么在这里判权限而不是让前端判</h2>
     *
     * <p>同一个判据（{@code ModelAccess.selectable}）也用在真正发起问数时。
     * 两处各写一遍会漂移，而漂移的表现是<b>「下拉里看得见、选了报 403」</b>——
     * 用户会以为是系统坏了。
     *
     * <h2>未登录返回空列表而不是 401</h2>
     *
     * <p>与 {@code /assistants} 同一取舍：登录页也要能正常加载。
     *
     * @param domainId 可选。传了就能判断这个域是否锁定了模型
     */
    @org.springframework.web.bind.annotation.GetMapping("/models")
    public ResponseEntity<ModelOptions> modelOptions(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String domainId) {
        String username = currentUser.username().orElse(null);
        if (username == null) {
            return ResponseEntity.ok(new ModelOptions(List.of(), null));
        }

        // 域绑定：只有明确给出域时才查。传空表示"还没选助理"，
        // 那时不该用任何域的绑定去限制用户
        String bound = null;
        if (domainId != null && !domainId.isBlank()) {
            bound = models.domainBinding(domainId);
        }

        var selectable = com.liusongboyy.chatdata.engine.model.ModelAccess
                .selectable(models.enabled(), currentUser.roles(), bound);

        List<UsableModel> out = selectable.stream().map((m) -> new UsableModel(
                m.key(),
                m.displayName(),
                m.description(),
                costTier(m),
                m.isDefault())).toList();

        return ResponseEntity.ok(new ModelOptions(out, bound));
    }

    /**
     * 相对贵的程度。0 便宜 / 1 中等 / 2 贵。
     *
     * <p>**不返回具体单价**：那会让用户分心算账，而他真正该想的是
     * "这个问题难不难"。只需知道"这个更贵"就够了。
     *
     * 阈值按当前见过的价格区间定：输出价 &lt; 5 元/百万算便宜，
     * &lt; 30 算中等，再高算贵。
     */
    private static int costTier(com.liusongboyy.chatdata.engine.model.ModelDef m) {
        if (m.priceOutput() == null) {
            return 0;   // 未配单价：不知道贵不贵，按便宜处理，不给用户额外负担
        }
        double p = m.priceOutput().doubleValue();
        if (p < 5) {
            return 0;
        }
        return p < 30 ? 1 : 2;
    }

    /** 一个可选的模型。与前端 {@code UsableModel} 一一对应。 */
    public record UsableModel(String key, String displayName, String description,
                              int costTier, boolean isDefault) {
    }

    /** 可选模型列表 + 域绑定情况。 */
    public record ModelOptions(List<UsableModel> models, String lockedByDomain) {
    }

    /**
     * 当前域能问哪些字段。
     *
     * <p><strong>这个接口存在的理由值得记一笔。</strong>用户问过「现在你支持问哪些数据」，
     * 而系统把它当成普通提问，让模型编了个 SQL 去查——那**不是数据问题，是能力问题**，
     * 结果自然是执行失败。界面上有一块地方直接回答它，比让模型猜要好得多。
     *
     * <p>不需要登录：它只列出字段名，而字段名对未登录者没有意义
     * （拿到名字也问不出数据——问数端点自己会校验身份与权限）。
     */
    @org.springframework.web.bind.annotation.GetMapping("/capabilities")
    public ResponseEntity<AskServiceFactory.Capabilities> capabilities(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String domainId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String assistantId,
            HttpServletRequest request) {
        try {
            // 带了助理就按助理的主域取——否则界面列出的字段与实际能问的对不上
            String resolved = domainId;
            if ((resolved == null || resolved.isBlank()) && assistantId != null && !assistantId.isBlank()) {
                String username = currentUser.username().orElse(null);
                if (username != null) {
                    var svc = serviceFactory.forAssistant(assistantId, null,
                            permissionContext(username));
                    return ResponseEntity.ok(new AskServiceFactory.Capabilities(
                            svc.domainName(), svc.availableFields()));
                }
            }
            return ResponseEntity.ok(serviceFactory.capabilities(resolved));
        } catch (Exception e) {
            // 域加载失败（没配域、配置坏）——返回空能力而不是 500，
            // 界面显示"暂无可用字段"比弹一个红色报错更合适
            log.warn("读取域能力失败：{}", e.getMessage());
            return ResponseEntity.ok(new AskServiceFactory.Capabilities(null, java.util.List.of()));
        }
    }

    @PostMapping(value = "/engine", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> ask(@RequestBody EngineAskBody body,
                                          HttpServletRequest request) {

        // 身份在**请求线程**上解析完，工作线程只拿到不可变对象
        String username = currentUser.username().orElse(null);
        if (username == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (body == null || body.question() == null || body.question().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // 会话在**请求线程**上创建/校验：越权与助理不一致必须变成 4xx，
        // 一旦 SSE 已开始，工作线程里再发现就只能发一个 error 事件了
        ConversationService.ConversationRef conversation = conversations.ensure(
                body.sessionId(), username, body.assistantId(), body.question());

        Set<String> roles = currentUser.roles();

        SseEmitter emitter = new SseEmitter(180_000L);

        try {
            workerExecutor.execute(() -> run(emitter, body, conversation, username, roles));
        } catch (RejectedExecutionException e) {
            // 队列满时明确拒绝，而不是让请求线程直接跑——后者会让 SSE 变成"卡住后一次性返回"
            log.warn("问数工作队列已满，拒绝请求：user={}", username);
            emitter.completeWithError(e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        return ResponseEntity.ok(emitter);
    }

    /**
     * 重算请求体。
     *
     * @param question    原提问，**只用于审计留痕**。重算不是一次新提问，
     *                    但审计里得看得出它在重算什么，否则同一个问题会在
     *                    审计里出现多条、无法区分哪条是原始查询
     * @param dsl         要重算的 DSL，通常由前端在上一份基础上改掉某个条件
     * @param assistantId 与首次问数保持一致——换助理就是换域，重算必须落在同一个域上
     */
    public record RecomputeBody(String question, String domainId, String sessionId,
                                String assistantId, Map<String, Object> dsl) {
    }

    /**
     * 基于给定的 DSL 重算，**不经过模型**（FR-5.5）。
     *
     * <h2>为什么这条路径接受客户端传来的 DSL</h2>
     *
     * <p>因为它**没有扩大任何能力**：调用方能表达的意图，与让模型产出一个 DSL
     * 完全等价，而后者本来就是这个端点的日常。字段解析、行级谓词、列级脱敏、
     * 只读白名单、行数上限——一个都不少地照常施加。
     *
     * <p>唯一的硬约束是：**判定主体只从令牌解析**，绝不接受请求体里的角色。
     * 见 {@code AskService.recompute} 的说明。
     *
     * <h2>不用 SSE</h2>
     *
     * <p>重算没有"理解问题"那一步，耗时主要在查数本身，阶段只有两个。
     * 为它再走一遍事件流，收益不抵前端要维护的第二套解析。
     */
    @PostMapping("/recompute")
    public ResponseEntity<?> recompute(@RequestBody RecomputeBody body,
                                       HttpServletRequest request) {
        String username = currentUser.username().orElse(null);
        if (username == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (body == null || body.dsl() == null) {
            return ResponseEntity.badRequest().build();
        }

        Set<String> roles = currentUser.roles();
        String rawQuestion = (body.question() == null || body.question().isBlank())
                ? "（重算）" : body.question();
        String question = "〔重算〕" + rawQuestion;

        ConversationService.ConversationRef conversation = conversations.ensure(
                body.sessionId(), username, body.assistantId(), rawQuestion);
        conversations.appendUser(conversation.id(), rawQuestion, "recompute");

        try {
            AskFacade facade = serviceFactory.forAssistant(conversation.assistantId(), body.domainId(),
                    permissionContext(username));

            // 重算是 **DSL 概念**：直连模式压根没有 DSL，模型写的是 SQL。
            // 与其假装支持，不如明确告诉调用方这个模式没有可重算的中间表示
            if (!(facade instanceof AskService service)) {
                conversations.appendFailure(conversation.id(), "NOT_SUPPORTED",
                        "该数据集是直连模式，没有可重算的查询条件");
                return ResponseEntity.badRequest().body(Map.of(
                        "code", "NOT_SUPPORTED",
                        "message", "该数据集是直连模式，没有可重算的查询条件"));
            }

            // 与生成链路用同一个宽松解析器：它容忍模型输出的小偏差，
            // 前端把 DSL 原样传回来时同样用得上
            //
            // **请求体里刻意不用任何 Jackson 类型**（这里用 Map + 手工转字符串）。
            // classpath 上同时有 Jackson 2（agentscope / vendor 带进来的
            // `com.fasterxml`）与 Jackson 3（Spring Boot 4 的默认，`tools.jackson`），
            // 而 **Spring MVC 的消息转换器用的是 Jackson 3**。记录组件一旦写成
            // Jackson 2 的 `JsonNode`，转换器在**进入方法体之前**就构造不出来，
            // 表现为 500 且堆栈与业务毫无关系。用 JDK 类型则与转换器用哪个 Jackson 无关。
            AskDsl dsl;
            try {
                dsl = DslParser.parse(M.writeValueAsString(body.dsl()));
            } catch (Exception e) {
                // 传回来的 DSL 不合法属于**客户端输入问题**，是 400 不是 500。
                // 不回显解析细节：它可能含字段名，而用户只需要知道"重来一次"
                log.warn("重算的 DSL 无法解析：user={} 原因={}", username, e.getMessage());
                conversations.appendFailure(conversation.id(), "DSL_FAILED",
                        "查询条件格式不正确，请重新提问");
                return ResponseEntity.badRequest().body(Map.of(
                        "code", "DSL_FAILED", "message", "查询条件格式不正确，请重新提问"));
            }

            // 同 SSE 路径：重算的记录也要能看出走的是哪个助理
            AskResult result = service.recompute(dsl, new AskRequest(
                    question, username, username, currentUser.roles(),
                    conversation.id(), conversation.assistantId(), currentUser.attributes()));

            Map<String, Object> payload = resultPayload(result, service.domainId(), conversation.id());
            conversations.appendResult(conversation.id(), payload);
            return ResponseEntity.ok(payload);

        } catch (AskDeniedException e) {
            // 403 而不是 200 + blocked:true —— ADR-0002 记的那个坑
            conversations.appendFailure(conversation.id(), "DENIED", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("code", "DENIED", "message", e.getMessage()));

        } catch (AskService.NotUnderstoodException e) {
            conversations.appendFailure(conversation.id(), "NOT_UNDERSTOOD", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "NOT_UNDERSTOOD", "message", e.getMessage()));

        } catch (SemanticException e) {
            log.warn("重算语义错误：user={} 原因={}", username, e.getMessage());
            conversations.appendFailure(conversation.id(), "SEMANTIC_ERROR",
                    "这个条件涉及的数据还没配置好，请联系管理员");
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "SEMANTIC_ERROR",
                    "message", "这个条件涉及的数据还没配置好，请联系管理员"));

        } catch (SqlExecutor.SqlExecutionException e) {
            // SQL 原文进日志，不给用户——它可能含表名列名
            log.error("重算执行失败：user={}", username, e);
            conversations.appendFailure(conversation.id(), "EXECUTION_FAILED",
                    "查询失败，请稍后重试或联系管理员");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", "EXECUTION_FAILED", "message", "查询失败，请稍后重试或联系管理员"));

        } catch (Exception e) {
            log.error("重算失败：user={}", username, e);
            conversations.appendFailure(conversation.id(), "DSL_FAILED", "处理失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", "DSL_FAILED", "message", "处理失败，请稍后重试"));
        }
    }

    private void run(SseEmitter emitter, EngineAskBody body,
                     ConversationService.ConversationRef conversation,
                     String username, Set<String> roles) {
        // 先落用户消息：即使引擎随后失败，历史里也能看到"问过什么、为什么失败"
        conversations.appendUser(conversation.id(), body.question(), "question");
        try {
            // 经助理进入时，助理层与域层**两道闸门都要过**——它们的判据不同，
            // 见 AskServiceFactory.forAssistant 的说明
            AskFacade service = serviceFactory.forAssistant(
                    conversation.assistantId(), body.domainId(),
                    permissionContext(username));

            // assistantId 必须带上：**审计要能回答"这次走的是哪个助理"**。
            // 助理决定功能可见性与落点域，是排查"他为什么看到这些数据"的一环；
            // 传 null 的话审计列恒为空，连 ask_audit_log 上那个
            // idx_assistant_created 索引都成了摆设
            AskRequest askRequest = new AskRequest(
                    body.question(), username, username, currentUser.roles(),
                    conversation.id(), conversation.assistantId(), currentUser.attributes());

            // 显式开关 + 域支持才走编排。默认单查询——编排会多花几次查询与模型调用，
            // 不该由系统替用户决定（自动路由留到有分析评测集之后）
            AskResult result = body.wantsAnalysis() && service.supportsAnalysis()
                    ? service.analyzeStreaming(askRequest, event -> send(emitter, event))
                    : service.askStreaming(askRequest, event -> send(emitter, event));

            // 结果事件手工发送而不是走 AskEvent.Completed：反馈需要知道**产生答案的域 id**，
            // 而域是在服务层解析出来的，事件对象里没有它
            Map<String, Object> payload = resultPayload(result, service.domainId(), conversation.id());
            conversations.appendResult(conversation.id(), payload);
            sendPayload(emitter, "result", payload);
            emitter.complete();

        } catch (AskDeniedException e) {
            // 权限拒绝与"没听懂"要说清楚——用户能自己处理
            fail(emitter, conversation.id(), AskEvent.FailureCode.DENIED, e.getMessage());

        } catch (AskService.NotUnderstoodException e) {
            fail(emitter, conversation.id(), AskEvent.FailureCode.NOT_UNDERSTOOD, e.getMessage());

        } catch (SemanticException e) {
            log.warn("问数语义错误：user={} 原因={}", username, e.getMessage());
            fail(emitter, conversation.id(), AskEvent.FailureCode.SEMANTIC_ERROR,
                    "这个问题涉及的数据还没配置好，请联系管理员");

        } catch (SqlExecutor.SqlExecutionException e) {
            // SQL 报错原文进日志，不给用户——它可能含表名列名
            log.error("问数执行失败：user={}", username, e);
            fail(emitter, conversation.id(), AskEvent.FailureCode.EXECUTION_FAILED,
                    "查询失败，请稍后重试或联系管理员");

        } catch (EngineNotConfiguredException e) {
            // **不能说"稍后重试"。**重试永远不会成功，真实原因是服务端缺配置，
            // 用户重试只会白等，而运维也收不到"去配密钥"这个信号
            log.error("问数引擎未配置：user={} 原因={}", username, e.getMessage());
            fail(emitter, conversation.id(), AskEvent.FailureCode.ENGINE_NOT_READY,
                    "问数引擎尚未配置完成，请联系管理员");

        } catch (Exception e) {
            log.error("问数失败：user={}", username, e);
            fail(emitter, conversation.id(), AskEvent.FailureCode.DSL_FAILED,
                    "处理失败，请稍后重试");
        }
    }

    /**
     * 发送失败事件并落历史。
     *
     * <p>失败事件也带 {@code conversationId}：新会话第一次提问就失败时，
     * 前端需要它把本地 id 落定，否则接下来那次"重试"会开出一个新会话。
     */
    private void fail(SseEmitter emitter, String conversationId, String code, String message) {
        conversations.appendFailure(conversationId, code, message);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("type", "error");
        payload.put("code", code);
        payload.put("message", message);
        payload.put("conversationId", conversationId);
        sendPayload(emitter, "error", payload);
        emitter.complete();
    }

    /** 推送一个命名事件。与 {@link #send(SseEmitter, AskEvent)} 同源，只是载荷已构造好。 */
    private void sendPayload(SseEmitter emitter, String name, Object payload) {
        try {
            emitter.send(SseEmitter.event()
                    .name(name)
                    .data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 推送失败（连接可能已断开）：{}", name);
        }
    }

    /** 推送一个事件。写失败说明连接已断——记 debug 即可，用户已经走了。 */
    private void send(SseEmitter emitter, AskEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.eventName())
                    .data(toPayload(event), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 推送失败（连接可能已断开）：{}", event.eventName());
        }
    }

    /** 事件的 JSON 形状。刻意扁平：前端只需按 {@code type} 分支。 */
    private Object toPayload(AskEvent event) {
        return switch (event) {
            case AskEvent.Started s -> Map.of("type", "started");
            case AskEvent.Progress p -> Map.of(
                    "type", "progress",
                    "stage", p.stage().name(),
                    "message", p.message());
            // 正常路径已改为 sendPayload（要带 domainId）；保留这个分支只为穷尽 sealed 类型，
            // 它不带域 id，不要用它来发结果
            case AskEvent.Completed c -> resultPayload(c.result(), null);
            case AskEvent.Failed f -> Map.of(
                    "type", "error",
                    "code", f.code(),
                    "message", f.message());
        };
    }

    /**
     * 一次问数结果的 JSON 形状。
     *
     * <p>SSE 的 {@code result} 事件与 {@code /recompute} 的响应**共用这一个方法**。
     * 两处各写一份的话，重算返回的结果迟早会和正常结果长得不一样，
     * 而前端要按同一个形状渲染——那类不一致在界面上表现为"某些字段时有时无"。
     */
    private Map<String, Object> resultPayload(AskResult r, String domainId) {
        return resultPayload(r, domainId, null);
    }

    private Map<String, Object> resultPayload(AskResult r, String domainId, String conversationId) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("type", "result");
        // 前端据此把本轮问答挂到正确的历史会话上（新会话第一次提问时尤其重要）
        if (conversationId != null) {
            payload.put("conversationId", conversationId);
        }
        // 反馈要用它：反馈必须落在产生这条答案的那个域上，否则审核通过后
        // 写入的范例会被别的域检索到
        payload.put("domainId", domainId);
        payload.put("columns", r.result().columns());
        payload.put("rows", r.result().rows());
        payload.put("rowCount", r.result().rowCount());
        payload.put("maskedColumns", r.maskedColumns());
        payload.put("durationMs", r.durationMs());
        // 图表规格由后端推荐——它知道 DSL 的意图与语义层的列类型，
        // 前端只有结果的形状，判断不了"这个维度是不是日期"
        payload.put("chart", r.chart());
        // SQL 是否透出由上层决定；这里先带上，前端按助手的 showSql 开关决定显不显示。
        // 用 sqlText() 而不是 r.sql().sql()：直连模式的 SQL 由模型写，不在 RenderedSql 里
        payload.put("sql", r.sqlText());
        // **展示用**：参数已内联成字面量。sqlText() 里是 `?`，用户看不出查了什么条件。
        // 两个都给：sql 保留为排查用的模板，sqlDisplay 给界面与复制粘贴
        payload.put("sqlDisplay", r.sqlDisplayText());
        // 模式要告诉前端：直连模式**没有行/列权限**，界面必须明示这一点
        payload.put("mode", r.mode().name());
        // 「我理解成了」——四个槽位永远全给，空的就是空数组/空值。
        // **不要在服务端拼成一句中文**：缺的那一项会消失在句子里，
        // 而"缺了什么"正是这块信息唯一要说的事。见 AskUnderstanding
        // 直连模式没有 DSL、也没有可回显的结构化事实——前端据此隐藏那两块
        payload.put("understanding", r.understanding());
        // 能力答复：没有数据表时，答案就是这段文字。前端据此跳过表格与"无数据"提示
        payload.put("answer", r.answer());
        // DSL 回传给前端，是为了让它能**改一个条件再算一次**（FR-5.5）。
        // 这不是把权限交出去：重算时判定主体仍从令牌解析，字段解析、行规则、
        // 列脱敏、只读与行数上限一个都不少（见 AskService.recompute）。
        payload.put("dsl", r.dsl());
        // 分析编排结果；单查询为 null，前端据此走原有渲染
        payload.put("analysis", analysisPayload(r.analysis()));
        // 降级说明：分析计划失败回退单查询时，必须让用户知道
        payload.put("notice", r.notice());
        return payload;
    }

    /**
     * 分析结果的 JSON 形状。
     *
     * <p>逐字段挑，不直接序列化内部 record：{@code RenderedSql} 里带着参数数组，
     * 那是执行用的，不该出现在前端契约里。这里只给展示与排查需要的部分。
     */
    private Map<String, Object> analysisPayload(
            com.liusongboyy.chatdata.engine.analysis.AnalysisResult a) {
        if (a == null) {
            return null;
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("intent", a.intent());
        out.put("narrative", a.narrative());
        out.put("queryCount", a.queryCount());
        out.put("truncated", a.truncated());

        List<Map<String, Object>> findings = a.findings().stream().map(f -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("kind", f.kind().name());
            m.put("stepId", f.stepId());
            m.put("text", f.text());
            m.put("evidence", f.evidence());
            return m;
        }).toList();
        out.put("findings", findings);

        List<Map<String, Object>> steps = a.steps().stream().map(s -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", s.id());
            m.put("label", s.label());
            m.put("purpose", s.purpose());
            m.put("status", s.status().name());
            m.put("error", s.error());
            m.put("columns", s.rows().columns());
            m.put("rows", s.rows().rows());
            m.put("rowCount", s.rows().rowCount());
            m.put("maskedColumns", s.maskedColumns());
            m.put("sql", s.sql() == null ? null : s.sql().sql());
            m.put("sqlDisplay", s.sql() == null ? null : s.sql().displaySql());
            m.put("dsl", s.dsl());
            // 每步各自的图表推荐：趋势、构成、对比用同一种图会看不清
            m.put("chart", s.chart());
            m.put("durationMs", s.durationMs());
            return m;
        }).toList();
        out.put("steps", steps);
        return out;
    }


    /**
     * 引擎用的权限上下文。
     *
     * <p><strong>必须带 attributes。</strong>动态行规则（如 {@code sysVar: "orgPaths"}）
     * 从这里取组织路径；只传 roles 时动态变量恒为空，结果要么整条查询被拒绝，
     * 要么——更糟——规则被当成"不适用"而静默放宽。
     *
     * <p>此前这里有四处各自写 {@code PermissionContext.of(username, roles)}，
     * 全都丢了 attributes。四处分别写同一个东西时，改一处漏三处是常态，
     * 所以收敛成一个方法。
     */
    private com.liusongboyy.chatdata.engine.permission.PermissionContext permissionContext(
            String username) {
        return com.liusongboyy.chatdata.engine.permission.PermissionContext.of(
                username, currentUser.roles(), currentUser.attributes());
    }
}

package com.liusongboyy.chatdata.ask.engine;

import com.liusongboyy.chatdata.common.core.result.Result;
import com.liusongboyy.chatdata.ask.policy.PolicyReferenceValidator;
import com.liusongboyy.chatdata.iam.client.IamRoleDirectoryClient;
import com.liusongboyy.chatdata.iam.client.IamUserDirectoryClient;
import com.liusongboyy.chatdata.engine.admin.AdminAssistantService;
import com.liusongboyy.chatdata.engine.admin.AdminDatasourceService;
import com.liusongboyy.chatdata.engine.admin.AssistantInput;
import com.liusongboyy.chatdata.engine.admin.AdminDomainService;
import com.liusongboyy.chatdata.engine.admin.AdminException;
import com.liusongboyy.chatdata.engine.admin.DatasourceInput;
import com.liusongboyy.chatdata.engine.admin.DomainInput;
import com.liusongboyy.chatdata.engine.admin.DomainTableInput;
import com.liusongboyy.chatdata.engine.admin.TermInput;
import com.liusongboyy.chatdata.engine.admin.TopicInput;
import com.liusongboyy.chatdata.engine.datasource.DatasourceConnectionTester;
import com.liusongboyy.chatdata.engine.datasource.DatasourceDef;
import com.liusongboyy.chatdata.engine.datasource.MetaReader;
import com.liusongboyy.chatdata.engine.feedback.FeedbackException;
import com.liusongboyy.chatdata.engine.feedback.FeedbackRow;
import com.liusongboyy.chatdata.engine.feedback.FeedbackService;
import com.liusongboyy.chatdata.engine.semantic.AssistantRow;
import com.liusongboyy.chatdata.engine.semantic.DomainRow;
import com.liusongboyy.chatdata.engine.semantic.DomainTableRow;
import com.liusongboyy.chatdata.engine.semantic.TermRow;
import com.liusongboyy.chatdata.engine.semantic.TopicRow;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 引擎管理面。
 *
 * <h2>鉴权是**管理台角色**，不是"能问数"</h2>
 *
 * <p>ADR-0004 记录过一个教训：早期判断「问数接口需要 chat2bi 角色」，于是给所有用户
 * 都授了 chat2bi，结果是**每个能问数的人都能进管理台配数据源与指标口径**。
 * 实际上 {@code chat2bi} 是管理台角色，问数接口不设角色门槛。
 *
 * <p>所以这里**必须**单独校验 {@code chat2bi}，不能因为"他已经登录了"就放行。
 *
 * <h2>失败一律 4xx，不用 200 + success:false</h2>
 *
 * <p>厂商对"被拒绝"返回 HTTP 200 + {@code {"blocked":true}}，导致调用方无法用状态码
 * 区分成功与失败，审计里也分不清"被拒绝"与"查了但没数据"。
 * 这里用真实状态码：400 输入有问题、403 权限不足、404 对象不存在。
 */
@RestController
@RequestMapping("/api/engine/admin")
@org.springframework.security.access.prepost.PreAuthorize("@perm.allowAdminApi()")
public class AdminEngineController {

    private static final Logger log = LoggerFactory.getLogger(AdminEngineController.class);

    /** 管理台角色。见 ADR-0004。 */
    private static final Set<String> ADMIN_ROLES = Set.of("chat2bi", "admin");

    private final AdminDatasourceService datasourceService;
    private final AdminDomainService domainService;
    private final AdminAssistantService assistantService;
    private final MetaReader metaReader;
    /** 身份与角色来自 IAM 令牌；不再从本地表解析。 */
    private final com.liusongboyy.chatdata.ask.identity.CurrentUser currentUser;
    /**
     * 角色目录来自 IAM——与鉴权、与启动校验读的是同一份事实。
     *
     * <p>用途：管理台把可选角色列出来让人点选，并当场标出填错的角色名。
     * 读本地旧表会造出"能选但不存在"（IAM 里已删）与"存在但选不到"（IAM 里新建）
     * 两种角色，而两者的表现都是规则静默失效。
     */
    private final IamRoleDirectoryClient roleDirectory;
    private final IamUserDirectoryClient userDirectory;
    /** 保存策略时校验角色/用户引用（IAM 不可用则跳过，见类注释）。 */
    private final PolicyReferenceValidator policyReferences;
    /** 策略创建/更新审计；写失败只记日志，不回滚保存。 */
    private final com.liusongboyy.chatdata.ask.policy.PolicyAuditRecorder policyAudit;
    private final com.liusongboyy.chatdata.engine.admin.AdminSqlExampleService sqlExampleService;
    private final FeedbackService feedbackService;
    /** 功能权限开关。关闭时退回旧的粗粒度角色判据（回滚闸）。 */
    private final boolean fineGrainedPermissions;

    public AdminEngineController(AdminDatasourceService datasourceService,
                                 AdminDomainService domainService,
                                 AdminAssistantService assistantService,
                                 MetaReader metaReader,
                                 com.liusongboyy.chatdata.ask.identity.CurrentUser currentUser,
                                 IamRoleDirectoryClient roleDirectory,
                                 IamUserDirectoryClient userDirectory,
                                 PolicyReferenceValidator policyReferences,
                                 com.liusongboyy.chatdata.ask.policy.PolicyAuditRecorder policyAudit,
                                 com.liusongboyy.chatdata.engine.admin.AdminSqlExampleService sqlExampleService,
                                 FeedbackService feedbackService,
                                 @org.springframework.beans.factory.annotation.Value(
                                         "${chatdata.iam.functional-permissions.enabled:true}")
                                 boolean fineGrainedPermissions) {
        this.datasourceService = datasourceService;
        this.domainService = domainService;
        this.assistantService = assistantService;
        this.metaReader = metaReader;
        this.currentUser = currentUser;
        this.roleDirectory = roleDirectory;
        this.userDirectory = userDirectory;
        this.policyReferences = policyReferences;
        this.policyAudit = policyAudit;
        this.sqlExampleService = sqlExampleService;
        this.feedbackService = feedbackService;
        this.fineGrainedPermissions = fineGrainedPermissions;
    }

    // ── 角色 ──

    /**
     * 全部角色。
     *
     * <h2>为什么管理台需要这个接口</h2>
     *
     * <p>权限配置里写的角色名**必须与 IAM 里的角色码逐字符一致**，
     * 否则规则静默失效（见 {@code docs/permission-config.md} 第四节）。
     * 让管理员对着一个自由文本框凭记忆敲角色名，是在制造这类错误。
     *
     * <p>前端拿它做两件事：把可选角色列出来让用户**点选而不是手打**（治本），
     * 以及当场标出已经填错的角色（治标，但能立刻发现）。
     *
     * @return 角色码、显示名与启用状态。**含已停用的**——"不存在"与"已停用"
     *         要分开提示，处理方式不同
     */
    @GetMapping("/roles")
    public ResponseEntity<Result<List<RoleOption>>> roles(HttpServletRequest request) {
        ResponseEntity<Result<List<RoleOption>>> denied = requireAdmin(request);
        if (denied != null) return denied;

        List<RoleOption> out;
        try {
            out = roleDirectory.roles().stream()
                    .map(r -> new RoleOption(r.code(), r.name(), r.enabled()))
                    .toList();
        } catch (RuntimeException e) {
            // **不要返回空列表**：前端会把"一个角色都没有"画成"没有可选角色"，
            // 管理员于是以为角色真的不存在，转而怀疑自己建错了。说清是取不到
            log.error("读取 IAM 角色目录失败，角色点选不可用：{}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Result.error(503, "角色目录暂时不可用（IAM），请稍后重试"));
        }
        return ok(out);
    }

    /**
     * 用户选项：策略编辑器用，按账号/显示名搜索。
     *
     * <p>权限配置里 {@code grantUsers} 存的是 **username**。让管理员手打
     * 与手打角色码是同一类风险：写错不报错，只是永远匹配不上。
     *
     * <p>只返回选择器需要的三列，不返回组织/角色；限 20 条，搜索按需触发。
     *
     * @return 含已停用用户——已保存的策略可能引用他们，界面需要标出
     */
    @GetMapping("/user-options")
    public ResponseEntity<Result<List<UserOption>>> userOptions(
            @RequestParam(required = false) String keyword,
            HttpServletRequest request) {
        ResponseEntity<Result<List<UserOption>>> denied = requireAdmin(request);
        if (denied != null) return denied;

        List<UserOption> out;
        try {
            out = userDirectory.users(keyword, 20).stream()
                    .map(u -> new UserOption(u.username(), u.displayName(), u.status()))
                    .toList();
        } catch (RuntimeException e) {
            log.error("读取 IAM 用户目录失败，用户点选不可用：{}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Result.error(503, "用户目录暂时不可用（IAM），请稍后重试"));
        }
        return ok(out);
    }

    /**
     * 一个可选用户。
     *
     * @param username    权限配置里要填的值
     * @param displayName 显示名
     * @param status      ACTIVE / DISABLED
     */
    public record UserOption(String username, String displayName, String status) {
    }

    /**
     * 一个可选角色。
     *
     * @param code    角色码。**这是权限配置里要填的那个字符串**
     * @param name    显示名，给人看
     * @param enabled 是否启用。停用的角色不参与鉴权，规则引用它同样不会生效
     */
    public record RoleOption(String code, String name, boolean enabled) {
    }

    // ── SQL 范例（直连模式的 few-shot）──

    @GetMapping("/domains/{domainId}/sql-examples")
    public ResponseEntity<Result<java.util.List<com.liusongboyy.chatdata.engine.raw.SqlExample>>> listSqlExamples(
            @PathVariable long domainId, HttpServletRequest request) {
        ResponseEntity<Result<java.util.List<com.liusongboyy.chatdata.engine.raw.SqlExample>>> denied =
                requireAdmin(request);
        if (denied != null) return denied;
        return ok(sqlExampleService.list(domainId));
    }

    @PostMapping("/domains/{domainId}/sql-examples")
    public ResponseEntity<Result<Long>> createSqlExample(@PathVariable long domainId,
                                                        @RequestBody com.liusongboyy.chatdata.engine.admin.SqlExampleInput input,
                                                        HttpServletRequest request) {
        ResponseEntity<Result<Long>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ok(sqlExampleService.create(domainId, input, operator(request)));
    }

    @PutMapping("/sql-examples/{id}")
    public ResponseEntity<Result<Void>> updateSqlExample(@PathVariable long id,
                                                        @RequestBody com.liusongboyy.chatdata.engine.admin.SqlExampleInput input,
                                                        HttpServletRequest request) {
        ResponseEntity<Result<Void>> denied = requireAdmin(request);
        if (denied != null) return denied;
        sqlExampleService.update(id, input, operator(request));
        return ok(null);
    }

    @DeleteMapping("/sql-examples/{id}")
    public ResponseEntity<Result<Void>> deleteSqlExample(@PathVariable long id,
                                                        HttpServletRequest request) {
        ResponseEntity<Result<Void>> denied = requireAdmin(request);
        if (denied != null) return denied;
        sqlExampleService.delete(id, operator(request));
        return ok(null);
    }

    // ── 数据源 ──

    @GetMapping("/datasources")
    public ResponseEntity<Result<List<DatasourceDef>>> listDatasources(HttpServletRequest request) {
        ResponseEntity<Result<List<DatasourceDef>>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ok(datasourceService.list());
    }

    @PostMapping("/datasources")
    public ResponseEntity<Result<Long>> createDatasource(@RequestBody DatasourceInput input,
                                                         HttpServletRequest request) {
        ResponseEntity<Result<Long>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ok(datasourceService.create(input, operator(request)));
    }

    @PutMapping("/datasources/{id}")
    public ResponseEntity<Result<Void>> updateDatasource(@PathVariable long id,
                                                         @RequestBody DatasourceInput input,
                                                         HttpServletRequest request) {
        ResponseEntity<Result<Void>> denied = requireAdmin(request);
        if (denied != null) return denied;
        datasourceService.update(id, input, operator(request));
        return ok(null);
    }

    /**
     * 单独改密码。
     *
     * <p>与普通更新分开：管理页回显不了密码，所以表单里的空值必须解释成"不改"。
     * 合成一个接口的话，迟早有人改个名字就把密码清掉了。
     */
    @PutMapping("/datasources/{id}/password")
    public ResponseEntity<Result<Void>> updatePassword(@PathVariable long id,
                                                       @RequestBody PasswordBody body,
                                                       HttpServletRequest request) {
        ResponseEntity<Result<Void>> denied = requireAdmin(request);
        if (denied != null) return denied;
        datasourceService.savePassword(id, body == null ? null : body.password(), operator(request));
        return ok(null);
    }

    /** 密码请求体。单独定义而不是复用 DatasourceInput——这里只有一件事。 */
    public record PasswordBody(String password) {
    }

    @DeleteMapping("/datasources/{id}")
    public ResponseEntity<Result<Void>> deleteDatasource(@PathVariable long id,
                                                         HttpServletRequest request) {
        ResponseEntity<Result<Void>> denied = requireAdmin(request);
        if (denied != null) return denied;
        datasourceService.delete(id, operator(request));
        return ok(null);
    }

    /**
     * 测试连接（**表单参数，尚未保存**）。
     *
     * <p>刻意返回 200 + 结构化的结果，而不是用状态码表达"连不上"——
     * 连不上是这类接口的**正常结果**，不是请求错误。前端要的是那条提示文本。
     */
    @PostMapping("/datasources/test")
    public ResponseEntity<Result<ProbeBody>> testDatasource(@RequestBody DatasourceInput input,
                                                            HttpServletRequest request) {
        ResponseEntity<Result<ProbeBody>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ok(ProbeBody.of(datasourceService.test(input)));
    }

    /** 测试连接（已保存的数据源，密码留空则用已存的）。 */
    @PostMapping("/datasources/{id}/test")
    public ResponseEntity<Result<ProbeBody>> testSavedDatasource(@PathVariable long id,
                                                                 HttpServletRequest request) {
        ResponseEntity<Result<ProbeBody>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ok(ProbeBody.of(datasourceService.test(id)));
    }

    /** 连接测试结果。 */
    public record ProbeBody(boolean ok, String message) {
        static ProbeBody of(DatasourceConnectionTester.Probe probe) {
            return new ProbeBody(probe.ok(), probe.message());
        }
    }

    // ── 元数据（供"从数据源导入表与字段"用） ──

    /**
     * 列出数据源里的表。
     *
     * <p>存在的理由：注册表里的表名与列名此前全靠手打，实测因此写进过一个
     * 不存在的列（{@code org_code}），而它要到用户提问、SQL 执行时才暴露。
     * 让管理员**从真实结构里选**而不是敲，这类错误就没了来源。
     */
    @GetMapping("/datasources/{id}/tables")
    public ResponseEntity<Result<List<MetaReader.TableMeta>>> listDatasourceTables(
            @PathVariable long id, HttpServletRequest request) {
        ResponseEntity<Result<List<MetaReader.TableMeta>>> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            return ok(metaReader.listTables(String.valueOf(id)));
        } catch (Exception e) {
            // 连不上数据源是常见情况（密码改过、库停了）——返回 400 让人看得懂，
            // 而不是 500 加一段堆栈
            log.warn("读取数据源 {} 的表清单失败：{}", id, e.getMessage());
            throw new AdminException("读取表清单失败：" + e.getMessage());
        }
    }

    /** 列出某张表的字段。 */
    @GetMapping("/datasources/{id}/tables/{table}/columns")
    public ResponseEntity<Result<List<MetaReader.ColumnMeta>>> listDatasourceColumns(
            @PathVariable long id,
            @PathVariable String table,
            HttpServletRequest request) {
        ResponseEntity<Result<List<MetaReader.ColumnMeta>>> denied = requireAdmin(request);
        if (denied != null) return denied;
        try {
            return ok(metaReader.listColumns(String.valueOf(id), table));
        } catch (Exception e) {
            log.warn("读取数据源 {} 表 {} 的字段失败：{}", id, table, e.getMessage());
            throw new AdminException("读取字段清单失败：" + e.getMessage());
        }
    }

    // ── 语义域 ──

    @GetMapping("/domains")
    public ResponseEntity<Result<List<DomainRow>>> listDomains(HttpServletRequest request) {
        ResponseEntity<Result<List<DomainRow>>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ok(domainService.list());
    }

    @PostMapping("/domains")
    public ResponseEntity<Result<Long>> createDomain(@RequestBody DomainInput input,
                                                     HttpServletRequest request) {
        ResponseEntity<Result<Long>> denied = requireAdmin(request);
        if (denied != null) return denied;
        policyReferences.validateDomain(input.permJson());
        long id = domainService.create(input, operator(request));
        policyAudit.recordCreate("DOMAIN", id, input.name(), operator(request), input.permJson());
        return ok(id);
    }

    @PutMapping("/domains/{id}")
    public ResponseEntity<Result<Void>> updateDomain(@PathVariable long id,
                                                     @RequestBody DomainInput input,
                                                     HttpServletRequest request) {
        ResponseEntity<Result<Void>> denied = requireAdmin(request);
        if (denied != null) return denied;
        policyReferences.validateDomain(input.permJson());
        domainService.update(id, input, operator(request));
        policyAudit.recordUpdate("DOMAIN", id, input.name(), operator(request), input.permJson());
        return ok(null);
    }

    /**
     * 保存表关联。
     *
     * <p>单独一个入口，因为关联是在「注册表」页面编辑的——那里没有域的其它字段，
     * 走全量更新会把描述、授权一并清掉。
     */
    @PutMapping("/domains/{id}/joins")
    public ResponseEntity<Result<Void>> saveJoins(@PathVariable long id,
                                                  @RequestBody JoinsBody body,
                                                  HttpServletRequest request) {
        ResponseEntity<Result<Void>> denied = requireAdmin(request);
        if (denied != null) return denied;
        domainService.saveJoins(id, body == null ? null : body.joinsJson(), operator(request));
        return ok(null);
    }

    /** 表关联请求体。 */
    public record JoinsBody(String joinsJson) {
    }

    @DeleteMapping("/domains/{id}")
    public ResponseEntity<Result<Void>> deleteDomain(@PathVariable long id,
                                                     HttpServletRequest request) {
        ResponseEntity<Result<Void>> denied = requireAdmin(request);
        if (denied != null) return denied;
        domainService.delete(id, operator(request));
        return ok(null);
    }

    // ── 域内注册表 ──

    @GetMapping("/domains/{domainId}/tables")
    public ResponseEntity<Result<List<DomainTableRow>>> listTables(@PathVariable long domainId,
                                                                   HttpServletRequest request) {
        ResponseEntity<Result<List<DomainTableRow>>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ok(domainService.listTables(domainId));
    }

    @PostMapping("/domains/{domainId}/tables")
    public ResponseEntity<Result<Long>> createTable(@PathVariable long domainId,
                                                    @RequestBody DomainTableInput input,
                                                    HttpServletRequest request) {
        ResponseEntity<Result<Long>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ok(domainService.createTable(domainId, input, operator(request)));
    }

    @PutMapping("/tables/{id}")
    public ResponseEntity<Result<Void>> updateTable(@PathVariable long id,
                                                    @RequestBody DomainTableInput input,
                                                    HttpServletRequest request) {
        ResponseEntity<Result<Void>> denied = requireAdmin(request);
        if (denied != null) return denied;
        domainService.updateTable(id, input, operator(request));
        return ok(null);
    }

    @DeleteMapping("/tables/{id}")
    public ResponseEntity<Result<Void>> deleteTable(@PathVariable long id,
                                                    HttpServletRequest request) {
        ResponseEntity<Result<Void>> denied = requireAdmin(request);
        if (denied != null) return denied;
        domainService.deleteTable(id, operator(request));
        return ok(null);
    }

    // ── 业务术语 ──

    @GetMapping("/domains/{domainId}/terms")
    public ResponseEntity<Result<List<TermRow>>> listTerms(@PathVariable long domainId,
                                                           HttpServletRequest request) {
        ResponseEntity<Result<List<TermRow>>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ok(domainService.listTerms(domainId));
    }

    @PostMapping("/domains/{domainId}/terms")
    public ResponseEntity<Result<Long>> createTerm(@PathVariable long domainId,
                                                   @RequestBody TermInput input,
                                                   HttpServletRequest request) {
        ResponseEntity<Result<Long>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ok(domainService.createTerm(domainId, input, operator(request)));
    }

    @PutMapping("/terms/{id}")
    public ResponseEntity<Result<Void>> updateTerm(@PathVariable long id,
                                                   @RequestBody TermInput input,
                                                   HttpServletRequest request) {
        ResponseEntity<Result<Void>> denied = requireAdmin(request);
        if (denied != null) return denied;
        domainService.updateTerm(id, input, operator(request));
        return ok(null);
    }

    @DeleteMapping("/terms/{id}")
    public ResponseEntity<Result<Void>> deleteTerm(@PathVariable long id,
                                                   HttpServletRequest request) {
        ResponseEntity<Result<Void>> denied = requireAdmin(request);
        if (denied != null) return denied;
        domainService.deleteTerm(id, operator(request));
        return ok(null);
    }

    // ── 查询主题 ──

    @GetMapping("/domains/{domainId}/topics")
    public ResponseEntity<Result<List<TopicRow>>> listTopics(@PathVariable long domainId,
                                                             HttpServletRequest request) {
        ResponseEntity<Result<List<TopicRow>>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ok(domainService.listTopics(domainId));
    }

    @PostMapping("/domains/{domainId}/topics")
    public ResponseEntity<Result<Long>> createTopic(@PathVariable long domainId,
                                                    @RequestBody TopicInput input,
                                                    HttpServletRequest request) {
        ResponseEntity<Result<Long>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ok(domainService.createTopic(domainId, input, operator(request)));
    }

    @PutMapping("/topics/{id}")
    public ResponseEntity<Result<Void>> updateTopic(@PathVariable long id,
                                                    @RequestBody TopicInput input,
                                                    HttpServletRequest request) {
        ResponseEntity<Result<Void>> denied = requireAdmin(request);
        if (denied != null) return denied;
        domainService.updateTopic(id, input, operator(request));
        return ok(null);
    }

    @DeleteMapping("/topics/{id}")
    public ResponseEntity<Result<Void>> deleteTopic(@PathVariable long id,
                                                    HttpServletRequest request) {
        ResponseEntity<Result<Void>> denied = requireAdmin(request);
        if (denied != null) return denied;
        domainService.deleteTopic(id, operator(request));
        return ok(null);
    }

    // ── 助理 ──

    @GetMapping("/assistants")
    public ResponseEntity<Result<List<AssistantRow>>> listAssistants(HttpServletRequest request) {
        ResponseEntity<Result<List<AssistantRow>>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ok(assistantService.list());
    }

    @PostMapping("/assistants")
    public ResponseEntity<Result<Long>> createAssistant(@RequestBody AssistantInput input,
                                                        HttpServletRequest request) {
        ResponseEntity<Result<Long>> denied = requireAdmin(request);
        if (denied != null) return denied;
        policyReferences.validateAssistant(input.permJson());
        long id = assistantService.create(input, operator(request));
        policyAudit.recordCreate("ASSISTANT", id, input.name(), operator(request), input.permJson());
        return ok(id);
    }

    @PutMapping("/assistants/{id}")
    public ResponseEntity<Result<Void>> updateAssistant(@PathVariable long id,
                                                        @RequestBody AssistantInput input,
                                                        HttpServletRequest request) {
        ResponseEntity<Result<Void>> denied = requireAdmin(request);
        if (denied != null) return denied;
        policyReferences.validateAssistant(input.permJson());
        assistantService.update(id, input, operator(request));
        policyAudit.recordUpdate("ASSISTANT", id, input.name(), operator(request), input.permJson());
        return ok(null);
    }

    @DeleteMapping("/assistants/{id}")
    public ResponseEntity<Result<Void>> deleteAssistant(@PathVariable long id,
                                                        HttpServletRequest request) {
        ResponseEntity<Result<Void>> denied = requireAdmin(request);
        if (denied != null) return denied;
        assistantService.delete(id, operator(request));
        return ok(null);
    }

    // ── 反馈审核 ──

    /**
     * 待审/已审反馈列表。
     *
     * @param status PENDING / APPROVED / REJECTED；留空表示全部
     */
    @GetMapping("/feedback")
    public ResponseEntity<Result<List<FeedbackRow>>> listFeedback(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit,
            HttpServletRequest request) {
        ResponseEntity<Result<List<FeedbackRow>>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ok(feedbackService.list(status, limit));
    }

    /**
     * 通过。若反馈带修正 SQL，会写入/更新该域的范例库。
     *
     * @return 写入或复用的范例 id；非修正类型为 null
     */
    @PostMapping("/feedback/{id}/approve")
    public ResponseEntity<Result<Long>> approveFeedback(@PathVariable long id,
                                                        HttpServletRequest request) {
        ResponseEntity<Result<Long>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ok(feedbackService.approve(id, operator(request)));
    }

    /** 拒绝。不写范例库。 */
    @PostMapping("/feedback/{id}/reject")
    public ResponseEntity<Result<Void>> rejectFeedback(@PathVariable long id,
                                                       HttpServletRequest request) {
        ResponseEntity<Result<Void>> denied = requireAdmin(request);
        if (denied != null) return denied;
        feedbackService.reject(id, operator(request));
        return ok(null);
    }

    // ── 公共 ──

    /**
     * 确认"有人登录"。
     *
     * <h2>权限判定去哪了</h2>
     *
     * <p>功能权限由**类级注解** {@code @PreAuthorize("@perm.allowAdminApi()")} 判定
     * （规则表见 {@link AdminApiPermissions}）。这个方法只剩"未登录 → 401"这一句——
     * 它要给出一句能直接展示的文案，而 {@code @PreAuthorize} 被拒时是统一的 403。
     *
     * <p>关闭功能权限时（回滚开关）退回到旧的粗粒度角色判据。
     *
     * @return 拒绝时返回响应；通过时返回 null
     */
    private <T> ResponseEntity<Result<T>> requireAdmin(HttpServletRequest request) {
        String username = currentUser.username().orElse(null);

        if (username == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Result.error(401, "未登录"));
        }

        if (fineGrainedPermissions) {
            // 细粒度判定已在进入方法体之前完成
            return null;
        }

        Set<String> roles = currentUser.roles();
        boolean isAdmin = roles.stream().anyMatch(ADMIN_ROLES::contains);
        if (!isAdmin) {
            // 记 warn 是刻意的：越权尝试值得留痕，而它不该被当成普通 404 静默掉
            log.warn("非管理台角色访问管理面：user={} roles={}", username, roles);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Result.error(403, "需要管理台权限"));
        }
        return null;
    }

    /** 操作人，用于审计字段。身份已由 {@link #requireAdmin} 校验过。 */
    private String operator(HttpServletRequest request) {
        return currentUser.username().orElse("unknown");
    }

    private static <T> ResponseEntity<Result<T>> ok(T data) {
        return ResponseEntity.ok(Result.success(data));
    }

    /**
     * 输入问题转成 400，而不是 500。
     *
     * <p>{@link AdminException} 的消息是写给管理员看的，直接透出。
     * 其它异常才是真的出错，记日志并给一句通用提示——不把堆栈或 SQL 泄露给前端。
     */
    @ExceptionHandler(AdminException.class)
    public ResponseEntity<Result<Void>> handleAdminError(AdminException e) {
        return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
    }

    /**
     * 反馈链路的可解释失败（已处理、SQL 不合规、域不存在……）。
     *
     * <p>与 {@link AdminException} 一样属于输入/状态问题，400 而不是 500。
     */
    @ExceptionHandler(FeedbackException.class)
    public ResponseEntity<Result<Void>> handleFeedbackError(FeedbackException e) {
        return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
    }

    /**
     * 请求体不合法的转成 400。
     *
     * <p>最典型的一种是**字段名拼错**。管理 DTO 标了
     * {@code @JsonIgnoreProperties(ignoreUnknown = false)}，所以不认识字段会报错——
     * 这是刻意的：默认的"静默忽略"会让一个拼错的字段名悄悄跳过对应的校验，
     * 而校验代码看起来还在。实测踩到过（{@code driverClassName} vs {@code driverClass}）。
     *
     * <p>不回显 Jackson 的原始消息——它含类名与字段路径，对管理员没用。
     * 但**要点出是哪个字段**，否则管理员无从下手。
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        String raw = e.getMessage() == null ? "" : e.getMessage();
        String hint = raw.contains("Unrecognized field")
                ? "请求里有服务端不认识的字段，请检查字段名是否拼错"
                : "请求体格式不正确";
        log.warn("管理面请求体无法解析：{}", raw);
        return ResponseEntity.badRequest().body(Result.error(400, hint));
    }

    /**
     * 权限不足 → <strong>403</strong>，不是 500。
     *
     * <p>类级 {@code @PreAuthorize("@perm.allowAdminApi()")} 抛的是
     * {@code AuthorizationDeniedException}（{@code AccessDeniedException} 的子类，
     * 也是 {@code RuntimeException}）。没有这个方法，它会被下面的
     * {@link #handleUnexpected(Exception)} 吃成 **500「处理失败，请稍后重试」**——
     * 用户以为系统坏了，而真正要做的是去找管理员开权限。
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException e,
            HttpServletRequest request) {
        log.warn("管理面权限不足：user={} {} {}",
                currentUser.username().orElse("anonymous"),
                request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.error(403, "您没有该操作权限"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception e) {
        log.error("管理面未预期错误", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(500, "操作失败，请稍后重试"));
    }
}

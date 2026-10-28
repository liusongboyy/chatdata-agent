package com.liusongboyy.chatdata.engine.chat;

import com.liusongboyy.chatdata.engine.agent.AskDeniedException;
import com.liusongboyy.chatdata.engine.agent.EngineNotConfiguredException;
import com.liusongboyy.chatdata.engine.audit.AskAuditOutcome;
import com.liusongboyy.chatdata.engine.audit.AskAuditRecorder;
import com.liusongboyy.chatdata.engine.datasource.DatasourceResolver;
import com.liusongboyy.chatdata.engine.datasource.QueryResult;
import com.liusongboyy.chatdata.engine.datasource.SqlTools;
import com.liusongboyy.chatdata.engine.raw.RawPromptAssembler;
import com.liusongboyy.chatdata.engine.raw.SqlExample;
import com.liusongboyy.chatdata.engine.raw.SqlExampleSelector;
import com.liusongboyy.chatdata.engine.raw.RawSchemaBuilder;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 直连模式的问数：**只勾表，模型自己读结构、自己写 SQL**。
 *
 * <h2>与建模模式的根本差别</h2>
 *
 * <table>
 *   <tr><th></th><th>建模模式</th><th>直连模式</th></tr>
 *   <tr><td>模型产出</td><td>问数 DSL</td><td>SQL</td></tr>
 *   <tr><td>SQL 由谁写</td><td>我们（{@code AskSqlRenderer}）</td><td>模型</td></tr>
 *   <tr><td>行级/列级权限</td><td><b>生效</b></td><td><b>不生效</b></td></tr>
 *   <tr><td>语义层校验</td><td>字段存在性、聚合合法性…</td><td>无</td></tr>
 * </table>
 *
 * <h2>⚠️ 这个模式没有行/列权限，而且是**设计使然**</h2>
 *
 * <p>模型写的 SQL 形状任意（CTE / UNION / 子查询 / 窗口函数），没有安全的注入点——
 * {@code docs/adr/0002} 已评估并否决"解析后注入谓词"。
 *
 * <p>所以它**只做表级授权**：{@code ask_domain_table} 里登记且启用的那几张表就是白名单，
 * 由 {@link SqlTools} 在执行前强制（不是靠 prompt 请求模型自觉）。
 * <b>敏感列必须靠"不把带敏感列的表放进白名单"来挡。</b>
 * 管理台与问数页都明示这一点。
 *
 * <h2>没有 DSL，也就没有确定性检查点</h2>
 *
 * <p>建模模式的核心保证是"DSL 是检查点"——权限、字段存在性、审计都挂在 DSL 上。
 * 直连模式没有这一层，因此**静默语义错误的风险更高**（模型可以悄悄漏掉一个条件）。
 * 界面把模型写的 SQL 摊开给用户看，是这一模式下唯一的兜底。
 */
public final class RawAskService implements AskFacade {

    private static final Logger log = LoggerFactory.getLogger(RawAskService.class);

    /** 单次查询返回行数上限。与建模模式保持同一个量级。 */
    private static final int MAX_ROWS = 1000;
    /** 查询超时（秒）。 */
    private static final int TIMEOUT_SECONDS = 30;
    /**
     * ReAct 循环上限。
     *
     * <p>要够模型完成「看结构 → 写 SQL → 看结果 → 作答」，又不能失控——
     * 每一轮都是一次模型调用，钱和延迟都在这里。
     */
    private static final int MAX_ITERS = 6;

    private final Domain domain;
    private final Model model;
    private final DatasourceResolver datasources;
    private final RawSchemaBuilder schemas;
    private final AskAuditRecorder auditRecorder;
    /** 该域的范例库（已按 enabled 过滤）。为空表示没有范例，那就少一块提示。 */
    private final List<SqlExample> sqlExamples;

    public RawAskService(Domain domain, Model model, DatasourceResolver datasources,
                         RawSchemaBuilder schemas, AskAuditRecorder auditRecorder,
                         List<SqlExample> sqlExamples) {
        this.domain = domain;
        this.model = model;
        this.datasources = datasources;
        this.schemas = schemas;
        this.auditRecorder = auditRecorder == null ? AskAuditRecorder.NOOP : auditRecorder;
        this.sqlExamples = sqlExamples == null ? List.of() : List.copyOf(sqlExamples);
    }

    @Override
    public AskResult askStreaming(AskRequest request, Consumer<AskEvent> sink) {
        sink.accept(new AskEvent.Started(request.question()));
        return doAsk(request, stage -> sink.accept(new AskEvent.Progress(stage, stage.label())));
    }

    @Override
    public String domainId() {
        return domain.id();
    }

    @Override
    public String domainName() {
        return domain.displayName();
    }

    @Override
    public List<String> availableFields() {
        // 直连模式没有"登记过的字段"——模型看的是库里的真实表结构。
        // 返回空列表而不是表名：界面拿它渲染的是「按 X 看看数据」这类引导句，
        // 塞表名进去会得到"按 ins_policy 看看数据"，没有意义
        return List.of();
    }

    private AskResult doAsk(AskRequest request, AskProgressListener progress) {
        long t0 = System.currentTimeMillis();
        String sql = null;

        try {
            // ① 权限判定 —— **与建模模式共用同一个闸门**。
            // 直连模式下它就是唯一的闸门（行/列规则不生效），更不能放松
            progress.onStage(AskEvent.Stage.CHECKING_PERMISSION);
            AskPermissions.require(domain, request);

            List<String> tables = allowlistedTables();
            if (tables.isEmpty()) {
                throw new AskService.NotUnderstoodException(
                        "这个数据集还没有配置可访问的表，请联系管理员");
            }

            // ② 理解问题 + 查询数据 —— 都由模型在这一轮里完成。
            // 阶段事件先发出去：模型会边看结构边查，没法在中途精确地报"现在开始查了"
            progress.onStage(AskEvent.Stage.GENERATING_DSL);
            progress.onStage(AskEvent.Stage.EXECUTING);

            SqlTools tools = new SqlTools(datasources.resolve(domain.datasourceId()),
                    MAX_ROWS, TIMEOUT_SECONDS, Set.copyOf(tables));
            String schema = schemas.describe(domain.datasourceId(), tables);

            Msg reply = runAgent(request, tools, schema, tables);
            log.debug("直连模式模型回复长度={}", reply == null ? -1 : reply.getTextContent().length());

            // ③ 模型没有查库 —— 那它必须给出了一段文字答复，否则就是真的没听懂。
            //
            // **这就是"能力问题"的出口**：用户问「这里能分析哪些数据」，
            // 正确答案不是一张表，而是基于表结构的一段说明。逼模型为它写 SQL
            // 只会得到一条无意义的查询，然后失败——用户会以为系统坏了。
            //
            // 这一条与"数据问题"的分界由 prompt 界定（见 RawPromptAssembler 的规则 6），
            // 而这里只负责：没查库但有文字 → 当作能力答复返回；两者都没有 → 没听懂。
            if (tools.lastSql() == null) {
                String text = reply == null ? null : reply.getTextContent();
                if (text == null || text.isBlank()) {
                    throw new AskService.NotUnderstoodException(
                            "模型没有生成可执行的查询，请换一种说法，或补充要查的指标与范围");
                }
                long textDuration = System.currentTimeMillis() - t0;
                AskResult textOnly = new AskResult(
                        null, null, null,
                        QueryResult.empty(),
                        Set.of(),
                        textDuration,
                        // 没有数据表，也就没有图表。仍给一个占位而不是 null：
                        // 前端按 answer 决定渲染什么，不去读它；给 null 反而多一处判空
                        ChartSpec.table("这是能力答复，没有数据表"),
                        null,
                        text.trim(),
                        Domain.Mode.RAW);

                AskAuditWriter.write(auditRecorder, request, domain.id(), null, null,
                        null, textDuration, AskAuditOutcome.SUCCESS, null);
                return textOnly;
            }
            sql = tools.lastSql();
            QueryResult result = tools.lastResult();

            long duration = System.currentTimeMillis() - t0;
            AskResult askResult = new AskResult(
                    null,                       // 没有 DSL
                    null,                       // 没有我们渲染的 SQL
                    sql,                        // 模型写的 SQL
                    result,
                    Set.of(),                   // 没有列级脱敏
                    duration,
                    // 直连模式不做图表推荐：推荐要看字段的业务语义（哪些是指标、哪个是维度），
                    // 而这一模式没有语义层。前端仍可手动切形态
                    ChartSpec.table("直连模式不做图表推荐，先用表格；可以手动切换形态"),
                    null,                       // 没有可回显的结构化事实
                    null,                       // 数据问题没有文字答复
                    Domain.Mode.RAW);

            AskAuditWriter.write(auditRecorder, request, domain.id(), null, sql,
                    result.rowCount(), duration, AskAuditOutcome.SUCCESS, null);
            return askResult;

        } catch (AskDeniedException e) {
            AskAuditWriter.write(auditRecorder, request, domain.id(), null, sql,
                    null, System.currentTimeMillis() - t0, AskAuditOutcome.BLOCKED, e.reason().name());
            throw e;

        } catch (AskService.NotUnderstoodException e) {
            AskAuditWriter.write(auditRecorder, request, domain.id(), null, sql,
                    null, System.currentTimeMillis() - t0, AskAuditOutcome.FAILURE,
                    AskEvent.FailureCode.NOT_UNDERSTOOD);
            throw e;

        } catch (RuntimeException e) {
            // **兜底也要留审计。**今天已经栽过两次：未分类的异常会一路穿到最外层，
            // 用户看到"处理失败"，而审计里什么都没有——查无实据
            log.warn("直连模式问数失败：user={} question={} 原因={}",
                    request.username(), request.question(), e.getMessage());
            AskAuditWriter.write(auditRecorder, request, domain.id(), null, sql,
                    null, System.currentTimeMillis() - t0, AskAuditOutcome.FAILURE, e.getMessage());
            throw e;
        }
    }

    /**
     * 跑一轮 agent。
     *
     * <p>模型 Bean 是 {@code @Lazy} 的（缺密钥时不该让应用起不来），所以"没配密钥"
     * 这个错会**在这里才抛**，类型是 Spring 的 Bean 创建异常。翻译成
     * {@link EngineNotConfiguredException}，否则用户会看到一句误导的
     * "处理失败，请稍后重试"——重试永远不会成功。
     */
    private Msg runAgent(AskRequest request, SqlTools tools, String schema, List<String> tables) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);

        ReActAgent agent;
        try {
            agent = ReActAgent.builder()
                    .name("raw-nlsql")
                    .sysPrompt(RawPromptAssembler.assemble(domain, tables, schema,
                            // 挑最像的几条：给一条不相关的范例比不给更糟，
                            // 所以 selector 宁缺勿滥（见它的说明）
                            SqlExampleSelector.select(sqlExamples, request.question(), domain)))
                    .model(model)
                    .toolkit(toolkit)
                    .maxIters(MAX_ITERS)
                    .build();
        } catch (BeansException e) {
            throw new EngineNotConfiguredException(
                    "问数引擎的模型未配置成功：" + rootMessage(e)
                            + "。这是服务端配置问题，重试不会成功", e);
        }

        RuntimeContext ctx = RuntimeContext.builder()
                .userId(request.userId())
                .sessionId(request.sessionId())
                .build();
        return agent.call(List.of(new UserMessage(request.question())), ctx).block();
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        String best = e.getMessage();
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
            if (t.getMessage() != null && !t.getMessage().isBlank()) {
                best = t.getMessage();
            }
        }
        return best == null ? e.getClass().getSimpleName() : best;
    }

    /**
     * 白名单：域内**启用**的注册表。
     *
     * <p>直连模式下这里的每一行就是"允许模型访问的一张表"，所以只有 enabled 的算数——
     * 管理员关掉一张表，就等于把它从白名单里摘掉。
     */
    private List<String> allowlistedTables() {
        return domain.visibleTables().stream()
                .map(RegisteredTable::referenceName)
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

}

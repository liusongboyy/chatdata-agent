package com.liusongboyy.chatdata.engine.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liusongboyy.chatdata.engine.agent.AskDeniedException;
import com.liusongboyy.chatdata.engine.agent.DslGenerationException;
import com.liusongboyy.chatdata.engine.agent.DslSource;
import com.liusongboyy.chatdata.engine.agent.EngineNotConfiguredException;
import com.liusongboyy.chatdata.engine.agent.Generated;
import com.liusongboyy.chatdata.engine.agent.TokenUsage;
import com.liusongboyy.chatdata.engine.agent.PromptAssembler;
import com.liusongboyy.chatdata.engine.audit.AskAuditOutcome;
import com.liusongboyy.chatdata.engine.audit.AskAuditRecord;
import com.liusongboyy.chatdata.engine.audit.AskAuditRecorder;
import com.liusongboyy.chatdata.engine.datasource.DatasourceRegistry;
import com.liusongboyy.chatdata.engine.datasource.QueryResult;
import com.liusongboyy.chatdata.engine.datasource.SqlExecutor;
import com.liusongboyy.chatdata.engine.datasource.dialect.SqlDialect;
import com.liusongboyy.chatdata.engine.nlsql.AskDsl;
import com.liusongboyy.chatdata.engine.nlsql.AskSqlRenderer;
import com.liusongboyy.chatdata.engine.nlsql.ConstraintCheck;
import com.liusongboyy.chatdata.engine.nlsql.RenderedSql;
import com.liusongboyy.chatdata.engine.nlsql.TimeRangeResolver;
import com.liusongboyy.chatdata.engine.permission.PermissionContext;
import com.liusongboyy.chatdata.engine.permission.PermissionDecision;
import com.liusongboyy.chatdata.engine.permission.PermissionGuard;
import com.liusongboyy.chatdata.engine.semantic.SemanticException;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

/**
 * 问数服务：把引擎各层串起来，并负责审计与进度通知。
 *
 * <h2>执行顺序是固定的</h2>
 *
 * <pre>
 *   权限判定 → 生成 DSL → 渲染 SQL → 执行 → 审计
 * </pre>
 *
 * <p><strong>权限在最前，任何模型调用之前。</strong>除了省一次 LLM 调用，
 * 更因为顺序一旦反过来，将来有人"优化"成并发生成 DSL 再判权限，
 * 权限就从闸门降级成了事后检查。
 *
 * <h2>{@link #ask} 与 {@link #askStreaming} 的关系</h2>
 *
 * <p>两者走**同一条实现**，区别只在进度通知：前者不通知，后者把阶段回调出去。
 * 不做成两套代码，是因为两套代码迟早会分叉——而分叉点一定会出现在权限或审计上。
 */
public class AskService implements AskFacade {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);
    private static final ObjectMapper M = new ObjectMapper();

    private final SemanticModel model;
    private final SqlDialect dialect;
    private final DslSource dslSource;
    private final SqlExecutor sqlExecutor;
    private final AskAuditRecorder auditRecorder;
    /** 分析编排；为 null 表示这个域/环境不支持（例如模型未配置）。 */
    private final AnalysisService analysisService;
    /**
     * 本域实际使用的模型键，写进审计。
     *
     * <p>解析发生在 {@link AskServiceFactory}（那里才知道域绑了什么、默认是什么），
     * 服务层只负责把它记下来。<b>不在这里重新解析</b>——两处各解析一遍，
     * 迟早会出现「审计记的模型」与「实际调的模型」不一致，而那种错只有对账时才发现。
     *
     * <p>可为 null：老配置回落到 yml 的模型时没有 key。
     */
    private final String modelKey;

    public AskService(SemanticModel model,
                      SqlDialect dialect,
                      DslSource dslSource,
                      SqlExecutor sqlExecutor,
                      AskAuditRecorder auditRecorder) {
        this(model, dialect, dslSource, sqlExecutor, auditRecorder, null, null);
    }

    public AskService(SemanticModel model,
                      SqlDialect dialect,
                      DslSource dslSource,
                      SqlExecutor sqlExecutor,
                      AskAuditRecorder auditRecorder,
                      AnalysisService analysisService) {
        this(model, dialect, dslSource, sqlExecutor, auditRecorder, analysisService, null);
    }

    /**
     * @param modelKey 本域实际使用的模型键，写进审计；null 表示回落到了 yml 配置（没有 key）
     */
    public AskService(SemanticModel model,
                      SqlDialect dialect,
                      DslSource dslSource,
                      SqlExecutor sqlExecutor,
                      AskAuditRecorder auditRecorder,
                      AnalysisService analysisService,
                      String modelKey) {
        this.model = model;
        this.dialect = dialect;
        this.dslSource = dslSource;
        this.sqlExecutor = sqlExecutor;
        this.auditRecorder = auditRecorder == null ? AskAuditRecorder.NOOP : auditRecorder;
        this.analysisService = analysisService;
        this.modelKey = modelKey;
    }

    @Override
    public boolean supportsAnalysis() {
        return analysisService != null && analysisService.available();
    }

    /**
     * 分析编排入口。
     *
     * <h2>计划失败时回退单查询，并**说明**回退了</h2>
     *
     * <p>不说明的降级比没有降级更糟：用户以为自己看的是"+结论"，其实是一次普通查询。
     */
    @Override
    public AskResult analyzeStreaming(AskRequest request, Consumer<AskEvent> sink) {
        if (!supportsAnalysis()) {
            return askStreaming(request, sink);
        }
        sink.accept(new AskEvent.Started(request.question()));
        try {
            return analysisService.analyze(request, sink::accept);
        } catch (AskDeniedException e) {
            throw e;   // 权限拒绝没有"回退"一说
        } catch (DslGenerationException | SemanticException | IllegalArgumentException e) {
            log.info("分析计划不可用，回退单查询：user={} 原因={}",
                    request.username(), e.getMessage());
            return askStreaming(request, sink)
                    .withNotice("分析计划未能生成（" + e.getMessage() + "），已回退为单次查询");
        }
    }

    @Override
    public String domainId() {
        return model.domain().id();
    }

    @Override
    public String domainName() {
        return model.domain().displayName();
    }

    /** 同步问数。 */
    public AskResult ask(AskRequest request) {
        return run(request, AskProgressListener.NOOP, this::generateStage);
    }

    /**
     * 流式问数：把每个阶段推给 {@code sink}。
     *
     * <p>注意这是**同步方法**——它会在当前线程上跑完整个链路，
     * 阶段事件是通过回调同步发出的。调用方若要 SSE，需要自己在工作线程上调用它
     * （见 {@code AskStreamController} 的跨线程说明）。
     */
    @Override
    public AskResult askStreaming(AskRequest request, Consumer<AskEvent> sink) {
        sink.accept(new AskEvent.Started(request.question()));
        return run(request, stage -> sink.accept(new AskEvent.Progress(stage, stage.label())),
                this::generateStage);
    }

    /**
     * 重算（FR-5.5）：DSL 已经确定，**不调模型**。
     *
     * <h2>为什么它必须存在，且不能只是"内部测试工具"</h2>
     *
     * <p>它有两个消费方，缺一不可：
     *
     * <ol>
     *   <li><b>用户侧的"改一下条件再查"</b>——查出 0 行时最常见的下一步是去掉
     *       时间范围或某个筛选重试。走模型重问一次既慢又贵，而且模型很可能
     *       把同一个问题翻译成同一个 DSL，等于原地打转</li>
     *   <li><b>权限的确定性回归测试</b>——ADR-0002 的结论要靠它验证：
     *       同一份 DSL 在固定角色下重算，权限改写的结果必须完全一致。
     *       没有这条链路，权限就只能靠"问一句看看"来测，那不叫测试</li>
     * </ol>
     *
     * <h2>权限仍然在最前，且**不来自调用方**</h2>
     *
     * <p>传入的 DSL 可能来自浏览器，所以这里只把它当作"用户的意图"，
     * 而**判定主体永远从令牌解析**（见调用方），行规则与列脱敏照常施加。
     * 这一点决定了接受客户端 DSL 是安全的：它能做的事，与让模型产出一个 DSL
     * 完全等价——字段解析、权限改写、脱敏、只读、行数上限一个都不少。
     *
     * @param dsl 已经确定的问数 DSL
     */
    public AskResult recompute(AskDsl dsl, AskRequest request) {
        return run(request, AskProgressListener.NOOP,
                (req, decision, today, progress, usage) ->
                        renderGiven(dsl, decision, today, progress, usage));
    }

    /**
     * 两条链路**唯一的分叉点**：DSL 从哪来。
     *
     * <p>其余（权限判定 → 渲染 → 执行 → 审计）完全共用。分成两套代码的话，
     * 分叉迟早会出现在权限或审计上——那正是不能接受的地方。
     */
    @FunctionalInterface
    private interface DslStage {
        Rendered obtain(AskRequest request, PermissionDecision decision, LocalDate today,
                        AskProgressListener progress, UsageMeter usage);
    }

    /**
     * 请求内的 token 累加器。
     *
     * <p><b>一次提问可能调多次模型</b>：约束缺失时重问一次、渲染失败时再重问一次。
     * 只记最后一次的用量会系统性低估成本，而低估的部分恰好落在
     * "这个域老是重问"这种最该被看见的场景上。
     *
     * <p>它只是请求内的局部变量（在 {@link #run} 里新建），不是字段——
     * 做成字段会在并发提问下互相覆盖，而那种错是静默的。
     */
    private static final class UsageMeter {
        private TokenUsage total = TokenUsage.UNKNOWN;

        void add(Generated<?> generated) {
            if (generated != null) {
                total = total.plus(generated.usage());
            }
        }

        TokenUsage total() {
            return total;
        }
    }

    /** 共用主干：权限 → 取 DSL 并渲染 → 执行 → 审计。 */
    private AskResult run(AskRequest request, AskProgressListener progress, DslStage stage) {
        long t0 = System.currentTimeMillis();

        // 参考日期**整个请求只取一次**。渲染与回显各取一次的话，跨零点的那次请求
        // 会出现"SQL 里查的是昨天算出的上个月、界面上说的是今天算出的上个月"——
        // 差一天，而回显的全部意义就是不说谎
        LocalDate today = LocalDate.now();

        // 失败时审计要能记下"当时用的是哪份 DSL / SQL"，故在主干里持有
        AskDsl dsl = null;
        RenderedSql rendered = null;
        UsageMeter usage = new UsageMeter();

        try {
            // ① 权限判定 —— **两条链路都在最前**，在任何模型调用与渲染之前。
            // 与直连模式共用同一个闸门：准入的判据绝不能有两种写法
            progress.onStage(AskEvent.Stage.CHECKING_PERMISSION);
            PermissionDecision decision = AskPermissions.require(model.domain(), request);

            // ② 取 DSL 并渲染
            Rendered obtained = stage.obtain(request, decision, today, progress, usage);
            dsl = obtained.dsl();
            rendered = obtained.sql();

            // ③ 执行
            progress.onStage(AskEvent.Stage.EXECUTING);
            QueryResult result = sqlExecutor.execute(model.domain().datasourceId(), rendered);

            long duration = System.currentTimeMillis() - t0;
            AskResult askResult = new AskResult(dsl, rendered, null, result,
                    ((PermissionDecision.Allowed) decision).maskedColumns(), duration,
                    ChartRecommender.recommend(dsl, model, result),
                    // 用**生效的** dsl（重试之后的那份）与**同一个** today：
                    // 回显一个从未执行过的意图、或另一个日期算出的时间范围，都比不回显更糟
                    AskUnderstanding.of(dsl, model, decision, today),
                    null,   // 建模模式没有"只说不用查"的答复：它的契约是产出 DSL
                    com.liusongboyy.chatdata.engine.semantic.Domain.Mode.MODELED);

            record(request, dsl, rendered, result.rowCount(), duration, AskAuditOutcome.SUCCESS,
                    null, usage.total());
            return askResult;

        } catch (AskDeniedException e) {
            // 拒绝单独记一类：审计里"被拒绝"与"查了但没数据"必须可区分（ADR-0002）
            record(request, dsl, rendered, null, System.currentTimeMillis() - t0,
                    AskAuditOutcome.BLOCKED, e.reason().name(), usage.total());
            throw e;

        } catch (NotUnderstoodException e) {
            record(request, dsl, rendered, null, System.currentTimeMillis() - t0,
                    AskAuditOutcome.FAILURE, AskEvent.FailureCode.NOT_UNDERSTOOD, usage.total());
            throw e;

        } catch (DslGenerationException | SemanticException
                 | TimeRangeResolver.TimeRangeException
                 | SqlExecutor.SqlExecutionException
                 | DatasourceRegistry.DatasourceUnavailableException
                 | EngineNotConfiguredException e) {
            long duration = System.currentTimeMillis() - t0;
            // 配置缺失按 error 记：它不是"这次运气不好"，而是这个环境根本没起对
            if (e instanceof EngineNotConfiguredException) {
                log.error("问数引擎未配置：user={} 原因={}",
                        request.username(), e.getMessage());
            } else {
                log.warn("问数失败：user={} question={} 原因={}",
                        request.username(), abbreviate(request.question()), e.getMessage());
            }
            record(request, dsl, rendered, null, duration, AskAuditOutcome.FAILURE,
                    e.getMessage(), usage.total());
            throw e;

        } catch (RuntimeException e) {
            // **兜底也要留审计。**今天栽过两次：未被上面列出的异常类型（如渲染器抛的
            // IllegalArgumentException）会一路穿到最外层，用户看到"处理失败"，
            // 而审计里什么都没有——出了问题查无实据
            log.warn("问数失败（未分类）：user={} question={} type={} 原因={}",
                    request.username(), abbreviate(request.question()),
                    e.getClass().getSimpleName(), e.getMessage());
            record(request, dsl, rendered, null, System.currentTimeMillis() - t0,
                    AskAuditOutcome.FAILURE, e.getMessage(), usage.total());
            throw e;
        }
    }

    /** 生成链路：让模型产出 DSL，失败时带纠正提示重试一次。 */
    private Rendered generateStage(AskRequest request, PermissionDecision decision,
                                   LocalDate today, AskProgressListener progress,
                                   UsageMeter usage) {
        progress.onStage(AskEvent.Stage.GENERATING_DSL);
        String sysPrompt = PromptAssembler.assemble(model);
        AskDsl dsl = generate(request, sysPrompt, usage);

        if (dsl.isEmpty()) {
            // **不是"查询无数据"**，而是"没听懂"。两者必须分开，
            // 否则用户会以为库里真的没数据
            throw new NotUnderstoodException(
                    "没能理解这个问题，请换一种说法，或补充要查的指标与范围");
        }

        // **语义完整性校验必须在渲染之前。**
        // 一个漏了约束的 DSL 是**能渲染成功**的——它会安静地变成一次全量查询，
        // 数字看着正常，只是错的。等渲染完再查就晚了
        dsl = reaskIfConstraintsMissing(request, sysPrompt, dsl, today, usage);

        progress.onStage(AskEvent.Stage.RENDERING_SQL);
        return renderWithRetry(request, sysPrompt, dsl, decision, today, usage);
    }

    /**
     * 问题里的约束没落进 DSL 时，带着「你漏了什么」重问一次。
     *
     * <h2>为什么这是必须的，而不是模型调优能解决的</h2>
     *
     * <p>实测反复出现：DSL 结构合法、解析成功、无异常，但问题里的关键约束消失
     * （「上个月」丢过、「华东」「有效」丢过）。失败是**静默**的——监控看不到、
     * 审计里 DSL 合法，只有对着业务数字才能发现。
     *
     * <p>改 prompt 试过两次（基础 prompt 加规则、纠正提示加"别删条件"），
     * 都没能稳定纠正模型。所以只能由代码兜：**校验 + 重问**。
     * 这也正是 PRD §8 把它列为硬性要求的原因。
     *
     * <p><strong>只重问一次。</strong>再漏说明不是措辞问题，继续重问只是烧钱——
     * 那时把漏的东西留在界面上让用户自己看见（「我理解成了」）。
     */
    private AskDsl reaskIfConstraintsMissing(AskRequest request, String sysPrompt,
                                            AskDsl dsl, LocalDate today, UsageMeter usage) {
        List<String> missing = ConstraintCheck.findMissing(request.question(), dsl, model, today);
        if (missing.isEmpty()) {
            return dsl;
        }

        log.info("DSL 缺少问题里的约束，带提示重问一次：user={} 缺失={}",
                request.username(), missing);

        String corrected = sysPrompt + """

                ── 你漏掉了问题里的约束 ──
                %s

                **问题里的每一个约束都必须体现在 DSL 里**：
                - 时间范围写进 timeRange 字段，kind 用中文口径（如「上个月」「近30天」）
                - 筛选条件写进 filters 字段
                少一个条件，答案就是错的，而且**不会报错**——所以不要因为不确定就省略。
                """.formatted(String.join("\n", missing.stream().map(m -> "- " + m).toList()));

        AskDsl retry = generate(request, corrected, usage);
        // 重问反而什么都没解析出来时，保留第一次的：至少它还能渲染出一个结果，
        // 而不是把"约束不全"升级成"完全查不了"
        return retry.isEmpty() ? dsl : retry;
    }

    /**
     * 调一次 DSL 生成，并把**配置缺失**翻译成它自己的异常类型。
     *
     * <h2>为什么要单独接住 {@code BeansException}</h2>
     *
     * <p>{@code dslSource} 是 {@code @Lazy} 代理（见 {@link AskServiceFactory}），
     * 背后是 LLM 模型的 Bean。模型缺 api-key 时，**异常在调用 generate 的这一刻才抛**——
     * 类型是 Spring 的 Bean 创建异常，而不是任何业务异常。
     *
     * <p>不翻译的话它会被最外层兜底成一个"处理失败，请稍后重试"，于是：
     * 用户反复重试、运维看不到"去配密钥"这个真实信号，而且这条失败
     * **连审计都进不去**（它没落到 {@link #run} 的任何 catch 分支上）。
     */
    private AskDsl generate(AskRequest request, String sysPrompt, UsageMeter usage) {
        try {
            Generated<AskDsl> generated = dslSource.generate(request.question(), sysPrompt,
                    request.userId(), request.sessionId());
            // 先记账再取值：解析在 dslSource 内部完成，走到这里说明调用已经发生了
            usage.add(generated);
            return generated.value();
        } catch (BeansException e) {
            throw new EngineNotConfiguredException(
                    "问数引擎的模型未配置成功：" + rootMessage(e)
                            + "。这是服务端配置问题，重试不会成功", e);
        }
    }

    /** 取异常链最里层那句有用的说明——Spring 的包装层只重复"Error creating bean…"。 */
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

    /** 重算链路：DSL 已给定，直接渲染。 */
    private Rendered renderGiven(AskDsl dsl, PermissionDecision decision, LocalDate today,
                                 AskProgressListener progress, UsageMeter usage) {
        if (dsl == null || dsl.isEmpty()) {
            // 空 DSL 渲染不出任何东西。归到"没听懂"而不是抛 IllegalArgumentException——
            // 前者有明确的用户提示与审计分类，后者只会变成一句"处理失败"
            throw new NotUnderstoodException("这次重算没有可用的查询条件，请重新提问");
        }
        progress.onStage(AskEvent.Stage.RENDERING_SQL);
        return new Rendered(dsl, AskSqlRenderer.render(dsl, model, dialect, decision, today));
    }

    /**
     * 渲染结果：**实际生效的 DSL** 与渲染出的 SQL。
     *
     * <p>两个都要带出来——重试后 DSL 与首次尝试不同，而审计必须记**实际生效的那一份**。
     * 记错了等于审计里留着一条从未执行过的意图。
     */
    private record Rendered(AskDsl dsl, RenderedSql sql) {
    }

    /**
     * 渲染，字段解析失败时**带纠正提示重试一次**。
     *
     * <h2>为什么需要重试</h2>
     *
     * <p>实测：问「保费<strong>合计</strong>是多少」，模型产出
     * {@code "metrics": ["保费合计"]} —— 它用了**提问里的说法**，
     * 而不是语义域里登记的名字「保费」。prompt 里写了"只能使用上面列出的名字"，
     * 但模型仍会跟着用户的措辞走。
     *
     * <p>这是 PRD §8 要求的「DSL 校验 + 重试循环」的落点。没有它，
     * 用户只要换个说法问同一个指标就会失败——而换个说法正是最自然的提问方式。
     *
     * <p><strong>只重试一次。</strong>第二次仍失败说明不是措辞问题
     * （可能是域里真没这个字段），继续重试只是浪费模型调用。
     */
    private Rendered renderWithRetry(AskRequest request, String sysPrompt,
                                     AskDsl firstAttempt, PermissionDecision decision,
                                     LocalDate today, UsageMeter usage) {
        try {
            return new Rendered(firstAttempt,
                    AskSqlRenderer.render(firstAttempt, model, dialect, decision, today));
        } catch (SemanticException e) {
            log.info("首次渲染失败，带纠正提示重试一次：user={} 原因={}",
                    request.username(), e.getMessage());

            String corrected = sysPrompt + """

                    ── 上一次尝试失败了，请修正 ──
                    %s

                    **保留问题里的每一个限定条件**，只是换一种正确写法——
                    不要因为某个写法报错就把那个条件删掉。少一个条件，答案就是错的，而且不会报错。

                    - 字段名用上面列出的登记名字，不要用提问里的说法。
                      例如提问说「保费合计」，登记名是「保费」，就填「保费」。
                    - 时间范围（「上个月」「近10天」「今年」等）必须写进 timeRange 字段，
                      **不要写进 filters**；filters 里给日期列配值只能是 yyyy-MM-dd 的具体日期。

                    可用的字段名：%s
                    """.formatted(e.getMessage(), String.join("、", model.availableFieldNames()));

            AskDsl retry = generate(request, corrected, usage);

            // **重试也要判空。**首次尝试在 generateStage 里判过了，这一处曾经漏掉：
            // 模型被纠正提示绕晕、什么都没解析出来时，空 DSL 会直接进渲染器，
            // 抛 IllegalArgumentException("DSL 为空，无法渲染")——那个类型不在
            // run() 的分类 catch 里，用户看到的是"处理失败，请稍后重试"，
            // 完全不知道该做什么。这与"没听懂"是同一件事，用同一个异常表达。
            if (retry.isEmpty()) {
                throw new NotUnderstoodException(
                        "没能理解这个问题，请换一种说法，或补充要查的指标与范围");
            }
            return new Rendered(retry,
                    AskSqlRenderer.render(retry, model, dialect, decision, today));
        }
    }

    /** 写审计。委托给 {@link AskAuditWriter}——与直连模式共用同一套记录装配。 */
    private void record(AskRequest request, AskDsl dsl, RenderedSql rendered,
                        Integer rowCount, long duration, AskAuditOutcome outcome,
                        String errorMessage, TokenUsage usage) {
        AskAuditWriter.write(auditRecorder, request, model.domain().id(), dsl,
                rendered == null ? null : rendered.sql(), rowCount, duration, outcome,
                errorMessage, modelKey, usage);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "<null>";
        }
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }

    /**
     * 没能理解问题。
     *
     * <p>与"查询返回 0 行"是两回事：前者是模型没懂，后者是库里真没数据。
     * 用户对两者的反应完全不同，所以不能共用一个提示。
     */
    public static class NotUnderstoodException extends RuntimeException {
        public NotUnderstoodException(String message) {
            super(message);
        }
    }

    /**
     * 域内可问的字段名。
     *
     * <p>存在的理由：用户会问「你能问哪些数据」。那**不是数据问题，是能力问题**——
     * 让模型把它翻译成 SQL 只会得到一个语法正确但没有意义的查询，然后失败。
     * 界面应当直接回答它。
     */
    @Override
    public List<String> availableFields() {
        return model.availableFieldNames();
    }

}

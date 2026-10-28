package com.liusongboyy.chatdata.engine.chat;

import com.liusongboyy.chatdata.engine.agent.DslGenerationException;
import com.liusongboyy.chatdata.engine.agent.PromptAssembler;
import com.liusongboyy.chatdata.engine.analysis.AnalysisExecutor;
import com.liusongboyy.chatdata.engine.analysis.AnalysisPlan;
import com.liusongboyy.chatdata.engine.analysis.AnalysisPlanSource;
import com.liusongboyy.chatdata.engine.analysis.AnalysisPrompts;
import com.liusongboyy.chatdata.engine.analysis.AnalysisMemory;
import com.liusongboyy.chatdata.engine.analysis.AnalysisResult;
import com.liusongboyy.chatdata.engine.analysis.Finding;
import com.liusongboyy.chatdata.engine.analysis.NarrativeGuard;
import com.liusongboyy.chatdata.engine.analysis.NarrativeSource;
import com.liusongboyy.chatdata.engine.analysis.StepResult;
import com.liusongboyy.chatdata.engine.audit.AskAuditOutcome;
import com.liusongboyy.chatdata.engine.audit.AskAuditRecorder;
import com.liusongboyy.chatdata.engine.datasource.SqlExecutor;
import com.liusongboyy.chatdata.engine.datasource.dialect.SqlDialect;
import com.liusongboyy.chatdata.engine.permission.PermissionDecision;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

/**
 * 分析编排：拆解 → 执行 → 计算 → 叙述。
 *
 * <h2>与 {@link AskService} 的关系</h2>
 *
 * <p>单查询与编排共用**权限判定、渲染器、执行器、审计**，但控制流不同：
 * 单查询是"一次 DSL 一次 SQL"，编排是"一个计划 N 次"。故这里独立成类，
 * 而 {@link AskService} 只在入口处分流——两者都不复制对方的实现。
 *
 * <h2>降级</h2>
 *
 * <p>计划生成失败时抛 {@link DslGenerationException}，由 {@link AskService}
 * 回退到单查询，并在结果里带上降级说明。**不在这一层悄悄回退**：
 * 那样调用方会以为拿到的是一次完整分析。
 */
public final class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final SemanticModel model;
    private final SqlDialect dialect;
    private final SqlExecutor sqlExecutor;
    private final AskAuditRecorder auditRecorder;
    private final AnalysisPlanSource planSource;
    private final NarrativeSource narrativeSource;
    /** 上一轮分析记忆的存取；null 表示不做多轮记忆。 */
    private final io.agentscope.core.state.AgentStateStore memoryStore;

    public AnalysisService(SemanticModel model, SqlDialect dialect, SqlExecutor sqlExecutor,
                           AskAuditRecorder auditRecorder,
                           AnalysisPlanSource planSource, NarrativeSource narrativeSource) {
        this(model, dialect, sqlExecutor, auditRecorder, planSource, narrativeSource, null);
    }

    public AnalysisService(SemanticModel model, SqlDialect dialect, SqlExecutor sqlExecutor,
                           AskAuditRecorder auditRecorder,
                           AnalysisPlanSource planSource, NarrativeSource narrativeSource,
                           io.agentscope.core.state.AgentStateStore memoryStore) {
        this.model = model;
        this.dialect = dialect;
        this.sqlExecutor = sqlExecutor;
        this.auditRecorder = auditRecorder == null ? AskAuditRecorder.NOOP : auditRecorder;
        this.planSource = planSource;
        this.narrativeSource = narrativeSource;
        this.memoryStore = memoryStore;
    }

    /** 能否做分析。没有计划来源时（例如模型未配置）就退回单查询。 */
    public boolean available() {
        return planSource != null;
    }

    /**
     * 执行一次分析。
     *
     * @throws com.liusongboyy.chatdata.engine.agent.AskDeniedException 权限拒绝
     * @throws DslGenerationException                                   计划生成失败（调用方应回退单查询）
     */
    public AskResult analyze(AskRequest request, Consumer<AskEvent> progress) {
        long t0 = System.currentTimeMillis();
        LocalDate today = LocalDate.now();

        progress.accept(new AskEvent.Progress(AskEvent.Stage.CHECKING_PERMISSION,
                AskEvent.Stage.CHECKING_PERMISSION.label()));
        PermissionDecision decision = AskPermissions.require(model.domain(), request);

        progress.accept(new AskEvent.Progress(AskEvent.Stage.PLANNING,
                AskEvent.Stage.PLANNING.label()));
        String sysPrompt = AnalysisPrompts.withPlanRules(PromptAssembler.assemble(model));

        // 上一轮做过分析时，把它的**摘要**带进 prompt：追问「那华南呢」需要知道
        // 上轮查的是什么，否则模型只能把它当成一个缺主语的新问题。
        // 摘要而非原始行——行可能有上千条，塞进来只会把上下文撑爆
        AnalysisMemory memory = AnalysisMemory.load(memoryStore,
                request.userId(), request.sessionId());
        if (memory != null) {
            sysPrompt = sysPrompt + memory.toPromptBlock();
        }

        AnalysisPlan plan = planSource.generatePlan(
                request.question(), sysPrompt, request.userId(), request.sessionId());

        AnalysisExecutor executor = new AnalysisExecutor(model, dialect, sqlExecutor);
        AnalysisResult executed = executor.execute(plan, decision, today, (i, n) ->
                progress.accept(new AskEvent.Progress(AskEvent.Stage.EXECUTING,
                        "正在查询数据（" + i + "/" + n + "）")));

        progress.accept(new AskEvent.Progress(AskEvent.Stage.SYNTHESIZING,
                AskEvent.Stage.SYNTHESIZING.label()));
        String narrative = narrate(plan, executed, request);
        AnalysisResult analysis = new AnalysisResult(executed.intent(), executed.steps(),
                executed.findings(), narrative, executed.queryCount(), executed.truncated());

        // 存下这一轮的分析摘要，供下一轮追问使用。
        // **失败不影响本次问数**——见 AnalysisMemory.save
        AnalysisMemory.of(analysis).save(memoryStore, request.userId(), request.sessionId());

        long duration = System.currentTimeMillis() - t0;
        AskResult result = toAskResult(analysis, decision, today, duration);
        record(request, plan, analysis, duration, AskAuditOutcome.SUCCESS, null);
        return result;
    }

    /**
     * 写结论。**任何失败都退回确定性文本**，不让"措辞"这一步毁掉整次分析。
     */
    private String narrate(AnalysisPlan plan, AnalysisResult executed, AskRequest request) {
        if (narrativeSource == null) {
            return NarrativeGuard.deterministicNarrative(executed.findings());
        }
        try {
            String prompt = AnalysisPrompts.narrative(plan.intent(), executed);
            String text = narrativeSource.narrate(prompt, request.userId(), request.sessionId());
            if (text == null || text.isBlank()) {
                return NarrativeGuard.deterministicNarrative(executed.findings());
            }
            // 数字白名单：结论里的每个数字都必须能在 facts 的 evidence 里找到
            var violation = NarrativeGuard.violation(text, executed.findings(),
                    structuralNumbers(executed));
            if (violation.isPresent()) {
                log.warn("结论里出现未经验证的数字，退回确定性文本：user={} 原因={}",
                        request.username(), violation.get());
                return NarrativeGuard.deterministicNarrative(executed.findings());
            }
            return text;
        } catch (RuntimeException e) {
            log.warn("结论生成失败，退回确定性文本：user={} 原因={}",
                    request.username(), e.getMessage());
            return NarrativeGuard.deterministicNarrative(executed.findings());
        }
    }

    /**
     * 本次结果里的"结构性数字"：每个步骤的行数、每个维度的取值个数。
     *
     * <p>它们不是 findings 算出来的业务数字，但模型确实看到了，
     * 会在结论里说「这 7 个分类」「3 种状态」。把这些拦下来没有意义。
     */
    private static java.util.Set<java.math.BigDecimal> structuralNumbers(
            com.liusongboyy.chatdata.engine.analysis.AnalysisResult result) {
        java.util.Set<java.math.BigDecimal> out = new java.util.LinkedHashSet<>();
        for (StepResult s : result.steps()) {
            if (!s.isOk()) {
                continue;
            }
            out.add(java.math.BigDecimal.valueOf(s.rows().rowCount()));
            for (String dim : s.dsl() == null ? List.<String>of() : s.dsl().dimensions()) {
                int idx = s.rows().columns().indexOf(dim);
                if (idx < 0) {
                    continue;
                }
                out.add(java.math.BigDecimal.valueOf(s.rows().rows().stream()
                        .map(r -> idx < r.size() ? String.valueOf(r.get(idx)) : null)
                        .filter(java.util.Objects::nonNull)
                        .distinct().count()));
            }
        }
        return out;
    }

    /**
     * 把分析结果映射成 {@link AskResult}。
     *
     * <p>**第一个成功的步骤填充旧字段**（dsl/sql/result/chart/understanding）：
     * 这样即便前端还没适配编排，也仍能看到一张有意义的表，而不是空白。
     */
    private AskResult toAskResult(AnalysisResult analysis, PermissionDecision decision,
                                  LocalDate today, long durationMs) {
        StepResult primary = analysis.steps().stream()
                .filter(StepResult::isOk)
                .findFirst()
                .orElse(null);

        if (primary == null) {
            return new AskResult(null, null, null,
                    com.liusongboyy.chatdata.engine.datasource.QueryResult.empty(),
                    java.util.Set.of(), durationMs, null, null, null, Domain.Mode.MODELED,
                    analysis, analysis.allFailed() ? "所有分析步骤都没有查出来" : null);
        }

        return new AskResult(
                primary.dsl(),
                primary.sql(),
                null,
                primary.rows(),
                primary.maskedColumns(),
                durationMs,
                ChartRecommender.recommend(primary.dsl(), model, primary.rows()),
                AskUnderstanding.of(primary.dsl(), model, decision, today),
                null,
                Domain.Mode.MODELED,
                analysis,
                null);
    }

    private void record(AskRequest request, AnalysisPlan plan, AnalysisResult analysis,
                        long duration, AskAuditOutcome outcome, String error) {
        AskAuditWriter.writeAnalysis(auditRecorder, request, model.domain().id(), plan, analysis,
                totalRows(analysis), duration, outcome, error);
    }

    private static Integer totalRows(AnalysisResult analysis) {
        return analysis.steps().stream()
                .filter(StepResult::isOk)
                .mapToInt(s -> s.rows().rowCount())
                .sum();
    }

    /** 供测试与排查：当前域是否允许编排。 */
    List<Finding> findingsOf(AnalysisResult result) {
        return result.findings();
    }
}

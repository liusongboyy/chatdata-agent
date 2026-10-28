package com.liusongboyy.chatdata.engine.chat;

import com.liusongboyy.chatdata.engine.agent.AskDeniedException;
import com.liusongboyy.chatdata.engine.agent.DslGenerationException;
import com.liusongboyy.chatdata.engine.agent.DslSource;
import com.liusongboyy.chatdata.engine.agent.EngineNotConfiguredException;
import com.liusongboyy.chatdata.engine.audit.AskAuditOutcome;
import com.liusongboyy.chatdata.engine.audit.AskAuditRecord;
import com.liusongboyy.chatdata.engine.audit.AskAuditRecorder;
import com.liusongboyy.chatdata.engine.datasource.QueryResult;
import com.liusongboyy.chatdata.engine.datasource.SqlExecutor;
import com.liusongboyy.chatdata.engine.datasource.dialect.SqlDialects;
import com.liusongboyy.chatdata.engine.nlsql.AskDsl;
import com.liusongboyy.chatdata.engine.nlsql.TimeRangeResolver;
import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import com.liusongboyy.chatdata.engine.permission.PermissionDecision;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;
import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 问数编排的测试。**不调模型、不连库**——两个外部依赖都用假实现替换。
 *
 * <p>重点不是"链路能跑通"，而是**顺序约束**：
 * 权限被拒时不该调模型、没听懂时不该执行查询、任何失败都要留下审计。
 * 这些约束一旦破坏，症状都是"能跑但不对"，不测就发现不了。
 */
class AskServiceTest {

    private static final ObjectMapper M = new ObjectMapper();

    // ── 夹具 ──

    private static DomainPermission permOf(String json) {
        try {
            return DomainPermission.from(M.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException("夹具 JSON 写错", e);
        }
    }

    private static SemanticModel model(DomainPermission perm) {
        RegisteredTable policy = new RegisteredTable("1", "ins_policy", "保单表",
                RegisteredTable.Mode.TABLE, null, true, List.of(
                new ColumnDef("premium", "decimal", Aggregation.SUM, "保费", true),
                new ColumnDef("region", "varchar", Aggregation.NONE, "区域", true)));
        Domain domain = new Domain("d1", "保险分析域", "1", "MYSQL5.7", true,
                List.of(policy), List.of(), perm, List.of(), List.of());
        return new SemanticModel(domain);
    }

    private static final DomainPermission OPEN =
            permOf("""
                    {"v":1,"grantRoles":["分析师"],"unrestrictedRoles":["分析师"]}""");

    private static final DomainPermission RESTRICTED =
            permOf("""
                    {"v":1,"grantRoles":["区域经理"],
                     "rowRules":[{"id":1,"role":"区域经理","field":"region","op":"=","value":"华东"}]}""");

    private static AskRequest request(String username, String... roles) {
        // 刻意**不带时间词**：本类测的是顺序约束（权限→模型→执行→审计），
        // 而约束完整性另有专门的用例。带「上个月」会让每次调用都多一次
        // 完整性重问，把"模型只调一次"这类断言全部打乱
        return new AskRequest("华东的保费是多少", username, username,
                Set.of(roles), "s1", null);
    }

    /** 记录调用次数的假 DSL 源。 */
    private static class CountingDslSource implements DslSource {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> lastPrompt = new AtomicReference<>();
        AskDsl result = new AskDsl(List.of("保费"), List.of(), null, List.of());
        RuntimeException toThrow;

        @Override
        public AskDsl generate(String question, String sysPrompt, String userId, String sessionId) {
            calls.incrementAndGet();
            lastPrompt.set(sysPrompt);
            if (toThrow != null) {
                throw toThrow;
            }
            return result;
        }
    }

    /** 记录审计的假记录器。 */
    private static class CapturingAudit implements AskAuditRecorder {
        final List<AskAuditRecord> records = new ArrayList<>();

        @Override
        public void record(AskAuditRecord record) {
            records.add(record);
        }
    }

    /** 假的 SQL 执行器：把「执行」记成一个可断言的动作，不真连库。 */
    private static class FakeExecutor extends SqlExecutor {
        final AtomicInteger calls = new AtomicInteger();
        QueryResult result = new QueryResult(List.of("保费"), List.of(List.of(100)));
        RuntimeException toThrow;

        FakeExecutor() {
            super(null);
        }

        @Override
        public QueryResult execute(String datasourceId, com.liusongboyy.chatdata.engine.nlsql.RenderedSql sql) {
            calls.incrementAndGet();
            if (toThrow != null) {
                throw toThrow;
            }
            return result;
        }
    }

    private static AskService service(SemanticModel m, DslSource dsl, FakeExecutor exec, AskAuditRecorder audit) {
        return new AskService(m, SqlDialects.of("MYSQL5.7"), dsl, exec, audit);
    }

    @Nested
    @DisplayName("正常链路")
    class HappyPath {

        @Test
        @DisplayName("跑通并返回结果")
        void fullFlow() {
            CountingDslSource dsl = new CountingDslSource();
            FakeExecutor exec = new FakeExecutor();
            CapturingAudit audit = new CapturingAudit();

            AskResult r = service(model(OPEN), dsl, exec, audit)
                    .ask(request("u1", "分析师"));

            assertThat(r.result().rowCount()).isEqualTo(1);
            assertThat(dsl.calls.get()).isEqualTo(1);
            assertThat(exec.calls.get()).isEqualTo(1);
            assertThat(audit.records).hasSize(1);
            assertThat(audit.records.get(0).outcome()).isEqualTo(AskAuditOutcome.SUCCESS);
        }

        @Test
        @DisplayName("审计里记下 DSL 与 SQL —— 答错时第一现场是 DSL 不是 SQL")
        void auditCapturesDslAndSql() {
            CountingDslSource dsl = new CountingDslSource();
            CapturingAudit audit = new CapturingAudit();

            service(model(OPEN), dsl, new FakeExecutor(), audit).ask(request("u1", "分析师"));

            AskAuditRecord rec = audit.records.get(0);
            assertThat(rec.dslJson()).contains("保费");
            assertThat(rec.sqlText()).contains("SELECT").contains("SUM");
            assertThat(rec.username()).isEqualTo("u1");
        }

        @Test
        @DisplayName("★ 权限谓词真的进了 SQL —— 端到端确认权限没被漏掉")
        void permissionPredicateInSql() {
            CapturingAudit audit = new CapturingAudit();

            service(model(RESTRICTED), new CountingDslSource(), new FakeExecutor(), audit)
                    .ask(request("u1", "区域经理"));

            assertThat(audit.records.get(0).sqlText())
                    .as("审计里记的必须是**实际下发**的 SQL，所以行规则必须已经在里面")
                    .contains("`t1`.`region` = ?");
        }

        @Test
        @DisplayName("结果为空是 SUCCESS，不是失败")
        void emptyResultIsSuccess() {
            FakeExecutor exec = new FakeExecutor();
            exec.result = QueryResult.empty();
            CapturingAudit audit = new CapturingAudit();

            AskResult r = service(model(OPEN), new CountingDslSource(), exec, audit)
                    .ask(request("u1", "分析师"));

            assertThat(r.isEmpty()).isTrue();
            assertThat(audit.records.get(0).outcome())
                    .as("查了但没数据，与「没听懂」是两回事")
                    .isEqualTo(AskAuditOutcome.SUCCESS);
        }
    }

    @Nested
    @DisplayName("顺序约束 —— 这些破坏了症状都是「能跑但不对」")
    class Ordering {

        @Test
        @DisplayName("★ 权限被拒时不调用模型 —— 省成本，也避免语义域提示词泄露")
        void deniedDoesNotCallModel() {
            CountingDslSource dsl = new CountingDslSource();
            FakeExecutor exec = new FakeExecutor();
            CapturingAudit audit = new CapturingAudit();

            // 分析师不在 RESTRICTED 的 grantRoles 里
            assertThatThrownBy(() -> service(model(RESTRICTED), dsl, exec, audit)
                    .ask(request("u1", "分析师")))
                    .isInstanceOf(AskDeniedException.class);

            assertThat(dsl.calls.get())
                    .as("无权限的请求不该消耗 LLM 调用；语义域的提示词里含字段清单，调了就是泄露")
                    .isZero();
            assertThat(exec.calls.get()).isZero();
        }

        @Test
        @DisplayName("★ 权限被拒记成 BLOCKED，不是 FAILURE")
        void deniedRecordedAsBlocked() {
            CapturingAudit audit = new CapturingAudit();

            assertThatThrownBy(() -> service(model(RESTRICTED), new CountingDslSource(),
                    new FakeExecutor(), audit).ask(request("u1", "分析师")))
                    .isInstanceOf(AskDeniedException.class);

            assertThat(audit.records).hasSize(1);
            assertThat(audit.records.get(0).outcome())
                    .as("厂商把「被拒绝」与「查了但无数据」混在一起，审计上无法区分。这里必须分开")
                    .isEqualTo(AskAuditOutcome.BLOCKED);
        }

        @Test
        @DisplayName("★ 没听懂时不执行查询")
        void notUnderstoodDoesNotExecute() {
            CountingDslSource dsl = new CountingDslSource();
            dsl.result = new AskDsl(List.of(), List.of(), null, List.of());   // 空 DSL
            FakeExecutor exec = new FakeExecutor();
            CapturingAudit audit = new CapturingAudit();

            assertThatThrownBy(() -> service(model(OPEN), dsl, exec, audit)
                    .ask(request("u1", "分析师")))
                    .isInstanceOf(AskService.NotUnderstoodException.class);

            assertThat(exec.calls.get())
                    .as("没听懂却去执行，会得到一个查全表的结果——数字看着正常但是错的")
                    .isZero();
        }

        @Test
        @DisplayName("★ 没听懂的审计带 NOT_UNDERSTOOD 码，与执行失败区分开")
        void notUnderstoodRecordedDistinctly() {
            CountingDslSource dsl = new CountingDslSource();
            dsl.result = new AskDsl(List.of(), List.of(), null, List.of());
            CapturingAudit audit = new CapturingAudit();

            assertThatThrownBy(() -> service(model(OPEN), dsl, new FakeExecutor(), audit)
                    .ask(request("u1", "分析师")))
                    .isInstanceOf(AskService.NotUnderstoodException.class);

            assertThat(audit.records.get(0).errorMessage())
                    .isEqualTo(AskEvent.FailureCode.NOT_UNDERSTOOD);
        }
    }

    @Nested
    @DisplayName("失败都要留审计")
    class AuditOnFailure {

        @Test
        @DisplayName("DSL 生成失败也留审计")
        void dslFailureRecorded() {
            CountingDslSource dsl = new CountingDslSource();
            dsl.toThrow = new DslGenerationException("模型返回为空");
            CapturingAudit audit = new CapturingAudit();

            assertThatThrownBy(() -> service(model(OPEN), dsl, new FakeExecutor(), audit)
                    .ask(request("u1", "分析师")))
                    .isInstanceOf(DslGenerationException.class);

            assertThat(audit.records).hasSize(1);
            assertThat(audit.records.get(0).outcome()).isEqualTo(AskAuditOutcome.FAILURE);
            assertThat(audit.records.get(0).errorMessage()).contains("模型返回为空");
        }

        @Test
        @DisplayName("执行失败也留审计，并记下 SQL 供排查")
        void executionFailureRecorded() {
            FakeExecutor exec = new FakeExecutor();
            exec.toThrow = new SqlExecutor.SqlExecutionException("查询执行失败：Unknown column 'x'");
            CapturingAudit audit = new CapturingAudit();

            assertThatThrownBy(() -> service(model(OPEN), new CountingDslSource(), exec, audit)
                    .ask(request("u1", "分析师")))
                    .isInstanceOf(SqlExecutor.SqlExecutionException.class);

            AskAuditRecord rec = audit.records.get(0);
            assertThat(rec.outcome()).isEqualTo(AskAuditOutcome.FAILURE);
            assertThat(rec.errorMessage()).contains("Unknown column");
            assertThat(rec.sqlText())
                    .as("执行失败时 SQL 最有诊断价值，必须记下来")
                    .contains("SELECT");
        }

        @Test
        @DisplayName("★ 审计自身抛异常时，问数不受影响")
        void auditFailureDoesNotBreakAsk() {
            AskAuditRecorder exploding = record -> {
                throw new RuntimeException("审计表写不进去");
            };

            AskResult r = service(model(OPEN), new CountingDslSource(), new FakeExecutor(), exploding)
                    .ask(request("u1", "分析师"));

            assertThat(r.result().rowCount())
                    .as("用户问到了数据，却因为审计写失败拿不到结果，这个取舍是错的")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("流式进度")
    class Streaming {

        @Test
        @DisplayName("★ 依次收到四个阶段，顺序固定")
        void emitsStagesInOrder() {
            List<AskEvent> events = new ArrayList<>();

            service(model(OPEN), new CountingDslSource(), new FakeExecutor(), new CapturingAudit())
                    .askStreaming(request("u1", "分析师"), events::add);

            assertThat(events.get(0)).isInstanceOf(AskEvent.Started.class);

            List<AskEvent.Stage> stages = events.stream()
                    .filter(e -> e instanceof AskEvent.Progress)
                    .map(e -> ((AskEvent.Progress) e).stage())
                    .toList();

            assertThat(stages).containsExactly(
                    AskEvent.Stage.CHECKING_PERMISSION,
                    AskEvent.Stage.GENERATING_DSL,
                    AskEvent.Stage.RENDERING_SQL,
                    AskEvent.Stage.EXECUTING);
        }

        @Test
        @DisplayName("★ 权限被拒时只发到 CHECKING_PERMISSION —— 不该出现「正在理解问题」")
        void deniedStopsAtPermissionStage() {
            List<AskEvent> events = new ArrayList<>();

            assertThatThrownBy(() -> service(model(RESTRICTED), new CountingDslSource(),
                    new FakeExecutor(), new CapturingAudit())
                    .askStreaming(request("u1", "分析师"), events::add))
                    .isInstanceOf(AskDeniedException.class);

            List<AskEvent.Stage> stages = events.stream()
                    .filter(e -> e instanceof AskEvent.Progress)
                    .map(e -> ((AskEvent.Progress) e).stage())
                    .toList();

            assertThat(stages).containsExactly(AskEvent.Stage.CHECKING_PERMISSION);
        }

        @Test
        @DisplayName("每个阶段都有中文说明，前端可直接显示")
        void stagesHaveLabels() {
            for (AskEvent.Stage s : AskEvent.Stage.values()) {
                assertThat(s.label()).isNotBlank();
            }
        }
    }

    /**
     * 重试之后**又什么都没解析出来**时的表现。
     *
     * <p>来自真实页面的一次「处理失败，请稍后重试」：首次 DSL 渲染失败 → 带纠正提示重问 →
     * 模型这次产出了空 DSL → 空 DSL 直接进渲染器 → {@code IllegalArgumentException}。
     * 那个类型不在分类 catch 里，于是用户看到一句不知该做什么的话。
     */
    @Nested
    @DisplayName("重试后仍解析不出内容")
    class RetryProducesNothing {

        /** 第一次给一个渲染不了的 DSL（字段不存在），第二次给空的。 */
        private static class BadThenEmpty implements DslSource {
            final AtomicInteger calls = new AtomicInteger();

            @Override
            public AskDsl generate(String q, String p, String u, String s) {
                return calls.incrementAndGet() == 1
                        ? new AskDsl(List.of("根本不存在的字段"), List.of(), null, List.of())
                        : new AskDsl(List.of(), List.of(), null, List.of());
            }
        }

        @Test
        @DisplayName("★ 空 DSL 归到「没听懂」，而不是让渲染器抛 IllegalArgumentException")
        void emptyRetryBecomesNotUnderstood() {
            CountingDslSource dsl = new CountingDslSource();
            dsl.toThrow = null;

            BadThenEmpty source = new BadThenEmpty();
            CapturingAudit audit = new CapturingAudit();

            assertThatThrownBy(() -> service(model(OPEN), source, new FakeExecutor(), audit)
                    .ask(request("u1", "分析师")))
                    .as("必须是 NotUnderstoodException——它有明确的用户提示与审计分类")
                    .isInstanceOf(AskService.NotUnderstoodException.class)
                    .hasMessageContaining("换一种说法");

            assertThat(source.calls.get()).as("一次原始 + 一次重问").isEqualTo(2);
            assertThat(audit.records).singleElement()
                    .satisfies(r -> assertThat(r.outcome()).isEqualTo(AskAuditOutcome.FAILURE));
        }
    }

    /**
     * 约束完整性：问题里提到的时间没落进 DSL 时，自动带提示重问一次。
     *
     * <p>这条链在真实环境里救过一次：问「上个月华东的保费合计」，
     * 模型第一次把时间丢了，渲染照样成功、返回全量数字——**静默错误**。
     */
    @Nested
    @DisplayName("约束完整性 —— 漏了就重问，而不是静默给出错的数")
    class ConstraintCompleteness {

        /** 夹具域必须**有日期列**：否则补全后的 DSL 会因为找不到时间列而渲染失败。 */
        private static SemanticModel modelWithDate() {
            RegisteredTable policy = new RegisteredTable("1", "ins_policy", "保单表",
                    RegisteredTable.Mode.TABLE, null, true, List.of(
                    new ColumnDef("premium", "decimal", Aggregation.SUM, "保费", true),
                    new ColumnDef("region", "varchar", Aggregation.NONE, "区域", true),
                    new ColumnDef("effect_date", "date", Aggregation.NONE, "生效日期", true)));
            Domain domain = new Domain("d1", "保险分析域", "1", "MYSQL5.7", true,
                    List.of(policy), List.of(), permOf("""
                    {"v":1,"grantRoles":["分析师"],"unrestrictedRoles":["分析师"]}"""),
                    List.of(), List.of());
            return new SemanticModel(domain);
        }

        /** 问题里带了时间词，所以完整性校验一定会检查 timeRange。 */
        private static AskRequest timedRequest() {
            return new AskRequest("上个月华东的保费是多少", "u1", "u1", Set.of("分析师"), "s1", null);
        }

        private static final AskDsl COMPLETE = new AskDsl(List.of("保费"), List.of(),
                new AskDsl.TimeRange("上个月", null, null), List.of());
        private static final AskDsl INCOMPLETE = new AskDsl(List.of("保费"), List.of(),
                null, List.of());

        /** 前 n 次返回漏约束的 DSL。 */
        private static class LeakySource implements DslSource {
            final AtomicInteger calls = new AtomicInteger();
            final int leakFirst;
            String lastPrompt;

            LeakySource(int leakFirst) {
                this.leakFirst = leakFirst;
            }

            @Override
            public AskDsl generate(String q, String p, String u, String s) {
                lastPrompt = p;
                return calls.incrementAndGet() <= leakFirst ? INCOMPLETE : COMPLETE;
            }
        }

        @Test
        @DisplayName("★ 第一次漏了时间 → 自动重问，最终按补全后的 DSL 出数")
        void reasksWhenConstraintMissing() {
            LeakySource dsl = new LeakySource(1);
            CapturingAudit audit = new CapturingAudit();

            AskResult r = service(modelWithDate(), dsl, new FakeExecutor(), audit).ask(timedRequest());

            assertThat(dsl.calls.get()).as("第一次漏 → 重问一次").isEqualTo(2);
            assertThat(audit.records.get(0).sqlText())
                    .as("审计必须记**实际生效**的那份 DSL——否则留着一条从未执行过的意图")
                    .contains(">= ?");
            assertThat(audit.records.get(0).dslJson()).contains("上个月");
            assertThat(r.dsl().timeRange()).isNotNull();
        }

        @Test
        @DisplayName("★ 纠正提示必须点名漏了什么 —— 否则模型不知道怎么改")
        void correctionPromptNamesWhatIsMissing() {
            LeakySource dsl = new LeakySource(1);

            service(modelWithDate(), dsl, new FakeExecutor(), new CapturingAudit()).ask(timedRequest());

            assertThat(dsl.lastPrompt)
                    .contains("你漏掉了问题里的约束")
                    .contains("上个月")
                    .contains("timeRange");
        }

        @Test
        @DisplayName("★ 重问后仍漏 → 只重问一次，不无限烧钱")
        void reasksOnlyOnce() {
            LeakySource dsl = new LeakySource(99);   // 永远返回漏约束的版本
            CapturingAudit audit = new CapturingAudit();

            service(modelWithDate(), dsl, new FakeExecutor(), audit).ask(timedRequest());

            assertThat(dsl.calls.get())
                    .as("一次原始 + 一次重问，到此为止")
                    .isEqualTo(2);
            // 漏了也照常返回结果：剩下的靠界面上的「我理解成了」让用户看见
            assertThat(audit.records.get(0).outcome()).isEqualTo(AskAuditOutcome.SUCCESS);
        }

        @Test
        @DisplayName("约束完整时不额外调用模型 —— 这个校验不能常态化加钱")
        void noExtraCallWhenComplete() {
            LeakySource dsl = new LeakySource(0);

            service(modelWithDate(), dsl, new FakeExecutor(), new CapturingAudit()).ask(timedRequest());

            assertThat(dsl.calls.get()).isEqualTo(1);
        }
    }

    /**
     * 引擎没配置好时的表现。
     *
     * <p>这条来自一次真实事故：预览实例没注入 LLM 密钥，用户看到的却是
     * <b>"处理失败，请稍后重试"</b>——而重试永远不会成功，真实原因是服务端缺配置。
     * 更糟的是那个异常**逃出了所有 catch 分支，连审计都没留下**。
     */
    @Nested
    @DisplayName("引擎未配置 —— 必须与「模型抽风」分开")
    class NotConfigured {

        @Test
        @DisplayName("★ 模型 Bean 创建失败 → EngineNotConfiguredException，而不是裸的 Spring 异常")
        void beanFailureBecomesTypedException() {
            CountingDslSource dsl = new CountingDslSource();
            dsl.toThrow = new org.springframework.beans.factory.BeanCreationException(
                    "askEngineModel",
                    "Failed to instantiate [io.agentscope.core.model.Model]",
                    new IllegalStateException("缺少配置 chatdata.engine.model.api-key。密钥请通过环境变量注入"));

            assertThatThrownBy(() -> service(model(OPEN), dsl, new FakeExecutor(),
                    new CapturingAudit()).ask(request("u1", "分析师")))
                    .isInstanceOf(EngineNotConfiguredException.class)
                    .as("根因那句话必须带出来——运维靠它知道去配哪个键")
                    .hasMessageContaining("chatdata.engine.model.api-key")
                    .hasMessageContaining("重试不会成功");
        }

        @Test
        @DisplayName("★ 这条失败必须留审计 —— 否则「问了但什么都没发生」查无实据")
        void notConfiguredIsAudited() {
            CountingDslSource dsl = new CountingDslSource();
            dsl.toThrow = new org.springframework.beans.factory.BeanCreationException(
                    "askEngineModel", "boom", new IllegalStateException("缺少配置 api-key"));
            CapturingAudit audit = new CapturingAudit();

            assertThatThrownBy(() -> service(model(OPEN), dsl, new FakeExecutor(), audit)
                    .ask(request("u1", "分析师")))
                    .isInstanceOf(EngineNotConfiguredException.class);

            assertThat(audit.records).singleElement().satisfies(r -> {
                assertThat(r.outcome()).isEqualTo(AskAuditOutcome.FAILURE);
                assertThat(r.errorMessage()).contains("api-key");
            });
        }

        @Test
        @DisplayName("重试链路（第二次生成）同样要翻译 —— 两条调用点都不能漏")
        void retryPathIsAlsoTranslated() {
            // 第一次正常返回一个渲染不了的 DSL，触发带纠正提示的重试；
            // 重试时模型 Bean 创建失败
            CountingDslSource dsl = new CountingDslSource() {
                @Override
                public AskDsl generate(String q, String p, String u, String s) {
                    if (calls.incrementAndGet() > 1) {
                        throw new org.springframework.beans.factory.BeanCreationException(
                                "askEngineModel", "boom",
                                new IllegalStateException("缺少配置 api-key"));
                    }
                    return new AskDsl(List.of("根本不存在的字段"), List.of(), null, List.of());
                }
            };

            assertThatThrownBy(() -> service(model(OPEN), dsl, new FakeExecutor(),
                    new CapturingAudit()).ask(request("u1", "分析师")))
                    .isInstanceOf(EngineNotConfiguredException.class);
        }
    }

    /**
     * 重算（FR-5.5）。
     *
     * <p>这一组的重心不是"能不能跑通"，而是两条**必须成立**的性质：
     *
     * <ol>
     *   <li><b>不调模型。</b>它是"改一个条件再算一次"的底座，走模型就退回原点了</li>
     *   <li><b>权限一点不少。</b>DSL 可能来自浏览器，而判定主体只能来自令牌——
     *       行规则与列脱敏在重算路径上必须与生成路径完全一致</li>
     * </ol>
     */
    @Nested
    @DisplayName("重算 —— 不经模型，权限一点不少")
    class Recompute {

        private AskDsl dslOf(String... metrics) {
            return new AskDsl(List.of(metrics), List.of(), null, List.of());
        }

        @Test
        @DisplayName("★ 重算不调模型 —— 这是它存在的理由")
        void neverCallsTheModel() {
            CountingDslSource dsl = new CountingDslSource();
            FakeExecutor exec = new FakeExecutor();

            service(model(OPEN), dsl, exec, new CapturingAudit())
                    .recompute(dslOf("保费"), request("u1", "分析师"));

            assertThat(dsl.calls.get()).isZero();
            assertThat(exec.calls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 权限判定仍在最前：被拒时既不渲染也不执行，且审计记 BLOCKED")
        void permissionIsCheckedFirst() {
            CountingDslSource dsl = new CountingDslSource();
            FakeExecutor exec = new FakeExecutor();
            CapturingAudit audit = new CapturingAudit();

            // RESTRICTED 只授权「区域经理」，这里用一个没有该角色的人
            assertThatThrownBy(() -> service(model(RESTRICTED), dsl, exec, audit)
                    .recompute(dslOf("保费"), request("u1", "路人")))
                    .isInstanceOf(AskDeniedException.class);

            assertThat(dsl.calls.get()).isZero();
            assertThat(exec.calls.get()).isZero();
            assertThat(audit.records).singleElement()
                    .satisfies(r -> assertThat(r.outcome()).isEqualTo(AskAuditOutcome.BLOCKED));
        }

        @Test
        @DisplayName("★ 行规则在重算路径上同样注入 —— 客户端传来的 DSL 不是权限后门")
        void rowRulesStillApply() {
            CapturingAudit audit = new CapturingAudit();

            service(model(RESTRICTED), new CountingDslSource(), new FakeExecutor(), audit)
                    .recompute(dslOf("保费"), request("u1", "区域经理"));

            assertThat(audit.records.get(0).sqlText()).contains("`t1`.`region` = ?");
        }

        @Test
        @DisplayName("★ 同一 DSL 重算两次，SQL 完全一致 —— 权限回归测试靠的就是这条")
        void recomputeIsDeterministic() {
            CapturingAudit first = new CapturingAudit();
            CapturingAudit second = new CapturingAudit();

            AskDsl dsl = new AskDsl(List.of("保费"), List.of("区域"), null, List.of());

            AskResult a = service(model(RESTRICTED), new CountingDslSource(), new FakeExecutor(), first)
                    .recompute(dsl, request("u1", "区域经理"));
            AskResult b = service(model(RESTRICTED), new CountingDslSource(), new FakeExecutor(), second)
                    .recompute(dsl, request("u1", "区域经理"));

            // 只有 SQL 与参数逐字节一致，"权限改写的回归测试"才成立
            assertThat(a.sql().sql()).isEqualTo(b.sql().sql());
            assertThat(a.sql().params()).isEqualTo(b.sql().params());
            assertThat(first.records.get(0).sqlText()).isEqualTo(second.records.get(0).sqlText());
        }

        @Test
        @DisplayName("重算的结果同样带回显与图表推荐，与生成链路一个形状")
        void resultShapeMatchesGeneratedPath() {
            AskResult r = service(model(OPEN), new CountingDslSource(), new FakeExecutor(),
                    new CapturingAudit())
                    .recompute(dslOf("保费"), request("u1", "分析师"));

            assertThat(r.understanding().metrics()).containsExactly("保费");
            assertThat(r.chart()).isNotNull();
            assertThat(r.sql().sql()).contains("SELECT");
        }

        @Test
        @DisplayName("空 DSL：归到「没听懂」而不是 IllegalArgumentException —— 前者有提示也有审计分类")
        void emptyDslIsNotUnderstood() {
            CapturingAudit audit = new CapturingAudit();

            assertThatThrownBy(() -> service(model(OPEN), new CountingDslSource(), new FakeExecutor(), audit)
                    .recompute(new AskDsl(List.of(), List.of(), null, List.of()),
                            request("u1", "分析师")))
                    .isInstanceOf(AskService.NotUnderstoodException.class);

            assertThat(audit.records).singleElement()
                    .satisfies(r -> assertThat(r.outcome()).isEqualTo(AskAuditOutcome.FAILURE));
        }

        @Test
        @DisplayName("★ 时间口径解析失败要留审计 —— 此前这条路径没有审计记录")
        void unresolvableTimeRangeIsAudited() {
            CapturingAudit audit = new CapturingAudit();
            // 「本财年」不在 TimeRangeResolver 支持的口径里（它只认自然年/季/月/日与近N天）。
            // 注意别用「上个季度」——那是**支持**的，测不出这条路径
            AskDsl weird = new AskDsl(List.of("保费"), List.of(),
                    new AskDsl.TimeRange("本财年", null, null), List.of());

            assertThatThrownBy(() -> service(model(OPEN), new CountingDslSource(), new FakeExecutor(), audit)
                    .recompute(weird, request("u1", "分析师")))
                    .isInstanceOf(TimeRangeResolver.TimeRangeException.class);

            // TimeRangeException 不是 SemanticException，以前会一路穿出去、
            // 既没有审计也没有明确提示
            assertThat(audit.records).singleElement()
                    .satisfies(r -> assertThat(r.outcome()).isEqualTo(AskAuditOutcome.FAILURE));
        }
    }
}

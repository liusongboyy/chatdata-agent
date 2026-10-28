package com.liusongboyy.chatdata.engine.agent;

import com.liusongboyy.chatdata.engine.datasource.dialect.SqlDialect;
import com.liusongboyy.chatdata.engine.nlsql.AskDsl;
import com.liusongboyy.chatdata.engine.nlsql.AskSqlRenderer;
import com.liusongboyy.chatdata.engine.nlsql.RenderedSql;
import com.liusongboyy.chatdata.engine.permission.PermissionDecision;
import com.liusongboyy.chatdata.engine.permission.PermissionGuard;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import com.liusongboyy.chatdata.engine.permission.PermissionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;

/**
 * 问数链路。**这是引擎对外的门面**——上层只该调用它，不要自己拼装各层。
 *
 * <h2>固定顺序，权限在最前</h2>
 *
 * <pre>
 *   权限判定  →  生成 DSL  →  渲染 SQL
 * </pre>
 *
 * <p><strong>权限判定放在最前</strong>，在任何模型调用之前。理由有两条：
 *
 * <ol>
 *   <li>无权限的请求**不该消耗 LLM 调用**——既有成本也有信息泄露面
 *       （语义域的提示里含字段清单）</li>
 *   <li>顺序一旦反过来，将来有人优化"先并发生成 DSL 再判权限"，
 *       权限就从闸门降级成了事后检查</li>
 * </ol>
 *
 * <h2>拒绝走异常，不走返回值</h2>
 *
 * <p>调用方拿到一个"被拒绝"的返回值后很容易忘掉判空、当成正常继续走。
 * 抛 {@link AskDeniedException} 让这条路径**必须**被处理，
 * 且它带上机器可读的 {@code Reason} 供审计分类——
 * 这正是厂商「返回 HTTP 200 + blocked:true」那个坑的反面做法。
 */
public class AskEngine {

    private static final Logger log = LoggerFactory.getLogger(AskEngine.class);

    private final SemanticModel model;
    private final SqlDialect dialect;
    private final DslGenerator dslGenerator;

    public AskEngine(SemanticModel model, SqlDialect dialect, DslGenerator dslGenerator) {
        this.model = model;
        this.dialect = dialect;
        this.dslGenerator = dslGenerator;
    }

    /**
     * 问数：从自然语言到可执行的 SQL。
     *
     * @param question 用户问题
     * @param ctx      判定主体（角色、动态变量）
     * @param userId   用户标识，用于模型会话隔离
     * @param sessionId 会话标识，多轮追问靠它保持上下文
     * @return 渲染结果：SQL + 参数 + 输出列
     * @throws AskDeniedException 权限判定拒绝
     */
    public RenderedSql ask(String question, PermissionContext ctx, String userId, String sessionId) {
        // ① 权限判定 —— 必须在任何模型调用之前
        PermissionDecision decision = PermissionGuard.check(null, model.domain().permission(), ctx);
        if (decision instanceof PermissionDecision.Denied denied) {
            log.warn("问数被拒：user={} roles={} reason={}",
                    ctx.userId(), ctx.roles(), denied.reason());
            throw new AskDeniedException(denied.reason(), denied.message());
        }

        // ② 生成 DSL
        String sysPrompt = PromptAssembler.assemble(model);
        AskDsl dsl = dslGenerator.generate(question, sysPrompt, userId, sessionId);

        if (dsl.isEmpty()) {
            // 模型什么都没解析出来。这**不是**"查询无数据"，而是"没听懂"——
            // 两者必须分开，否则用户会以为库里真的没数据
            throw new AskDeniedException(
                    PermissionDecision.Reason.NO_ROW_SCOPE,
                    "没能理解这个问题，请换一种说法，或补充要查的指标与范围");
        }

        // ③ 渲染 SQL。行规则谓词与脱敏列在这一步真正落进 SQL
        return AskSqlRenderer.render(dsl, model, dialect, decision, LocalDate.now());
    }

    /** 供上层装配提示词用（如展示"这个域能问什么"）。 */
    public List<String> availableFields() {
        return model.availableFieldNames();
    }
}

package com.liusongboyy.chatdata.engine.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liusongboyy.chatdata.engine.audit.AskAuditOutcome;
import com.liusongboyy.chatdata.engine.audit.AskAuditRecord;
import com.liusongboyy.chatdata.engine.audit.AskAuditRecorder;
import com.liusongboyy.chatdata.engine.nlsql.AskDsl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 写审计。两种模式（建模 / 直连）共用这一处。
 *
 * <h2>为什么单独抽出来</h2>
 *
 * <p>建模模式与直连模式的**执行方式完全不同**（前者我们渲染 SQL，后者模型自己写），
 * 但审计必须长得一样——它是"某用户为什么看到了这些数据"的唯一凭据。
 * 各写一份的话，分叉一定会出现在审计上：某天你会发现直连模式的记录少了角色快照、
 * 或把 SQL 记成了 null，而那时已经积了几万条。
 *
 * <p>差异只有一个：SQL 从哪来。故这个方法的参数是 {@code sqlText}（原文），
 * 而不是渲染产物。
 */
public final class AskAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AskAuditWriter.class);
    private static final ObjectMapper M = new ObjectMapper();

    private AskAuditWriter() {
    }

    /**
     * 写一条审计。**自己吞异常**——审计写失败不该让问数失败。
     *
     * @param dsl     建模模式的意图；直连模式传 null
     * @param sqlText 实际执行的 SQL 原文。失败时可能为 null
     */
    public static void write(AskAuditRecorder recorder,
                             AskRequest request,
                             String domainId,
                             AskDsl dsl,
                             String sqlText,
                             Integer rowCount,
                             long duration,
                             AskAuditOutcome outcome,
                             String errorMessage) {
        if (recorder == null) {
            return;
        }
        try {
            recorder.record(new AskAuditRecord(
                    request.username(),
                    request.roles().isEmpty() ? null : String.join(",", request.roles()),
                    request.question(),
                    request.assistantId(),
                    domainId,
                    // DSL 序列化失败不能连累审计本身——那会把"问数失败"变成"审计也失败"
                    dsl == null ? null : toJson(dsl),
                    sqlText,
                    rowCount,
                    duration,
                    outcome,
                    errorMessage,
                    java.time.LocalDateTime.now()));
        } catch (Exception e) {
            log.error("组装审计记录失败：user={}", request.username(), e);
        }
    }

    /**
     * 写一次分析的审计：**1 条主记录 + N 条步骤记录**。
     *
     * <p>主记录回答"谁、问了什么、拆成了什么计划、结论是什么"；
     * 步骤记录回答"每一步实际执行了什么 SQL、返回多少行、花多久"。
     * 只写主记录的话，"这条结论是怎么来的"就还原不出来——而这正是编排最需要解释的部分。
     *
     * <p>批次号用随机串而不是父记录自增 id：后者要求插入后拿回主键，
     * 而不可变 record + MyBatis 的组合做这件事要额外绕一圈，收益为零。
     */
    public static void writeAnalysis(AskAuditRecorder recorder,
                                     AskRequest request,
                                     String domainId,
                                     com.liusongboyy.chatdata.engine.analysis.AnalysisPlan plan,
                                     com.liusongboyy.chatdata.engine.analysis.AnalysisResult result,
                                     Integer rowCount,
                                     long duration,
                                     AskAuditOutcome outcome,
                                     String errorMessage) {
        if (recorder == null) {
            return;
        }
        String batchId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String roles = request.roles().isEmpty() ? null : String.join(",", request.roles());

        try {
            recorder.record(new AskAuditRecord(
                    request.username(), roles, request.question(), request.assistantId(),
                    domainId, null, null, rowCount, duration, outcome, errorMessage, now,
                    batchId, null,
                    plan == null ? null : toJson(plan),
                    result == null ? null : result.narrative()));

            if (result != null) {
                int stepNo = 1;
                for (com.liusongboyy.chatdata.engine.analysis.StepResult step : result.steps()) {
                    recorder.record(new AskAuditRecord(
                            request.username(), roles, request.question(), request.assistantId(),
                            domainId,
                            step.dsl() == null ? null : toJson(step.dsl()),
                            step.sql() == null ? null : step.sql().sql(),
                            step.isOk() ? step.rows().rowCount() : null,
                            step.durationMs(),
                            step.isOk() ? AskAuditOutcome.SUCCESS : AskAuditOutcome.FAILURE,
                            step.error(), now, batchId, stepNo++, null, null));
                }
            }
        } catch (Exception e) {
            // 与单查询一致：审计写不进去不该让分析失败，但必须显式记 error
            log.error("分析审计写入失败：user={} batch={}", request.username(), batchId, e);
        }
    }

    private static String toJson(Object value) {
        try {
            return M.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("审计序列化失败，记为 null：{}", e.getMessage());
            return null;
        }
    }

    private static String toJson(AskDsl dsl) {
        try {
            return M.writeValueAsString(dsl);
        } catch (Exception e) {
            log.warn("DSL 序列化失败，审计里记为 null：{}", e.getMessage());
            return null;
        }
    }
}

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

    private static String toJson(AskDsl dsl) {
        try {
            return M.writeValueAsString(dsl);
        } catch (Exception e) {
            log.warn("DSL 序列化失败，审计里记为 null：{}", e.getMessage());
            return null;
        }
    }
}

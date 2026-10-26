package com.liusongboyy.chatdata.engine.audit;

import java.time.LocalDateTime;

/**
 * 一条待落库的审计记录。
 *
 * <p>字段与 {@code ask_audit_log} 对应，但**这个 record 不依赖任何持久化技术**——
 * 它只是"要记什么"的描述。这样引擎的核心逻辑不必知道审计存在哪，
 * 也便于测试用假实现替换。
 *
 * <p><strong>roles 是当次请求的角色快照，不是引用。</strong>
 * 权限事后可能变更，审计要能回答「他当时凭什么看到了这些数据」。
 *
 * @param username     提问者
 * @param roles        当次请求的角色快照，逗号分隔
 * @param question     提问原文
 * @param assistantId  助手 id，可为空
 * @param domainId     数据域 id，可为空（权限判定前就失败的请求拿不到域）
 * @param dslJson      语义意图。**可为空**——DSL 生成失败时没有它
 * @param sqlText      实际下发的 SQL（已含权限改写）。可为空
 * @param rowCount     返回行数；失败或被拒时为空
 * @param durationMs   端到端耗时
 * @param outcome      结局
 * @param errorMessage 失败摘要，成功时为空
 * @param createdAt    记录时间
 * @param batchId      一次分析的批次标识。主记录与它的每个步骤共享同一个值；
 *                     单查询为 null。用批次而不是父记录 id，是为了不依赖
 *                     "插入后拿到自增主键"——那在不可变 record + MyBatis 里要额外绕一圈，
 *                     而批次号本身已经能唯一还原一次分析
 * @param stepNo       步骤序号（从 1 开始）；主记录为 null。
 *                     **提问列表查 {@code step_no IS NULL}**，天然向后兼容
 * @param planJson     分析计划原文；仅主记录有
 * @param narrative    合成结论；仅主记录有
 */
public record AskAuditRecord(
        String username,
        String roles,
        String question,
        String assistantId,
        String domainId,
        String dslJson,
        String sqlText,
        Integer rowCount,
        long durationMs,
        AskAuditOutcome outcome,
        String errorMessage,
        LocalDateTime createdAt,
        String batchId,
        Integer stepNo,
        String planJson,
        String narrative
) {

    /** 兼容构造器：单查询与直连模式不涉及编排。 */
    public AskAuditRecord(String username, String roles, String question, String assistantId,
                          String domainId, String dslJson, String sqlText, Integer rowCount,
                          long durationMs, AskAuditOutcome outcome, String errorMessage,
                          LocalDateTime createdAt) {
        this(username, roles, question, assistantId, domainId, dslJson, sqlText, rowCount,
                durationMs, outcome, errorMessage, createdAt, null, null, null, null);
    }

    /** 数据库里 error_message 是 VARCHAR(512)，超出会被截断或报错——在这里先截好。 */
    private static final int MAX_ERROR_LENGTH = 500;

    public AskAuditRecord {
        if (username == null || username.isBlank()) {
            // 没有身份就无从审计。调用方不该走到这里，但真走到了要立刻发现
            throw new IllegalArgumentException("审计记录必须有 username");
        }
        outcome = outcome == null ? AskAuditOutcome.SUCCESS : outcome;
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
        errorMessage = truncate(errorMessage);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_ERROR_LENGTH ? s : s.substring(0, MAX_ERROR_LENGTH) + "…";
    }
}

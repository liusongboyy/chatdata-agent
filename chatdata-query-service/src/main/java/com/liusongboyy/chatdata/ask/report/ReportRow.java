package com.liusongboyy.chatdata.ask.report;

import java.time.LocalDateTime;

/**
 * 报告表的一行。
 *
 * <p>{@code bodyJson} 保持字符串：正文是引擎层的 {@code ReportBody} 序列化结果，
 * 这里不反序列化。与 {@code MessageRow.resultJson} 同一个理由——
 * 两处各维护一份 DTO 迟早漂移。
 */
public record ReportRow(
        String id,
        String conversationId,
        String username,
        String title,
        String intent,
        String bodyJson,
        String sourceMessageIds,
        String modelKey,
        LocalDateTime generatedAt
) {
}

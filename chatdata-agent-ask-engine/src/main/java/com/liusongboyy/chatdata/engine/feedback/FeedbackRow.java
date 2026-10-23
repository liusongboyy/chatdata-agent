package com.liusongboyy.chatdata.engine.feedback;

import java.time.LocalDateTime;

/**
 * {@code ask_feedback} 的一行。
 *
 * <p>类型与状态用字符串而不是枚举：这一行要直接序列化给管理台，
 * 枚举反序列化失败的代价（整页 500）不值得省那一层转换。
 *
 * @param exampleId 审核通过后写入/复用的范例 id；未通过或无需范例时为空
 */
public record FeedbackRow(
        long id,
        long domainId,
        String assistantId,
        String username,
        String question,
        String feedbackType,
        String generatedSql,
        String correctedSql,
        String note,
        String status,
        Long exampleId,
        String reviewedBy,
        LocalDateTime reviewedAt,
        LocalDateTime createdAt
) {
}

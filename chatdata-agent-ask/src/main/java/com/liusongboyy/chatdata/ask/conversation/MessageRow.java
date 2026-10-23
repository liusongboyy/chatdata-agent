package com.liusongboyy.chatdata.ask.conversation;

import java.time.LocalDateTime;

/**
 * 会话中的一条消息。
 *
 * <p>{@code resultJson} 是问数结果事件的原始 JSON。它不在这里反序列化：
 * 结果形状由 {@code AskEngineStreamController#resultPayload} 定义，
 * 后端只需要原样存、原样还，避免两处各维护一份 DTO。
 */
public record MessageRow(
        long id,
        String conversationId,
        int seq,
        String role,
        String kind,
        String content,
        String resultJson,
        String status,
        String failureCode,
        String failureMessage,
        LocalDateTime createdAt
) {
}

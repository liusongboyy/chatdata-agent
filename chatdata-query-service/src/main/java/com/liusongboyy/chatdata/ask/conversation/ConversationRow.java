package com.liusongboyy.chatdata.ask.conversation;

import java.time.LocalDateTime;

/**
 * 会话列表/详情里的会话主体。
 *
 * <p>字段与 {@code ask_conversation} 一一对应，直接序列化给前端。
 *
 * @param id        会话 id，即 agentscope 的 sessionId
 * @param preview   最后一条消息的摘要，列表用
 * @param messageCount 消息条数，前端可据此判断是否为空
 */
public record ConversationRow(
        String id,
        String username,
        String assistantId,
        String title,
        String preview,
        int messageCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}

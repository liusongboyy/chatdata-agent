package com.liusongboyy.chatdata.engine.chat;

import java.util.Map;
import java.util.Set;

/**
 * 一次问数请求。
 *
 * @param question    提问原文
 * @param userId      用户标识（与 {@link #username} 可能是同一个值，但语义不同：
 *                    前者是内部标识，后者是审计与展示用的身份）
 * @param username    身份，用于审计
 * @param roles       当次请求的角色快照，用于权限判定与审计留痕
 * @param sessionId   会话标识，多轮追问靠它保持上下文
 * @param assistantId 助手 id，可为空（直连 API 时）
 * @param attributes  主体的业务属性（tenant/orgCode/orgPath/displayName…）。
 *                    动态行规则（{@code sysVar: "orgCode"}）从这里取值——
 *                    它由资源服务器内省令牌后填入，**不由客户端传入**。
 */
public record AskRequest(
        String question,
        String userId,
        String username,
        Set<String> roles,
        String sessionId,
        String assistantId,
        Map<String, Object> attributes
) {

    /** 兼容构造器：不带业务属性。既有调用点与测试不必改。 */
    public AskRequest(String question, String userId, String username, Set<String> roles,
                      String sessionId, String assistantId) {
        this(question, userId, username, roles, sessionId, assistantId, Map.of());
    }

    /** 与厂商集成那条链路一致的长度上限。 */
    public static final int MAX_QUESTION_LENGTH = 2000;

    public AskRequest {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        question = question.trim();
        if (question.length() > MAX_QUESTION_LENGTH) {
            // 截断而不是拒绝：超长输入多半是误粘贴，截断后仍能给出有用回答。
            // 但也可能是攻击，故记在审计的 question 里的是截断后的内容
            question = question.substring(0, MAX_QUESTION_LENGTH);
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username 不能为空——审计需要它");
        }
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        userId = (userId == null || userId.isBlank()) ? username : userId;
        sessionId = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
        // 空串与 null 同义：前端"不使用助理"传的是空串，而审计列里存空串是噪音，
        // 也让"哪些记录没走助理"这种查询写起来别扭
        assistantId = (assistantId == null || assistantId.isBlank()) ? null : assistantId;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}

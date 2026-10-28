package com.liusongboyy.chatdata.ask.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liusongboyy.chatdata.ask.conversation.mapper.ConversationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话历史的读写。
 *
 * <h2>会话 id 就是 sessionId</h2>
 *
 * <p>前端每次开新会话已经会生成一个 UUID，后端直接拿它当
 * {@code ask_conversation.id}。这样 agentscope 的状态（按
 * {@code (username, sessionId)} 存）与产品视图天然对齐，不需要一张映射表。
 *
 * <h2>归属只认令牌</h2>
 *
 * <p>所有入口的 {@code username} 都来自 {@code CurrentUser}，客户端传什么都不看。
 * 查详情/删除时用 {@code (id, username)} 一起命中，别人的 id 只会得到 404——
 * 不泄露"这条会话存在"。
 *
 * <h2>写消息是尽力而为</h2>
 *
 * <p>历史记录失败不能让用户的问题白问：{@code append*} 内部吞掉异常并记日志，
 * 只有 {@link #ensure} 的越权/冲突会阻断请求。
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final ObjectMapper M = new ObjectMapper();

    private static final int TITLE_MAX = 60;
    private static final int PREVIEW_MAX = 120;
    private static final int KEYWORD_MAX = 100;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;
    /** page 本身也要有上限，否则 (page-1)*size 会溢出 int。 */
    private static final int MAX_PAGE = 10_000;

    private final ConversationMapper mapper;
    private final TransactionTemplate tx;

    public ConversationService(ConversationMapper mapper, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 确保会话存在且属于当前用户，返回最终使用的会话 id / 助理 id。 */
    public ConversationRef ensure(String sessionId, String username, String assistantId, String question) {
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        String id = sessionId == null || sessionId.isBlank()
                ? UUID.randomUUID().toString().replace("-", "")
                : sessionId.trim();
        if (id.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话 id 过长");
        }

        Map<String, Object> owner = mapper.selectOwner(id);
        if (owner == null) {
            Map<String, Object> params = new HashMap<>();
            params.put("id", id);
            params.put("username", username);
            params.put("assistantId", assistantId == null || assistantId.isBlank() ? null : assistantId.trim());
            params.put("title", titleOf(question));
            try {
                mapper.insertConversation(params);
                log.info("创建会话：id={} user={} assistant={}", id, username, params.get("assistantId"));
                return new ConversationRef(id, (String) params.get("assistantId"));
            } catch (DuplicateKeyException e) {
                // 同一 id 的两个并发请求，另一个先建好了；重新读一次再按归属校验
                owner = mapper.selectOwner(id);
                if (owner == null) {
                    throw e;
                }
            }
        }

        if (!username.equals(String.valueOf(owner.get("username")))) {
            // 不区分"不存在"与"不是你的"：否则这个端点会变成会话 id 探测器
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (owner.get("deleted") instanceof Number n && n.intValue() != 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        ConversationRow row = mapper.selectById(id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String requested = assistantId == null || assistantId.isBlank() ? null : assistantId.trim();
        if (row.assistantId() != null && requested != null && !row.assistantId().equals(requested)) {
            // 同一会话内换助理 = 换语义域，旧上下文里的字段在新域可能是另一个口径。
            // 前端换助理会开新会话；这里兜底拒绝，避免生成"语法正确、语义错误"的查询
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该会话属于另一个助理，请新建会话");
        }
        return new ConversationRef(id, row.assistantId() != null ? row.assistantId() : requested);
    }

    /** 写入用户消息。 */
    public void appendUser(String conversationId, String content, String kind) {
        append(conversationId, "USER", kind, content, null, "SUCCESS", null, null);
    }

    /** 写入成功的助理结果。 */
    public void appendResult(String conversationId, Object resultPayload) {
        try {
            String json = M.writeValueAsString(resultPayload);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = resultPayload instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            append(conversationId, "ASSISTANT", "question", summaryOf(payload), json,
                    "SUCCESS", null, null);
        } catch (Exception e) {
            log.warn("序列化问数结果失败，历史记录将缺少这一条：conv={} 原因={}",
                    conversationId, e.getMessage());
        }
    }

    /** 写入失败的助理消息。 */
    public void appendFailure(String conversationId, String code, String message) {
        append(conversationId, "ASSISTANT", "question", message, null,
                "FAILED", code, truncate(message, 512));
    }

    /** 分页列表。 */
    public ConversationPage list(String username, int page, int size,
                                 String keyword, String assistantId) {
        int p = Math.min(Math.max(page, 1), MAX_PAGE);
        int s = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        String pattern = likePattern(keyword);
        String assistant = assistantId == null || assistantId.isBlank() ? null : assistantId.trim();

        long total = mapper.count(username, assistant, pattern);
        int offset = Math.min((p - 1) * s, Integer.MAX_VALUE - s);
        List<ConversationRow> items = total == 0
                ? List.of()
                : mapper.selectPage(username, assistant, pattern, s, offset);
        return new ConversationPage(items, total, p, s);
    }

    /** 会话详情，含全部消息（按 seq 升序）。 */
    public ConversationDetail detail(String id, String username) {
        requireOwned(id, username);
        ConversationRow row = mapper.selectById(id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        List<MessageRow> rows = mapper.selectMessages(id);
        List<ConversationMessage> messages = new ArrayList<>(rows.size());
        for (MessageRow r : rows) {
            messages.add(new ConversationMessage(
                    r.id(), r.seq(), r.role(), r.kind(), r.content(),
                    parseResult(r.resultJson()),
                    r.status(), r.failureCode(), r.failureMessage(), r.createdAt()));
        }
        return new ConversationDetail(row.id(), row.assistantId(), row.title(), row.preview(),
                row.messageCount(), row.createdAt(), row.updatedAt(), messages);
    }

    /** 软删除。返回后列表不再可见；agentscope 状态由调用方另行尽力清理。 */
    public void delete(String id, String username) {
        if (mapper.softDelete(id, username) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private void requireOwned(String id, String username) {
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        Map<String, Object> owner = mapper.selectOwner(id);
        if (owner == null
                || !username.equals(String.valueOf(owner.get("username")))
                || (owner.get("deleted") instanceof Number n && n.intValue() != 0)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    /**
     * seq 在事务内分配，且先锁会话行。
     *
     * <p>不锁的话，同一会话的并发重算会读到同一个 {@code message_count}，
     * 两条消息争同一个 {@code (conversation_id, seq)}，后写的直接主键冲突。
     *
     * <p>用 {@link TransactionTemplate} 而不是 {@code @Transactional}：
     * 这些方法都是本类内部调用，注解走不到代理，事务不会生效——那种错误
     * 在单线程测试里完全看不出来，只会在并发时偶发主键冲突。
     */
    private void append(String conversationId, String role, String kind, String content,
                        String resultJson, String status, String failureCode, String failureMessage) {
        try {
            tx.executeWithoutResult(s -> {
                Integer count = mapper.lockMessageCount(conversationId);
                if (count == null) {
                    log.warn("会话不存在或已删除，跳过历史写入：conv={}", conversationId);
                    return;
                }
                int seq = count + 1;

                Map<String, Object> params = new HashMap<>();
                params.put("conversationId", conversationId);
                params.put("seq", seq);
                params.put("role", role);
                params.put("kind", kind);
                params.put("content", content);
                params.put("resultJson", resultJson);
                params.put("status", status);
                params.put("failureCode", failureCode);
                params.put("failureMessage", failureMessage);
                mapper.insertMessage(params);

                mapper.touch(conversationId, seq, truncate(content, PREVIEW_MAX));
            });
        } catch (Exception e) {
            // 历史记录不是主流程：写失败也要让这次问数正常返回
            log.warn("写入会话消息失败（不影响本次问数）：conv={} role={} 原因={}",
                    conversationId, role, e.getMessage());
        }
    }

    private static String titleOf(String question) {
        String q = question == null ? "" : question.replaceAll("\\s+", " ").trim();
        return q.isEmpty() ? "新会话" : truncate(q, TITLE_MAX);
    }

    private static String summaryOf(Map<String, Object> payload) {
        Object answer = payload.get("answer");
        if (answer instanceof String s && !s.isBlank()) {
            return truncate(s, PREVIEW_MAX);
        }
        Object rows = payload.get("rowCount");
        if (rows instanceof Number n) {
            return "查询返回 " + n + " 行";
        }
        return "查询完成";
    }

    private static Object parseResult(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return M.readValue(json, Object.class);
        } catch (Exception e) {
            log.warn("历史结果 JSON 无法解析：{}", e.getMessage());
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** LIKE 通配符转义；{@code !} 是 ESCAPE 字符，先转它自己。 */
    static String likePattern(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String k = keyword.trim();
        if (k.length() > KEYWORD_MAX) {
            k = k.substring(0, KEYWORD_MAX);
        }
        return "%" + k.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    /** {@link #ensure} 的结果：最终会话 id 与会话绑定的助理。 */
    public record ConversationRef(String id, String assistantId) {
    }

    /** 分页结果。 */
    public record ConversationPage(List<ConversationRow> items, long total, int page, int size) {
    }

    /** 详情（扁平化，前端直接当会话对象用）。 */
    public record ConversationDetail(
            String id,
            String assistantId,
            String title,
            String preview,
            int messageCount,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime updatedAt,
            List<ConversationMessage> messages
    ) {
    }

    /** 回放用的消息；{@code result} 已解析成对象。 */
    public record ConversationMessage(
            long id,
            int seq,
            String role,
            String kind,
            String content,
            Object result,
            String status,
            String failureCode,
            String failureMessage,
            java.time.LocalDateTime createdAt
    ) {
    }
}

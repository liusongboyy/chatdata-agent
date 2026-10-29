package com.liusongboyy.chatdata.channel.session;

import com.liusongboyy.chatdata.channel.config.ChannelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 渠道会话映射：{@code chat_key} → ask 的 {@code conversationId}。
 *
 * <h2>为什么需要这张表</h2>
 *
 * <p>IM 是一问一答，用户会说「那上个月呢」。ask 的多轮靠
 * {@code conversationId} 关联上下文，而飞书给的是 {@code chat_id}/{@code open_id}
 * ——两者之间需要一个稳定的映射。
 *
 * <h2>{@code chat_key} 已经包含了"谁"</h2>
 *
 * <p>私聊是 {@code open_id}，群聊是 {@code chat_id:open_id}。
 * 群聊里换人必须开新会话：张三问完李四接着问，不能嫁接——
 * 那条上下文里有张三权限下的数据，李四的追问会基于它继续查询。
 */
@Component
public class ChannelSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChannelSessionService.class);

    private final JdbcTemplate jdbc;
    private final ChannelProperties properties;

    public ChannelSessionService(JdbcTemplate jdbc, ChannelProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * 取当前会话；过期或不存在则新建。
     *
     * @param channel 渠道标识
     * @param chatKey 会话键（已含发言人）
     * @param username 归属人。用于审计与"换绑后旧会话不该复用"的判断
     * @return ask 的 conversationId
     */
    public String resolve(String channel, String chatKey, String username) {
        Instant cutoff = Instant.now().minus(properties.getSession().getIdle());

        List<String> found = jdbc.query("""
                SELECT conversation_id FROM channel_session
                WHERE channel = ? AND chat_key = ? AND username = ? AND last_active_at >= ?
                """,
                (rs, i) -> rs.getString(1), channel, chatKey, username, Timestamp.from(cutoff));

        if (!found.isEmpty()) {
            jdbc.update("""
                    UPDATE channel_session SET last_active_at = ?
                    WHERE channel = ? AND chat_key = ?
                    """, Timestamp.from(Instant.now()), channel, chatKey);
            return found.get(0);
        }

        // 新建。id 用 UUID 而不是自增：它同时是 ask 的 sessionId，
        // 而 ask 侧把它当字符串键使用，UUID 便于跨库排查时一眼认出
        String conversationId = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO channel_session (channel, chat_key, username, conversation_id, last_active_at)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE conversation_id = VALUES(conversation_id),
                                        username = VALUES(username),
                                        last_active_at = VALUES(last_active_at)
                """, channel, chatKey, username, conversationId, Timestamp.from(Instant.now()));
        log.info("新建渠道会话：channel={} chatKey={} user={} conv={}",
                channel, chatKey, username, conversationId);
        return conversationId;
    }

    /**
     * 当前会话选中的助理。
     *
     * @return 助理 id；没选过则为空——调用方据此决定是自动选还是提示用户选
     */
    public Optional<String> selectedAssistant(String channel, String chatKey, String username) {
        List<String> found = jdbc.query("""
                SELECT assistant_id FROM channel_session
                WHERE channel = ? AND chat_key = ? AND username = ?
                """, (rs, i) -> rs.getString(1), channel, chatKey, username);
        return found.isEmpty() ? Optional.empty() : Optional.ofNullable(found.get(0));
    }

    /**
     * 记下会话选中的助理。
     *
     * <p>换助理时**要换会话**：ask 的多轮上下文绑在 conversationId 上，
     * 而两个助理的域不同，接上文的追问会落到错误的域里。
     */
    public void selectAssistant(String channel, String chatKey, String username,
                                String assistantId, String newConversationId) {
        jdbc.update("""
                UPDATE channel_session
                SET assistant_id = ?, conversation_id = ?, last_active_at = ?
                WHERE channel = ? AND chat_key = ?
                """, assistantId, newConversationId, Timestamp.from(Instant.now()),
                channel, chatKey);
    }

    /**
     * 显式开新会话（用户说「新话题」「重置」时）。
     *
     * @return 新的 conversationId
     */
    public String startNew(String channel, String chatKey, String username) {
        jdbc.update("DELETE FROM channel_session WHERE channel = ? AND chat_key = ?",
                channel, chatKey);
        return resolve(channel, chatKey, username);
    }
}

package com.liusongboyy.chatdata.channel.inbound;

import com.liusongboyy.chatdata.channel.config.ChannelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 入站去重。
 *
 * <h2>为什么键是 {@code message_id} 而不是 {@code event_id}</h2>
 *
 * <p>飞书两份文档给的答案不一致：「事件概述」说 2.0 事件用 {@code event_id}，
 * 而「接收消息 v2.0」明确写「请使用 message_id 去重，不要依赖 event_id」。
 * 取严格的那个：
 *
 * <p>{@code event_id} 标识<b>一次推送</b>，业务处理失败导致重推时会变；
 * {@code message_id} 标识<b>一条消息</b>，重推不变。我们要防的是
 * 「同一个问题被答两遍」——那是消息级的语义。
 */
@Component
public class InboundDedup {

    private static final Logger log = LoggerFactory.getLogger(InboundDedup.class);

    private final JdbcTemplate jdbc;
    private final ChannelProperties properties;

    public InboundDedup(JdbcTemplate jdbc, ChannelProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * 抢占一条消息的处理权。
     *
     * <p>用 {@code INSERT ... } 的主键冲突而非「先查再插」：两个线程同时收到重推时，
     * 先查再插会双双查不到然后都去处理——而去重本来就是为了挡住这种情况。
     *
     * @return {@code true} 表示这是我第一次见到它（可以处理）；
     *         {@code false} 表示重推（应当直接返回 200 并跳过）
     */
    public boolean claim(String channel, String messageId, String chatKey) {
        try {
            jdbc.update("""
                    INSERT INTO channel_inbound_dedup (channel, message_id, chat_key, received_at)
                    VALUES (?, ?, ?, ?)
                    """, channel, messageId, chatKey, Timestamp.from(Instant.now()));
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.debug("重复推送，跳过：channel={} messageId={}", channel, messageId);
            return false;
        }
    }

    /**
     * 释放处理权。
     *
     * <p><b>只在「处理根本没开始」时才调用。</b>典型场景：队列已满，
     * 消息没能进入工作线程。此时若不去掉去重记录，用户重发同一条消息
     * （或飞书重推）会被当成重复而永远得不到回答。
     *
     * <p>反过来，处理已经失败（ask 报错）时<b>不</b>释放：
     * 那样飞书 15 秒后的重推会再试一次，用户可能收到两次错误提示，
     * 也可能第二次碰巧成功——不确定比确定更糟。
     */
    public void release(String channel, String messageId) {
        jdbc.update("DELETE FROM channel_inbound_dedup WHERE channel = ? AND message_id = ?",
                channel, messageId);
    }

    /**
     * 清理过期记录。
     *
     * @return 删除行数
     */
    public int purgeExpired() {
        Timestamp cutoff = Timestamp.from(
                Instant.now().minus(properties.getDedup().getRetention()));
        int removed = jdbc.update(
                "DELETE FROM channel_inbound_dedup WHERE received_at < ?", cutoff);
        if (removed > 0) {
            log.info("清理渠道去重记录：{} 条（阈值 {}）", removed, cutoff);
        }
        return removed;
    }
}

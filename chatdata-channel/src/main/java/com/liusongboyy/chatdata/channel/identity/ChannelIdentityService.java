package com.liusongboyy.chatdata.channel.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 渠道身份 → IAM 用户名。
 *
 * <h2>查的是 IAM 的同一张表</h2>
 *
 * <p>{@code iam_user.external_provider/external_id} 是唯一的事实来源。
 * 这里直接读那张表，<b>不经过 IAM 的 HTTP 接口</b>——那会多一次往返，
 * 而且 IAM 没有「按 open_id 查用户」的端点（也不该有：
 * 那等于把"任意 open_id 是否已注册"变成一个可探测的接口）。
 *
 * <p>代价是 channel 需要直连 IAM 的库。这在当前部署形态下是可接受的
 * （同一套 MySQL），但记录在此：将来 IAM 拆库时，这里要改成调接口。
 */
@Component
public class ChannelIdentityService {

    private static final Logger log = LoggerFactory.getLogger(ChannelIdentityService.class);

    private final JdbcTemplate jdbc;

    public ChannelIdentityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 解析发送者对应的 IAM 用户名。
     *
     * @return 未绑定或账号停用时为空——调用方据此给绑定引导，
     *         而<b>不发</b>问数请求
     */
    public Optional<String> resolveUsername(String channel, String externalId) {
        if (externalId == null || externalId.isBlank()) {
            return Optional.empty();
        }
        List<String> found = jdbc.query("""
                SELECT username FROM iam_user
                WHERE external_provider = ? AND external_id = ?
                  AND status = 'ACTIVE'
                LIMIT 1
                """, (rs, i) -> rs.getString(1), channel, externalId);

        if (found.isEmpty()) {
            log.debug("渠道身份未绑定或已停用：channel={} externalId={}", channel, externalId);
            return Optional.empty();
        }
        return Optional.of(found.get(0));
    }
}

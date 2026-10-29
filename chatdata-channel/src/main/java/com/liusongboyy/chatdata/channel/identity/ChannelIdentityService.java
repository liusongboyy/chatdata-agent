package com.liusongboyy.chatdata.channel.identity;

import com.liusongboyy.chatdata.channel.config.ChannelProperties;
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
    private final String iamDatabase;

    public ChannelIdentityService(JdbcTemplate jdbc, ChannelProperties properties) {
        this.jdbc = jdbc;
        // 库名要拼进 SQL，不能走占位符（表名/库名不是参数）。
        // 它来自配置而不是请求，且这里做一次白名单校验——把配置注入变成
        // SQL 注入是最不该出现的失手
        String db = properties.getIam().getDatabase();
        if (db == null || !db.matches("[A-Za-z0-9_]+")) {
            throw new IllegalStateException("channel.iam.database 不是合法的库名：" + db);
        }
        this.iamDatabase = db;
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
        // 跨库查询：主数据源连的是 ask 的库（去重表、会话映射在那），
        // 而身份绑定在 IAM 的库。两个库在同一个 MySQL 实例上，加库名前缀即可。
        // 用字符串拼接而不是文本块：库名是运行时配置，文本块里没法插变量
        String sql = "SELECT username FROM " + iamDatabase + ".iam_user"
                + " WHERE external_provider = ? AND external_id = ?"
                + " AND status = 'ACTIVE' LIMIT 1";
        List<String> found = jdbc.query(sql, (rs, i) -> rs.getString(1), channel, externalId);

        if (found.isEmpty()) {
            log.debug("渠道身份未绑定或已停用：channel={} externalId={}", channel, externalId);
            return Optional.empty();
        }
        return Optional.of(found.get(0));
    }
}

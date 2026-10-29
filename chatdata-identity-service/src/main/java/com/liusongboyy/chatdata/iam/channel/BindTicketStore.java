package com.liusongboyy.chatdata.iam.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

/**
 * 绑定票据的读取与消费。
 *
 * <h2>票据里没有用户身份——这是刻意的</h2>
 *
 * <p>它只证明「某个渠道账号请求绑定」。绑定到<b>谁</b>由 Web 端当前登录用户决定。
 * 如果把 username 放进票据，一条转发到群里的链接就成了接管他人账号的入口。
 *
 * <h2>一次性</h2>
 *
 * <p>用 {@code getAndDelete} 而不是「先读再删」：两个标签页同时点确认时，
 * 先读再删会让两边都读到，然后都去绑定——第二次会把第一次的覆盖掉。
 *
 * <h2>与 channel 侧的约定</h2>
 *
 * <p>key 前缀与 JSON 结构必须与 {@code chatdata-channel} 的 {@code BindTicketIssuer}
 * 一致。两边共享同一个 Redis。改一边就要改另一边——这是两个进程间唯一
 * 没有编译器保护的接口，所以两边都写了这句注释。
 */
@Component
public class BindTicketStore {

    private static final Logger log = LoggerFactory.getLogger(BindTicketStore.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 与 channel 侧 BindTicketIssuer.KEY_PREFIX 必须一致。 */
    private static final String KEY_PREFIX = "channel:bind:";

    private final StringRedisTemplate redis;

    public BindTicketStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 查看票据内容，<b>不消费</b>。
     *
     * <p>用户点开链接后先要看到「飞书账号「张三」将绑定到当前账号 zhangsan」，
     * 那时还没决定要不要绑。这里只读，确认时才 {@link #consume}。
     */
    public Optional<Ticket> peek(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        String payload = redis.opsForValue().get(KEY_PREFIX + ticket);
        return parse(payload);
    }

    /**
     * 消费票据（用掉即删）。
     *
     * @return 票据内容；不存在或已用过则空
     */
    public Optional<Ticket> consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        String payload = redis.opsForValue().getAndDelete(KEY_PREFIX + ticket);
        return parse(payload);
    }

    private Optional<Ticket> parse(String payload) {
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = JSON.readTree(payload);
            String channel = node.path("channel").asString("");
            String externalId = node.path("externalId").asString("");
            if (channel.isBlank() || externalId.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Ticket(channel, externalId,
                    node.path("displayName").asString("")));
        } catch (RuntimeException e) {
            // 票据内容坏了等同于票据无效。不要抛给用户看——他只会看到一个 500
            log.warn("绑定票据解析失败：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 票据内容。
     *
     * @param displayName 渠道侧的显示名，用于让用户确认「绑的是不是我这个号」。
     *                    它<b>不能</b>当作身份凭据——用户可自改昵称
     */
    public record Ticket(String channel, String externalId, String displayName) {
    }
}

package com.liusongboyy.chatdata.channel.identity;

import com.liusongboyy.chatdata.channel.config.ChannelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * 绑定 ticket 的签发。
 *
 * <h2>ticket 里<b>不含</b>用户身份</h2>
 *
 * <p>它只证明「某个渠道账号请求绑定」，绑定到<b>谁</b>由 Web 端当前登录用户决定。
 *
 * <p>如果把 username 放进 ticket，就等于「谁拿到链接就能替别人绑定」——
 * 一条转发到群里的链接会变成接管他人账号的入口。
 *
 * <h2>谁写、谁读</h2>
 *
 * <p>channel 写（用户发消息时未绑定 → 签发），IAM 读（Web 端确认绑定时消费）。
 * 两边共享同一个 Redis 实例——这在当前部署形态下成立，
 * 也是把它们放在同一个 {@code channel:bind:} 命名空间下的原因。
 *
 * <p>IAM 侧的读取见 {@code BindTicketStore}，两边的 key 格式必须一致。
 */
@Component
public class BindTicketIssuer {

    private static final Logger log = LoggerFactory.getLogger(BindTicketIssuer.class);

    /** 与 IAM 侧的 BindTicketStore 必须一致。改这里就改那边。 */
    static final String KEY_PREFIX = "channel:bind:";

    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;
    private final ChannelProperties properties;
    private final SecureRandom random = new SecureRandom();

    public BindTicketIssuer(StringRedisTemplate redis, ChannelProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * 为一个渠道身份签发绑定票据。
     *
     * @param channel    渠道标识
     * @param externalId 渠道侧的用户 id（飞书 open_id）
     * @param displayName 渠道侧的显示名。Web 端展示「飞书账号「张三」」用它
     * @return 可直接拼进 URL 的 ticket 字符串
     */
    public String issue(String channel, String externalId, String displayName) {
        String ticket = randomTicket();
        // 用 JSON 而不是分隔符：displayName 是用户可控的字符串，
        // 含分隔符时会把字段错位解析成别人的身份
        String payload = """
                {"channel":"%s","externalId":"%s","displayName":"%s"}"""
                .formatted(jsonEscape(channel), jsonEscape(externalId), jsonEscape(displayName));
        redis.opsForValue().set(KEY_PREFIX + ticket, payload, TTL);
        log.info("签发绑定票据：channel={} externalId={}", channel, externalId);
        return ticket;
    }

    /** 拼接给用户点击的绑定链接。 */
    public String bindUrl(String ticket) {
        String base = properties.getBindBaseUrl();
        if (base == null || base.isBlank()) {
            // 没配就返回相对路径：在渠道里点不开，但至少不会拼出一个坏链接
            log.warn("未配置 channel.bind-base-url，绑定链接可能不可用");
            return "/iam/app/bind?ticket=" + ticket;
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/iam/app/bind?ticket=" + ticket;
    }

    private String randomTicket() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        // base64url：进 URL 查询参数不需要再编码，也就不会因为编码方式不同而对不上
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 最小化的 JSON 转义。displayName 可能含引号或反斜杠。 */
    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

package com.liusongboyy.chatdata.channel.identity;

import com.liusongboyy.chatdata.channel.config.ChannelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 换「代表某用户」的 IAM 令牌，并做本地缓存。
 *
 * <h2>为什么每次消息都换，但又要缓存</h2>
 *
 * <p>换取本身只是一次 HTTP 往返（几十毫秒），但用户连发三条消息就是三次往返，
 * 而且令牌 TTL 只有 5 分钟——大部分时候缓存都能命中。
 * 缓存键是用户名：同一个人在同一段时间内的所有消息共用一张令牌。
 *
 * <h2>提前刷新</h2>
 *
 * <p>卡在到期那一刻去换，会因为时钟偏差与网络往返拿到一张已经过期的令牌，
 * 请求随即 401，而错误信息看起来像"权限问题"。
 * 所以按 {@code refreshAhead} 提前换掉。
 *
 * <h2>它不缓存失败</h2>
 *
 * <p>用户未绑定、账号停用这类错误<b>绝不缓存</b>：管理员刚绑好，
 * 用户重发一条消息就该成功。缓存失败会让"刚配好却不生效"看起来像 bug。
 */
@Component
public class ChannelTokenService {

    private static final Logger log = LoggerFactory.getLogger(ChannelTokenService.class);

    private static final String GRANT_TYPE = "urn:chatdata:params:oauth:grant-type:channel";

    private final ChannelProperties properties;
    private final RestClient client;
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    public ChannelTokenService(ChannelProperties properties) {
        this.properties = properties;
        String base = properties.getIam().getBaseUrl();
        this.client = RestClient.builder()
                .baseUrl(base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                .build();
    }

    /**
     * 取某个用户的令牌。
     *
     * @param channel  渠道标识（{@code feishu}），IAM 用它校验绑定关系
     * @param username 目标用户
     * @return 可用的 Bearer 令牌
     * @throws TokenUnavailableException 换取失败（未绑定、账号停用、IAM 不可用）
     */
    public String tokenFor(String channel, String username) {
        CachedToken cached = cache.get(username);
        if (cached != null && cached.usable(properties.getIam().getRefreshAhead())) {
            return cached.value();
        }

        String secret = properties.getIam().getClientSecret();
        if (secret == null || secret.isBlank()) {
            throw new TokenUnavailableException(
                    "缺少 channel.iam.client-secret（环境变量 IAM_CHANNEL_SECRET）");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", GRANT_TYPE);
        form.add("channel", channel);
        form.add("channel_user", username);

        Map<?, ?> body;
        try {
            body = client.post()
                    .uri("/oauth2/token")
                    .headers(h -> h.setBasicAuth(properties.getIam().getClientId(), secret))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    // token endpoint 用 400 报业务错误、401 报客户端认证失败。
                    // 两者都要读响应体才知道原因，所以统一按错误处理再解析
                    .onStatus(status -> status.isError(), (req, res) -> {
                        throw new TokenUnavailableException(describe(res));
                    })
                    .body(Map.class);
        } catch (TokenUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("换令牌失败（IAM 不可用）：user={} {}", username, e.getMessage());
            throw new TokenUnavailableException("权限服务暂不可用，请稍后再试");
        }

        if (body == null || body.get("access_token") == null) {
            throw new TokenUnavailableException("权限服务返回异常，请稍后再试");
        }
        String token = String.valueOf(body.get("access_token"));
        long expiresIn = body.get("expires_in") instanceof Number n ? n.longValue() : 300;

        cache.put(username, new CachedToken(token, Instant.now().plusSeconds(expiresIn)));
        log.debug("换取渠道令牌成功：channel={} user={} ttl={}s", channel, username, expiresIn);
        return token;
    }

    /** 清除某个用户的缓存。解绑/换绑后调用，避免继续用旧身份的令牌。 */
    public void evict(String username) {
        cache.remove(username);
    }

    /**
     * 把 IAM 的错误转成用户能读的一句话。
     *
     * <p><b>不透传原始错误码。</b>用户看到 {@code invalid_grant} 只会困惑，
     * 而这里能判断出「未绑定」——那才是他需要知道的下一步。
     */
    private static String describe(org.springframework.http.client.ClientHttpResponse res) {
        // 注意：ClientHttpResponse 的 getBody()/getStatusCode() 都声明受检 IOException，
        // 而本方法是在 onStatus 的 lambda 里被调用的——lambda 不能声明受检异常。
        // 全部包成运行时异常，调用方按 unchecked 处理
        String description = "";
        try {
            description = readBody(res);
            if (description.contains("未绑定")) {
                return "你还没有绑定问数账号，请在 Web 端完成绑定后再试。";
            }
            if (description.contains("停用")) {
                return "你的问数账号已停用，请联系管理员。";
            }
        } catch (RuntimeException ignored) {
            // 读不到 body 就用状态码兜底，不让「读错误响应」本身再抛一次
        }
        log.warn("换令牌被拒：status={} body={}", statusOf(res), description);
        return "无法确认你的身份，请联系管理员。";
    }

    /**
     * 读错误响应体。
     *
     * <p>{@code getBody().readAllBytes()} 声明受检的 {@code IOException}，
     * 而它是在 {@code onStatus} 的 lambda 里被间接调用的——lambda 不能声明受检异常。
     * 所以在这里把它包成 {@code UncheckedIOException}，调用方按运行时异常处理。
     */
    private static int statusOf(org.springframework.http.client.ClientHttpResponse res) {
        try {
            return res.getStatusCode().value();
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    private static String readBody(org.springframework.http.client.ClientHttpResponse res) {
        try {
            return new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private record CachedToken(String value, Instant expiresAt) {
        /** 提前 {@code ahead} 视为不可用，留出网络往返与时钟偏差的余量。 */
        boolean usable(java.time.Duration ahead) {
            return Instant.now().plus(ahead).isBefore(expiresAt);
        }
    }

    /** 换不到令牌。消息里的话是**给最终用户看的**，不是给开发者看的。 */
    public static class TokenUnavailableException extends RuntimeException {
        public TokenUnavailableException(String message) {
            super(message);
        }
    }
}

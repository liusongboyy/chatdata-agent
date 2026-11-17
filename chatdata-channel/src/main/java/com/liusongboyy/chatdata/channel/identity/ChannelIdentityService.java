package com.liusongboyy.chatdata.channel.identity;

import com.liusongboyy.chatdata.channel.config.ChannelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渠道身份 → IAM 用户名。
 *
 * <h2>调 IAM 的接口，不查 IAM 的库</h2>
 *
 * <p>它原来直接写 SQL 查 {@code chatdata_iam.iam_user}：
 *
 * <pre>
 * SELECT username FROM chatdata_iam.iam_user
 * WHERE external_provider = ? AND external_id = ?
 * </pre>
 *
 * <p>那让 IAM 的 schema 变成了公开契约——IAM 拆库或改表结构，这里会在
 * <b>运行时</b>才发现，而失败表现是「飞书绑定查不到」，与「IAM 表结构变了」
 * 看起来毫无关系。IAM 要独立成平台（见 {@code .scratch/iam-platform-extraction/spec.md}），
 * 这种跨库直连必须先断掉。
 *
 * <p>「某个飞书 open_id 对应哪个本系统账号」本来就是<b>身份问题</b>，
 * 属于 IAM 的职责。现在是 {@code GET /iam/identity/resolve}。
 *
 * <h2>为什么要缓存</h2>
 *
 * <p>绑定关系极少变——一个人绑一次，之后几年都是同一行。而这条路径在
 * <b>每条飞书消息</b>上都要走一次（身份判定在所有处理之前）。
 * 不缓存的话，每次问数会多一次 HTTP 往返，还多一次 IAM 的库查询。
 *
 * <h2>缓存什么、不缓存什么</h2>
 *
 * <p><b>只缓存「已绑定」这个正结果。</b>「未绑定」不缓存：管理员刚绑好，
 * 用户重发一条消息就该成功。缓存未绑定会让"刚配好却不生效"看起来像 bug——
 * 与 {@link ChannelTokenService} 不缓存失败是同一取舍。
 *
 * <p>解绑后的窗口期：正结果缓存 {@code identityCacheTtl}（默认 60 秒），
 * 所以解绑后最多 60 秒内仍按旧身份解析。这期间 IAM 的 channel grant 会拒绝
 * 换令牌（它查的是库里的实时绑定），所以真正的问题是"给用户一条没用的消息"，
 * 而不是"越权访问"。60 秒是可接受的代价。
 */
@Component
public class ChannelIdentityService {

    private static final Logger log = LoggerFactory.getLogger(ChannelIdentityService.class);

    /** 解析外部身份所需的 scope。比 {@code iam.principal} 窄：读不到用户目录。 */
    private static final String SCOPE = "iam.identity";

    private final ChannelProperties properties;
    private final RestClient client;
    private final Map<String, CachedIdentity> cache = new ConcurrentHashMap<>();

    /**
     * 服务令牌缓存。
     *
     * <p>{@code volatile} + 方法级同步：解析身份在<b>每条消息</b>上都会发生，
     * 而换令牌是一次完整的 HTTP 往返。不缓存的话并发消息会同时去打
     * token endpoint，而那个端点的限流比查询接口严格得多。
     *
     * <p>用 {@code volatile} 而不是 {@code AtomicReference}：只有一个字段，
     * 而同步块已经保证了写-写的互斥，读走 volatile 就够。
     */
    private volatile String serviceToken;
    private volatile Instant serviceTokenExpiry = Instant.EPOCH;

    public ChannelIdentityService(ChannelProperties properties) {
        this(properties, RestClient.builder());
    }

    /**
     * 测试用：{@code MockRestServiceServer} 只能绑在 build 之前的 builder 上。
     *
     * <p>包级可见而不是 public——生产代码不该有机会传一个自制的 builder 进来，
     * 那会绕开 baseUrl 的配置。
     */
    ChannelIdentityService(ChannelProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        String base = properties.getIam().getBaseUrl();
        this.client = builder
                .baseUrl(base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                .build();
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
        String key = channel + '\u0000' + externalId;
        CachedIdentity cached = cache.get(key);
        if (cached != null && cached.usable()) {
            return Optional.of(cached.username());
        }

        Map<?, ?> body;
        try {
            body = client.get()
                    .uri(uri -> uri.path("/iam/identity/resolve")
                            .queryParam("provider", channel)
                            .queryParam("externalId", externalId)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken())
                    .retrieve()
                    // 404 是正常业务结果（未绑定），不是错误——单独处理，
                    // 不让它进 onStatus 的错误分支
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(),
                            (req, res) -> { /* 交给下面判空 */ })
                    .body(Map.class);
        } catch (RuntimeException e) {
            // **IAM 不可用时不能当成"未绑定"。** 那会给一个已绑定的用户发
            // 绑定引导，让他以为自己的绑定丢了，然后重新绑一遍——
            // 而真实原因只是 IAM 抖动了一下
            log.error("解析渠道身份失败（IAM 不可用）：channel={} {}", channel, e.getMessage());
            throw new IdentityUnavailableException("权限服务暂不可用，请稍后再试");
        }

        if (body == null || body.get("username") == null) {
            log.debug("渠道身份未绑定或已停用：channel={} externalId={}", channel, externalId);
            return Optional.empty();
        }
        String username = String.valueOf(body.get("username"));
        cache.put(key, new CachedIdentity(username,
                Instant.now().plus(properties.getIam().getIdentityCacheTtl())));
        return Optional.of(username);
    }

    /**
     * 取服务令牌（client_credentials）。
     *
     * <p>提前 30 秒视为过期：卡在到期那一刻用，会因为时钟偏差与网络往返
     * 拿到一张已经过期的令牌，请求随即 401——而错误信息看起来像"权限问题"。
     *
     * <p>与 {@link ChannelTokenService} 的两处不同：
     *
     * <ul>
     *   <li>那个换的是<b>代表某个人</b>的令牌（自定义 grant），这个换的是
     *       <b>代表渠道自身</b>的令牌（client_credentials）</li>
     *   <li>那个按用户缓存，这个全局只有一张</li>
     * </ul>
     */
    private synchronized String serviceToken() {
        if (serviceToken != null && Instant.now().isBefore(serviceTokenExpiry)) {
            return serviceToken;
        }
        String secret = properties.getIam().getClientSecret();
        if (secret == null || secret.isBlank()) {
            // 在**这里**失败，而不是发一个空密码过去等 401：
            // 401 会被当成"IAM 拒绝了我们"，而真实原因是本地没配环境变量
            throw new IdentityUnavailableException(
                    "缺少 channel.iam.client-secret（环境变量 IAM_CHANNEL_SECRET）");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", SCOPE);
        String basic = Base64.getEncoder().encodeToString(
                (properties.getIam().getClientId() + ":" + secret)
                        .getBytes(StandardCharsets.UTF_8));

        Map<?, ?> body = client.post()
                .uri("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .body(form)
                .retrieve()
                .body(Map.class);
        if (body == null || body.get("access_token") == null) {
            throw new IdentityUnavailableException("权限服务未返回令牌，请稍后再试");
        }
        this.serviceToken = String.valueOf(body.get("access_token"));
        long expiresIn = body.get("expires_in") instanceof Number n ? n.longValue() : 300;
        this.serviceTokenExpiry = Instant.now().plusSeconds(Math.max(30, expiresIn - 30));
        return this.serviceToken;
    }

    /** 清除某个外部身份的缓存。解绑/换绑后调用，避免继续用旧身份。 */
    public void evict(String channel, String externalId) {
        if (externalId == null) {
            return;
        }
        cache.remove(channel + '\u0000' + externalId);
    }

    private record CachedIdentity(String username, Instant expiresAt) {
        boolean usable() {
            return Instant.now().isBefore(expiresAt);
        }
    }

    /**
     * 解析不了身份——<b>不是</b>"未绑定"。
     *
     * <p>两者的用户可见后果完全不同：未绑定要引导绑定，服务不可用要让人等一会儿。
     * 混成一个会让 IAM 抖动一次就骗一批用户去重新绑定。
     */
    public static class IdentityUnavailableException extends RuntimeException {
        public IdentityUnavailableException(String message) {
            super(message);
        }
    }
}

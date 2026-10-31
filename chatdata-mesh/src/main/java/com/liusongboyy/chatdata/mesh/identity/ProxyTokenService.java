package com.liusongboyy.chatdata.mesh.identity;

import com.liusongboyy.chatdata.mesh.config.MeshProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 换一张「代表某用户」的 IAM 令牌。
 *
 * <h2>它和 channel 的 ChannelTokenService 是同构的，但不共享代码</h2>
 *
 * <ul>
 *   <li>信任边界不同：channel 面对飞书推送的人，mesh 面对公司内其他 agent</li>
 *   <li>失败话术不同：channel 的报错给业务人员看，mesh 的给调用方 agent 的开发者看</li>
 * </ul>
 *
 * <p>重复几十行，代价远小于把两个信任边界混在一起。
 *
 * <h2>判据在 IAM，不在这里</h2>
 *
 * <p>「用户是否授权过这个 agent」由 IAM 换令牌时查 {@code iam_agent_grant}。
 * 本类不知道授权表的存在——**判据只留一处**，行为才可预测。
 *
 * <p>所以这里拒绝的原因只有三类：客户端凭据错、IAM 不可用、
 * 或者 IAM 说「无有效授权」。第三类要原样告诉调用方，
 * 因为它能据此去走授权流程。
 */
@Component
public class ProxyTokenService {

    private static final Logger log = LoggerFactory.getLogger(ProxyTokenService.class);

    private static final String GRANT_TYPE = "urn:chatdata:params:oauth:grant-type:agent-proxy";

    private final MeshProperties properties;
    private final RestClient client;
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    public ProxyTokenService(MeshProperties properties) {
        this.properties = properties;
        String base = properties.getIam().getBaseUrl();
        this.client = RestClient.builder()
                .baseUrl(base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                .build();
    }

    /**
     * 换一张代表目标用户的令牌。
     *
     * @param agentClient 调用方标识。与授权表里的 {@code agent_client} 必须一致
     * @param username    目标用户
     * @throws TokenUnavailableException 无授权、账号停用、IAM 不可用
     */
    public String tokenFor(String agentClient, String username) {
        // 缓存键要带 agentClient：同一个用户被 A 授权不代表被 B 授权，
        // 只按用户名缓存会让 B 复用 A 换来的令牌——那等于跳过了授权检查
        String cacheKey = agentClient + "\u0000" + username;
        CachedToken cached = cache.get(cacheKey);
        if (cached != null && cached.usable(properties.getIam().getRefreshAhead())) {
            return cached.value();
        }

        String secret = properties.getIam().getClientSecret();
        if (secret == null || secret.isBlank()) {
            throw new TokenUnavailableException(
                    "mesh.iam.client-secret 未配置（环境变量 IAM_MESH_SECRET）");
        }

        // 用显式 URL 编码的表单串，不用 MultiValueMap：
        // RestClient 对 MultiValueMap 的编码方式在某些配置下会多套一层，
        // 表现为 IAM 返回 invalid_request（参数名对不上）。
        // 手写编码虽然原始，但"发出去的到底是什么"一目了然。
        String form = "grant_type=" + URLEncoder.encode(GRANT_TYPE, StandardCharsets.UTF_8)
                + "&agent_client=" + URLEncoder.encode(agentClient, StandardCharsets.UTF_8)
                + "&proxy_user=" + URLEncoder.encode(username, StandardCharsets.UTF_8);

        // 把发出去的 body 原样打出来。IAM 的 invalid_request 只说"参数有问题"，
        // 不说哪个参数——而这条链路上唯一可能出问题的就是这段字符串
        log.info("换令牌请求：client={} body=[{}]", properties.getIam().getClientId(), form);

        Map<?, ?> body;
        try {
            body = client.post()
                    .uri("/oauth2/token")
                    // 用 header() 而不是 headers(h -> h.setBasicAuth(...))：
                    // 后者会先清空已有头再设 Authorization，而之后的 contentType()
                    // 又要写 Content-Type——两者在同一个 header 回调里设置时
                    // 顺序不好控，出现过 Content-Type 把 Authorization 挤掉的情况。
                    .header("Authorization", "Basic " + base64(
                            properties.getIam().getClientId() + ":" + secret))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .onStatus(status -> status.isError(), (req, res) -> {
                        // 错误响应体里才有"未授权"这类可操作的说明，
                        // 必须读出来才能翻译成调用方能处理的话术
                        throw new TokenUnavailableException(describe(res));
                    })
                    .body(Map.class);
        } catch (TokenUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("A2A 换令牌失败（IAM 不可用）：agent={} user={} {}",
                    agentClient, username, e.getMessage());
            throw new TokenUnavailableException("权限服务暂不可用");
        }

        if (body == null || body.get("access_token") == null) {
            throw new TokenUnavailableException("权限服务返回异常");
        }
        String token = String.valueOf(body.get("access_token"));
        long expiresIn = body.get("expires_in") instanceof Number n ? n.longValue() : 300;
        cache.put(cacheKey, new CachedToken(token, Instant.now().plusSeconds(expiresIn)));
        log.info("A2A 换取代理令牌：agent={} user={} ttl={}s", agentClient, username, expiresIn);
        return token;
    }

    /** 清掉某个用户的全部缓存令牌（授权被撤销时用）。 */
    public void evictUser(String username) {
        cache.keySet().removeIf(k -> k.endsWith("\u0000" + username));
    }

    /**
     * 把 IAM 的错误翻成调用方能处理的说明。
     *
     * <p>「未授权」这类**要原样传达**——调用方可以据此去走授权流程；
     * 而「服务不可用」只该说"重试"。
     */
    private static String describe(org.springframework.http.client.ClientHttpResponse res) {
        try {
            String text = new String(res.getBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (text.contains("未授权")) {
                return "目标用户未授权该 agent 代表其查询。"
                        + "需要用户先在问数系统里完成授权。";
            }
            if (text.contains("停用")) {
                return "目标用户已停用";
            }
            if (text.contains("无权使用")) {
                return "mesh 客户端的 grant 配置有误，请联系管理员";
            }
            // 把 IAM 的原始响应带出来：这一支说明前面几个已知原因都没命中，
            // 而"未知原因"最需要原始信息——否则只能瞎猜
            log.warn("换令牌被 IAM 拒绝：status={} body={}", statusOf(res), text);
            return "无法为目标用户换取令牌（IAM 拒绝）";
        } catch (java.io.IOException e) {
            return "无法为目标用户换取令牌";
        }
    }

    private static String base64(String v) {
        return java.util.Base64.getEncoder()
                .encodeToString(v.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static int statusOf(org.springframework.http.client.ClientHttpResponse res) {
        try {
            return res.getStatusCode().value();
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    private record CachedToken(String value, Instant expiresAt) {
        boolean usable(Duration ahead) {
            return Instant.now().plus(ahead).isBefore(expiresAt);
        }
    }

    /** 换不到令牌。消息给**调用方 agent 的开发者**看，不是终端用户。 */
    public static class TokenUnavailableException extends RuntimeException {
        public TokenUnavailableException(String message) {
            super(message);
        }
    }
}

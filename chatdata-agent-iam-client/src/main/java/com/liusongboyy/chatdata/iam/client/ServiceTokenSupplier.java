package com.liusongboyy.chatdata.iam.client;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * 服务身份令牌（client_credentials），带进程内缓存。
 *
 * <h2>为什么抽出来</h2>
 *
 * <p>资源服务器调 IAM 的服务间接口不只一处（读策略、读角色目录），而换令牌这段
 * 代码写两遍就会有两份"提前多久刷新""失败怎么报"的实现，其中一份迟早会漂移。
 * 漂移的表现是"策略能读、角色目录 401"，排查时看到的是两套互不相干的日志。
 *
 * <h2>为什么提前 30 秒过期</h2>
 *
 * <p>令牌在请求途中过期时，IAM 返回 401，而调用方会把它当成"凭据不对"或
 * "IAM 不可用"——两种结论都与真实原因无关。提前 30 秒换掉，边界就不会出现。
 */
final class ServiceTokenSupplier {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient client;
    private final String clientId;
    private final String clientSecret;

    private volatile String accessToken;
    private volatile Instant accessTokenExpiry = Instant.EPOCH;

    ServiceTokenSupplier(RestClient client, String clientId, String clientSecret) {
        this.client = client;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /**
     * 当前可用的令牌，必要时换一个新的。
     *
     * <p>密钥缺失时在**这里**就失败，而不是发一个空密码过去等 401：
     * 401 会被上层当成"IAM 拒绝了我们"，而真实原因是本地没配环境变量。
     */
    synchronized String token() {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpiry)) {
            return accessToken;
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException(
                    "未配置 chatdata.iam.client-secret（环境变量 IAM_RESOURCE_SECRET），"
                            + "无法以服务身份调用 IAM");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", "iam.principal");

        String basic = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        Map<String, Object> body = client.post()
                .uri("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .body(form)
                .retrieve()
                .body(MAP_TYPE);
        if (body == null || body.get("access_token") == null) {
            throw new IllegalStateException("IAM 未返回 access_token");
        }
        this.accessToken = String.valueOf(body.get("access_token"));
        long expiresIn = body.get("expires_in") instanceof Number n ? n.longValue() : 300;
        this.accessTokenExpiry = Instant.now().plusSeconds(Math.max(30, expiresIn - 30));
        return this.accessToken;
    }
}

package com.liusongboyy.chatdata.iam.client;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 读 IAM 的用户目录（服务间调用），供策略编辑器选择用户。
 *
 * <p>与 {@link IamRoleDirectoryClient} 同一套鉴权与失败语义：
 * 客户端凭证、只读、失败就抛，由调用方决定是降级还是报 503。
 *
 * <p>返回的 {@code username} 才是权限配置里要填的值；显示名只用于界面。
 */
public class IamUserDirectoryClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient client;
    private final ServiceTokenSupplier tokens;

    public IamUserDirectoryClient(String iamBaseUrl, String clientId, String clientSecret) {
        this(RestClient.builder().baseUrl(stripTrailingSlash(iamBaseUrl)), clientId, clientSecret);
    }

    /** 测试用：MockRestServiceServer 只能绑在 build 之前的 builder 上。 */
    IamUserDirectoryClient(RestClient.Builder builder, String clientId, String clientSecret) {
        this.client = builder.build();
        this.tokens = new ServiceTokenSupplier(this.client, clientId, clientSecret);
    }

    /**
     * 按关键词搜索用户。
     *
     * @param keyword 账号或显示名的片段；空串等价于不过滤
     * @param limit   最多返回条数，服务端会再夹到安全范围
     * @throws IllegalStateException IAM 不可用、凭据缺失或响应无法解析
     */
    public List<UserEntry> users(String keyword, int limit) {
        Map<String, Object> body;
        try {
            body = client.get()
                    .uri(uri -> uri.path("/iam/policy/users")
                            .queryParam("keyword", keyword == null ? "" : keyword)
                            .queryParam("limit", limit)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                    .retrieve()
                    .body(MAP_TYPE);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(
                    "读取 IAM 用户目录失败：HTTP " + e.getStatusCode().value(), e);
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException) {
                throw e;
            }
            throw new IllegalStateException("读取 IAM 用户目录失败：" + e.getMessage(), e);
        }

        List<UserEntry> out = new ArrayList<>();
        if (body == null || !(body.get("items") instanceof List<?> items)) {
            return out;
        }
        for (Object raw : items) {
            if (!(raw instanceof Map<?, ?> item) || item.get("username") == null) {
                continue;
            }
            out.add(new UserEntry(
                    String.valueOf(item.get("username")),
                    item.get("displayName") == null ? null : String.valueOf(item.get("displayName")),
                    item.get("status") == null ? null : String.valueOf(item.get("status"))));
        }
        return out;
    }

    /**
     * 按用户名批量取，用于策略保存时的 best-effort 存在性校验。
     *
     * <p>与搜索分开：这里**不做模糊匹配**，返回的就是这些 username 里真实存在的那些。
     * 调用方拿返回集合与请求集合求差集，差集就是"引用了不存在的用户"。
     *
     * @param usernames 待校验的用户名；空集合返回空列表且不发请求
     * @throws IllegalStateException IAM 不可用或响应无法解析
     */
    public List<UserEntry> usersByNames(java.util.Collection<String> usernames) {
        if (usernames == null || usernames.isEmpty()) {
            return List.of();
        }
        String joined = usernames.stream()
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .collect(java.util.stream.Collectors.joining(","));
        if (joined.isEmpty()) {
            return List.of();
        }

        Map<String, Object> body;
        try {
            body = client.get()
                    .uri(uri -> uri.path("/iam/policy/users")
                            .queryParam("usernames", joined)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                    .retrieve()
                    .body(MAP_TYPE);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(
                    "批量读取 IAM 用户失败：HTTP " + e.getStatusCode().value(), e);
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException) {
                throw e;
            }
            throw new IllegalStateException("批量读取 IAM 用户失败：" + e.getMessage(), e);
        }

        List<UserEntry> out = new ArrayList<>();
        if (body == null || !(body.get("items") instanceof List<?> items)) {
            return out;
        }
        for (Object raw : items) {
            if (!(raw instanceof Map<?, ?> item) || item.get("username") == null) {
                continue;
            }
            out.add(new UserEntry(
                    String.valueOf(item.get("username")),
                    item.get("displayName") == null ? null : String.valueOf(item.get("displayName")),
                    item.get("status") == null ? null : String.valueOf(item.get("status"))));
        }
        return out;
    }

    /**
     * @param username    权限配置里要填的值
     * @param displayName 显示名
     * @param status      ACTIVE / DISABLED
     */
    public record UserEntry(String username, String displayName, String status) {
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}

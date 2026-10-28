package com.liusongboyy.chatdata.iam.client;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 读 IAM 的角色目录（服务间调用）。
 *
 * <h2>它解决的问题</h2>
 *
 * <p>权限配置（数据策略、助理授权）按**角色码逐字符**引用角色。角色码写错时，
 * 规则不会报错、不会打日志，只是永远匹配不上——"限制了"和"没限制"在界面上长得一模一样。
 * 要当场发现这类错误，调用方必须知道 IAM 里到底有哪些角色码。
 *
 * <p>不能拿 {@code /iam/admin/roles} 代替：那个接口认用户令牌、要求 {@code iam.roles}
 * 管理权限，而调用它的场合（启动校验）连一个用户都没有。所以走服务凭证
 * （{@link ServiceTokenSupplier}）。
 *
 * <h2>失败就要抛</h2>
 *
 * <p>与策略客户端相反，这里**不把失败吞成 empty**：{@code empty} 在策略语义里是
 * "该资源没有策略"（= 拒绝），而"没有角色目录"是两个完全不同的意思。
 * 是否降级（跳过校验 / 返回 503）由调用方按场景决定，客户端只负责说清发生了什么。
 */
public class IamRoleDirectoryClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient client;
    private final ServiceTokenSupplier tokens;

    public IamRoleDirectoryClient(String iamBaseUrl, String clientId, String clientSecret) {
        this(RestClient.builder().baseUrl(stripTrailingSlash(iamBaseUrl)), clientId, clientSecret);
    }

    /**
     * 测试用：传入预配置的 {@code Builder}。
     *
     * <p>收 {@code Builder} 的理由与 {@link IamUserDirectoryClient} 相同——
     * {@code MockRestServiceServer} 只能绑在 build 之前的 builder 上。
     */
    IamRoleDirectoryClient(RestClient.Builder builder, String clientId, String clientSecret) {
        this.client = builder.build();
        this.tokens = new ServiceTokenSupplier(this.client, clientId, clientSecret);
    }

    /**
     * 全部角色（**含已停用的**）。
     *
     * @return 顺序与 IAM 一致（按分类、id）；{@code code} 原样返回，不 trim、不改大小写
     * @throws IllegalStateException IAM 不可用、凭据缺失或响应无法解析
     */
    public List<RoleEntry> roles() {
        Map<String, Object> body;
        try {
            body = client.get()
                    .uri("/iam/policy/roles")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                    .retrieve()
                    .body(MAP_TYPE);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(
                    "读取 IAM 角色目录失败：HTTP " + e.getStatusCode().value(), e);
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException) {
                throw e;
            }
            throw new IllegalStateException("读取 IAM 角色目录失败：" + e.getMessage(), e);
        }

        List<RoleEntry> out = new ArrayList<>();
        if (body == null || !(body.get("items") instanceof List<?> items)) {
            return out;
        }
        for (Object raw : items) {
            if (!(raw instanceof Map<?, ?> item) || item.get("code") == null) {
                continue;
            }
            out.add(new RoleEntry(
                    String.valueOf(item.get("code")),
                    item.get("name") == null ? null : String.valueOf(item.get("name")),
                    item.get("category") == null ? null : String.valueOf(item.get("category")),
                    Boolean.TRUE.equals(item.get("enabled"))));
        }
        return out;
    }

    /**
     * 角色目录的一项。
     *
     * @param code     角色码。**权限配置里要填的就是它**
     * @param name     显示名，给人看
     * @param category ADMIN / DATA / FUNCTION，见 ADR-0004
     * @param enabled  停用的角色不参与鉴权，规则引用它同样不会生效
     */
    public record RoleEntry(String code, String name, String category, boolean enabled) {
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}

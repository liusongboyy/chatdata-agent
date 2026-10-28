package com.liusongboyy.chatdata.iam.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 用 IAM 的 {@code /iam/principal} 把不透明令牌换成主体，并缓存结果。
 *
 * <h2>为什么用 /iam/principal 而不是 /oauth2/introspect</h2>
 *
 * <p>标准内省只返回标准字段（active/scope/sub/client_id），拿不到角色与组织——
 * 而这两样正是本项目的权限判据。{@code /iam/principal} 是它的超集，
 * 且同样是"令牌即凭据"（不需要客户端密钥）。
 *
 * <h2>缓存</h2>
 *
 * <p>key 用令牌的 SHA-256，**不把明文令牌写进 Redis key 或日志**。
 * TTL 默认 30 秒：够挡住同一个页面的并发请求，又不会让角色变更延迟太久。
 * 没有 Redis 时退化为每次都问 IAM——那只是慢，不影响正确性。
 *
 * <h2>失败一律拒绝</h2>
 *
 * <p>令牌无效 → {@link BadOpaqueTokenException}（Spring 映射成 401）；
 * IAM 不可用 → 抛 {@link OAuth2AuthenticationException}（映射成 401/503）。
 * **绝不因为"查不到"就放行**——那是本项目最忌讳的失败形态（ADR-0002）。
 */
public class IamIntrospector implements OpaqueTokenIntrospector {

    private static final Logger log = LoggerFactory.getLogger(IamIntrospector.class);
    /**
     * 缓存 key 前缀。
     *
     * <p>{@code v2} 是**编码格式版本**：v2 起多了一段"功能权限"。
     * 带上版本号是为了让滚动发布期间的边界明确——新旧实例各读各的键，
     * 而不是互相读到对方写的、字段数不同的值。旧键会在 TTL 内自然消失。
     */
    private static final String CACHE_PREFIX = "iam:tok:v2:";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient client;
    private final StringRedisTemplate cache;
    private final Duration ttl;

    /**
     * @param iamBaseUrl IAM 的地址，如 {@code http://127.0.0.1:9000}
     * @param cache      Redis 模板；可为 null（无缓存）
     * @param ttl        缓存有效期
     */
    public IamIntrospector(String iamBaseUrl, StringRedisTemplate cache, Duration ttl) {
        this.client = RestClient.builder()
                .baseUrl(iamBaseUrl.endsWith("/") ? iamBaseUrl.substring(0, iamBaseUrl.length() - 1) : iamBaseUrl)
                .build();
        this.cache = cache;
        this.ttl = ttl == null ? Duration.ofSeconds(30) : ttl;
    }

    @Override
    public OAuth2AuthenticatedPrincipal introspect(String token) {
        if (token == null || token.isBlank()) {
            throw new BadOpaqueTokenException("缺少令牌");
        }
        IamPrincipal principal = fromCache(token);
        if (principal == null) {
            principal = fetch(token);
            cache(token, principal);
        }
        return toAuthenticatedPrincipal(principal);
    }

    private IamPrincipal fromCache(String token) {
        if (cache == null) {
            return null;
        }
        try {
            String encoded = cache.opsForValue().get(cacheKey(token));
            return encoded == null ? null : decode(encoded);
        } catch (RuntimeException e) {
            // 缓存读失败不是"令牌无效"：忽略缓存，继续问 IAM
            log.debug("内省缓存读取失败，回退到 IAM：{}", e.getMessage());
            return null;
        }
    }

    private void cache(String token, IamPrincipal principal) {
        if (cache == null) {
            return;
        }
        try {
            cache.opsForValue().set(cacheKey(token), encode(principal), ttl);
        } catch (RuntimeException e) {
            log.debug("内省缓存写入失败：{}", e.getMessage());
        }
    }

    private IamPrincipal fetch(String token) {
        Map<String, Object> body;
        try {
            body = client.get()
                    .uri("/iam/principal")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .onStatus(status -> status.value() == 401, (request, response) -> {
                        throw new BadOpaqueTokenException("令牌无效或已过期");
                    })
                    .body(MAP_TYPE);
        } catch (BadOpaqueTokenException e) {
            throw e;
        } catch (RuntimeException e) {
            // IAM 不可用：拒绝，不回落成放行
            log.error("内省失败（IAM 不可用）：{}", e.getMessage());
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("introspection_unavailable", "权限服务暂不可用", null), e);
        }
        if (body == null || body.isEmpty()) {
            throw new BadOpaqueTokenException("内省返回为空");
        }
        return IamPrincipal.from(body);
    }

    /**
     * roles → {@code ROLE_*}，scope → {@code SCOPE_*}，permissions → {@code PERM_*}：
     * 与 Spring Security 的约定一致，这样 {@code @PreAuthorize("hasAuthority('PERM_…')")}
     * 或 {@code @perm.has(…)} 都能直接用。
     *
     * <p><strong>功能权限只进 authorities，不进 attributes。</strong>
     * attributes 是 {@code AskRequest.attributes()} 的来源，而那个 map 会被当作
     * 行规则的 {@code dynamicVars} —— 权限列表混进去就成了一个莫名其妙的规则变量，
     * 而 {@code rowRules[].sysVar} 写错的表现是"规则无法求值 → 拒绝"。
     */
    private static OAuth2AuthenticatedPrincipal toAuthenticatedPrincipal(IamPrincipal principal) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        principal.roles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        principal.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority("PERM_" + p)));
        principal.scope().forEach(s -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + s)));

        Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        attributes.put("userId", principal.userId());
        attributes.put("tenant", principal.tenant());
        attributes.put("username", principal.username());
        attributes.put("displayName", principal.displayName());
        attributes.put("orgId", principal.orgId());
        attributes.put("orgCode", principal.orgCode());
        attributes.put("orgPath", principal.orgPath());
        // 全部组织路径。行规则里 sysVar 写 orgPaths 时取它，支持兼岗的并集语义。
        // 单一组织时 orgPaths 与 orgPath 内容一致，两种写法等价
        attributes.put("orgPaths", principal.orgPaths());
        // 编码路径。业务库认组织编码，行规则按它做前缀匹配；
        // sysVar 写 orgCodePath / orgCodePaths 时取这两个
        attributes.put("orgCodePath", principal.orgCodePath());
        attributes.put("orgCodePaths", principal.orgCodePaths());
        attributes.put("roles", principal.roles());
        attributes.put("scope", principal.scope());
        attributes.put("tokenVersion", principal.tokenVersion());
        return new DefaultOAuth2AuthenticatedPrincipal(
                principal.username(), attributes, authorities);
    }

    private static String cacheKey(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return CACHE_PREFIX + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 缓存编码：字段用 US() 分隔，集合内的元素用 RS() 分隔。
     *
     * <p>刻意**不引 JSON 库**：Boot 4 的 classpath 上是 Jackson 3（tools.jackson），
     * 而这个库要同时被 servlet 与 reactive 服务复用，多引一个序列化器只会多一处版本摩擦。
     * 字段都是简单值，用分隔符足够，而且编解码是自洽的一对。
     */
    static String encode(IamPrincipal p) {
        return String.join("\u001f",
                String.valueOf(p.userId()),
                nullSafe(p.tenant()),
                nullSafe(p.username()),
                nullSafe(p.displayName()),
                p.orgId() == null ? "" : String.valueOf(p.orgId()),
                nullSafe(p.orgCode()),
                nullSafe(p.orgPath()),
                String.join("\u001e", p.roles()),
                String.join("\u001e", p.scope()),
                nullSafe(p.clientId()),
                p.expiresAt() == null ? "" : p.expiresAt().toString(),
                String.valueOf(p.tokenVersion()),
                // 功能权限**追加在末尾**：前面的下标全部不动，滚动发布时
                // 新旧实例读到的前 12 段含义一致。
                // 空集合也要占位（String.join 会留下一个尾部分隔符），
                // 否则 split(…, -1) 得到 12 段，新代码会当成"旧格式"而丢弃权限
                String.join("\u001e", p.permissions()),
                // 组织路径**继续追加在末尾**。同样是为了滚动发布：
                // 旧实例读到多出来的第 14 段会当作"字段数更多"而正常解析前面的
                String.join("\u001e", p.orgPaths()),
                // 编码路径继续往后追加（第 15、16 段），规则同上
                nullSafe(p.orgCodePath()),
                String.join("\u001e", p.orgCodePaths()));
    }

    static IamPrincipal decode(String encoded) {
        String[] f = encoded.split("\u001f", -1);
        // 12 → 13 → 14 → 16：段数不足的旧缓存会在这里抛，被 fromCache 捕获后退回问 IAM。
        // 安全降级——多一次往返，不会读出错的身份
        if (f.length < 16) {
            throw new IllegalStateException("缓存内容字段数不足：" + f.length);
        }
        return new IamPrincipal(
                Long.parseLong(f[0]),
                emptyToNull(f[1]),
                emptyToNull(f[2]),
                emptyToNull(f[3]),
                f[4].isEmpty() ? null : Long.parseLong(f[4]),
                emptyToNull(f[5]),
                emptyToNull(f[6]),
                splitList(f[13]),        // orgPaths（编码里追加在末尾，record 里紧跟 orgPath）
                emptyToNull(f[14]),      // orgCodePath
                splitList(f[15]),        // orgCodePaths
                splitSet(f[7]),          // roles
                // 权限在**编码里排最后**（追加），但 record 的分量顺序里紧跟 roles。
                // 这两处顺序不一致是刻意的：编码顺序要迁就"新字段只能追加"，
                // 而 record 顺序要迁就可读性
                splitSet(f[12]),         // permissions
                splitSet(f[8]),          // scope
                emptyToNull(f[9]),
                f[10].isEmpty() ? null : Instant.parse(f[10]),
                Integer.parseInt(f[11]));
    }

    /**
     * 解码一个集合。
     *
     * <p>用 {@code LinkedHashSet} 而不是 {@code Set.of}：后者遇到重复元素会**抛异常**，
     * 而症状是 401（看起来像令牌失效）。上游只要有一条没写 DISTINCT 的 SQL
     * 就能触发——一次 Redis 抖动不该被误报成鉴权失败。
     */
    /**
     * 解码一个**有序**列表。
     *
     * <p>组织路径的顺序有语义（主组织在前），且重复项没有意义但不是错误，
     * 所以用 {@code distinct()} 而不是 {@code Set.of}——后者遇重复会抛。
     */
    private static java.util.List<String> splitList(String v) {
        if (v.isEmpty()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(v.split("\u001e"))
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private static java.util.Set<String> splitSet(String v) {
        if (v.isEmpty()) {
            return java.util.Set.of();
        }
        return java.util.Set.copyOf(new java.util.LinkedHashSet<>(java.util.List.of(v.split("\u001e"))));
    }

    private static String nullSafe(String v) {
        return v == null ? "" : v;
    }

    private static String emptyToNull(String v) {
        return v == null || v.isEmpty() ? null : v;
    }

    /** 令牌是否已过期（缓存命中时也检查一次）。 */
    public boolean expired(IamPrincipal principal) {
        return principal.expiresAt() != null && principal.expiresAt().isBefore(Instant.now());
    }
}

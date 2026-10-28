package com.liusongboyy.chatdata.iam.web;

import com.liusongboyy.chatdata.iam.security.IamUserDetails;
import com.liusongboyy.chatdata.iam.security.IamUserDetailsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用不透明令牌换主体信息（"富内省"）。
 *
 * <h2>它和内省端点的分工</h2>
 *
 * <p>{@code /oauth2/introspect} 按 RFC 7662 返回标准字段（active / scope / sub / client_id…），
 * 供第三方资源服务器使用；本端点额外返回**业务属性**（组织、角色、token_version），
 * 供网关 BFF 与自研服务使用。
 *
 * <p>为什么需要它：access token 是不透明的，令牌里没有任何信息；
 * 而网关做 OAuth2 登录时需要知道"这个人是谁"才能建立会话。
 *
 * <h2>鉴权</h2>
 *
 * <p>凭据就是令牌本身（持有即证明），因此本端点在过滤链上放行。
 * 拿不到有效令牌一律 401，且**不区分"令牌不存在"与"已过期"**——
 * 调用方只需要知道能不能用。
 */
@RestController
public class PrincipalController {

    private static final Logger log = LoggerFactory.getLogger(PrincipalController.class);

    private final OAuth2AuthorizationService authorizations;
    private final RegisteredClientRepository clients;
    private final IamUserDetailsService users;

    public PrincipalController(OAuth2AuthorizationService authorizations,
                               RegisteredClientRepository clients,
                               IamUserDetailsService users) {
        this.authorizations = authorizations;
        this.clients = clients;
        this.users = users;
    }

    @GetMapping("/iam/principal")
    public ResponseEntity<?> principal(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "缺少 Bearer 令牌"));
        }
        String token = authorization.substring("Bearer ".length()).trim();

        OAuth2Authorization auth = authorizations.findByToken(token, OAuth2TokenType.ACCESS_TOKEN);
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "令牌无效"));
        }
        OAuth2Authorization.Token<OAuth2AccessToken> accessToken = auth.getAccessToken();
        if (accessToken == null || accessToken.getToken() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "令牌无效"));
        }
        Instant expiresAt = accessToken.getToken().getExpiresAt();
        if (expiresAt != null && expiresAt.isBefore(Instant.now())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "令牌已过期"));
        }
        if (accessToken.isInvalidated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "令牌已失效"));
        }

        IamUserDetails user;
        try {
            user = users.loadPrincipal(auth.getPrincipalName());
        } catch (RuntimeException e) {
            // 令牌有效但用户没了（被删/被改）：按未认证处理，而不是 500
            log.warn("令牌对应的用户已不可用：principal={}", auth.getPrincipalName());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "账号不可用"));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", user.userId());
        out.put("tenant", user.tenant());
        out.put("username", user.username());
        out.put("displayName", user.displayName());
        out.put("orgId", user.orgId());
        out.put("orgCode", user.orgCode());
        out.put("orgPath", user.orgPath());
        // 全部组织路径（兼岗）。行权限按它取并集；orgPath 保留是为了
        // 单组织场景的兼容——老配置里 sysVar=orgPath 仍按单值解释
        out.put("orgPaths", user.orgPaths());
        out.put("roles", user.roleCodes());
        // 功能权限（页面/按钮/tab）。**必须与 @perm 的判定同源**——
        // 两处算法不一致会得到"界面有按钮、点了全 403"（见 PermissionChecker）
        out.put("permissions", user.permissionCodes());
        out.put("tokenVersion", user.tokenVersion());
        out.put("scope", auth.getAuthorizedScopes());
        // 返回 client_id 字符串而不是客户端主键：调用方认的是前者
        RegisteredClient client = clients.findById(auth.getRegisteredClientId());
        out.put("clientId", client == null ? auth.getRegisteredClientId() : client.getClientId());
        out.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        return ResponseEntity.ok(out);
    }
}

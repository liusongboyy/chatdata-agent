package com.liusongboyy.chatdata.iam.web;

import com.liusongboyy.chatdata.iam.security.CaptchaWebAuthenticationDetails;
import com.liusongboyy.chatdata.iam.security.IamUserDetails;
import com.liusongboyy.chatdata.iam.security.IamUserDetailsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录接口（JSON）。
 *
 * <h2>它只建立会话，不签发令牌</h2>
 *
 * <p>令牌的唯一签发点是 {@code /oauth2/token}。如果这里也发一份，
 * 就会出现两条令牌路径——审计与吊销会分叉，而这正是 ADR-0008 要避免的。
 *
 * <p>流程：SPA 调本接口 → 会话建立（Redis）→ 浏览器跳回 {@code /oauth2/authorize}
 * → 授权服务器发授权码 → 网关（BFF）换取不透明令牌。
 *
 * <h2>验证码</h2>
 *
 * <p>通过 {@link CaptchaWebAuthenticationDetails} 传给认证提供者；
 * 校验在 {@code CaptchaAuthenticationProvider} 里、**先于** BCrypt 比对。
 */
@RestController
@RequestMapping("/iam/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** 与登录页提示一致：不区分"账号不存在"与"密码错误"。 */
    private static final String GENERIC_FAILURE = "账号或密码错误";

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final IamUserDetailsService userDetailsService;

    public AuthController(AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository,
                          IamUserDetailsService userDetailsService) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.userDetailsService = userDetailsService;
    }

    public record LoginRequest(String username, String password, String captcha, String checkKey) {
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody(required = false) LoginRequest body,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        if (body == null || body.username() == null || body.password() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "账号与密码不能为空"));
        }

        UsernamePasswordAuthenticationToken token =
                UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password());
        token.setDetails(new CaptchaWebAuthenticationDetails(
                request.getRemoteAddr(), request.getSession().getId(),
                body.captcha(), body.checkKey()));

        try {
            Authentication result = authenticationManager.authenticate(token);

            // 兜底再清一次 details。认证对象会被授权服务器序列化进
            // oauth2_authorization.attributes，而 CaptchaWebAuthenticationDetails
            // 既没有反序列化入口、也不该把验证码落库（见 CaptchaAuthenticationProvider）。
            if (result instanceof UsernamePasswordAuthenticationToken cleared) {
                cleared.setDetails(null);
            }

            // 重建会话，防 session fixation：认证前后的会话 id 必须不同
            request.getSession().invalidate();
            request.getSession(true);

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(result);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);

            log.info("登录成功：user={}", result.getName());
            // 认证对象里只有用户名与角色；组织、userId 现查——见 IamUserDetails 的说明
            return ResponseEntity.ok(principalOf(userDetailsService.loadPrincipal(result.getName())));
        } catch (AuthenticationException e) {
            // 原因只进日志：区分"验证码错"与"密码错"会给攻击者提供信号
            log.warn("登录失败：user={} 原因={}", body.username(), e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", GENERIC_FAILURE));
        }
    }

    /** CSRF token。SPA 启动时取一次，写进内存后用于后续写请求。 */
    @GetMapping("/csrf")
    public Map<String, Object> csrf(org.springframework.security.web.csrf.CsrfToken csrfToken) {
        return Map.of(
                "headerName", csrfToken.getHeaderName(),
                "parameterName", csrfToken.getParameterName(),
                "token", csrfToken.getToken());
    }

    static Map<String, Object> principalOf(IamUserDetails user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", user.username());
        out.put("userId", user.userId());
        out.put("tenant", user.tenant());
        out.put("displayName", user.displayName());
        out.put("orgId", user.orgId());
        out.put("orgCode", user.orgCode());
        out.put("roles", user.roleCodes());
        // 功能权限。IAM 自己的前端靠它决定导航与按钮是否出现
        out.put("permissions", user.permissionCodes());
        return out;
    }
}

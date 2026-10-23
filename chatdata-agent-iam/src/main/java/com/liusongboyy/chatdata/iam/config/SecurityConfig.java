package com.liusongboyy.chatdata.iam.config;

import com.liusongboyy.chatdata.iam.captcha.CaptchaService;
import com.liusongboyy.chatdata.iam.security.CaptchaAuthenticationProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * 安全配置。
 *
 * <h2>登录是 JSON 接口，不是表单登录</h2>
 *
 * <p>IAM 前端是独立的 Vue 应用（ADR-0008）。因此这里**不配 formLogin**：
 * 未认证的浏览器请求返回 401（前端自己跳登录路由），
 * 登录由 {@code POST /iam/auth/login} 完成。
 *
 * <h2>授权服务器端点</h2>
 *
 * <p>{@code /oauth2/**} 与 {@code /.well-known/**} 由 Spring Authorization Server
 * 自己的过滤链处理（见 {@link AuthorizationServerSecurityConfig}），
 * 这里只把它们放行，避免被本链的 401 提前拦掉。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 会话里的 SecurityContext 仓库。
     *
     * <p>显式声明成 Bean：登录接口与过滤链必须用**同一个**仓库，
     * 否则接口写进去的上下文，过滤链读不到——表现为"登录成功但下一个请求仍是未登录"。
     * Spring Session 在时，它落 Redis。
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public CaptchaAuthenticationProvider captchaAuthenticationProvider(
            CaptchaService captcha, UserDetailsService userDetailsService, PasswordEncoder encoder) {
        return new CaptchaAuthenticationProvider(captcha, userDetailsService, encoder);
    }

    private static CookieCsrfTokenRepository iamCsrfRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName("IAM-XSRF-TOKEN");
        repository.setHeaderName("X-XSRF-TOKEN");
        return repository;
    }

    @Bean
    public SecurityFilterChain defaultSecurityFilterChain(
            HttpSecurity http,
            CaptchaAuthenticationProvider captchaProvider,
            SecurityContextRepository securityContextRepository,
            IamProperties properties) throws Exception {

        // 功能权限关闭时退回到旧的粗粒度判据。这是回滚闸：
        // 没有它，一旦这套判定出问题就只能改代码才能退回去
        boolean fineGrained = properties.getFunctionalPermissions().isEnabled();

        // SPA 读 Cookie 里的 CSRF token 再回填请求头；不用 Xor 处理器，
        // 否则前端拿到的 token 与后端校验的不是同一个
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
                .securityMatcher("/**")
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(
                                    "/login",
                                    "/iam/app/**",
                                    "/captcha/**",
                                    "/iam/auth/login",
                                    "/iam/auth/csrf",
                                    // 令牌换主体：凭据是令牌本身，见 PrincipalController
                                    "/iam/principal",
                                    // 策略接口：服务间调用，凭据是"客户端"不是"用户"，
                                    // 表达不成一条 hasRole 规则，故这里放行、
                                    // 由 PolicyClientAuthInterceptor 在整个前缀上统一把关
                                    "/iam/policy/**",
                                    "/oauth2/**",
                                    "/.well-known/**",
                                    "/error",
                                    "/actuator/health")
                            .permitAll();

                    // 管理面。细粒度判定在**每个控制器方法的 @PreAuthorize 上**
                    // （类级默认为拒绝，见 AdminUserController 的说明），
                    // 这里只要求"已登录"——否则只有某一项权限的人进不来。
                    // 关闭功能权限时退回旧的粗粒度判据
                    if (fineGrained) {
                        auth.requestMatchers("/iam/admin/**").authenticated();
                    } else {
                        auth.requestMatchers("/iam/admin/**").hasRole("chat2bi");
                    }
                    auth.anyRequest().authenticated();
                })
                .csrf(csrf -> csrf
                        // Cookie 名必须与网关的 XSRF-TOKEN 区分：Cookie 不区分端口，
                        // 同名会让两层的 token 互相覆盖，结果是随机 403
                        .csrfTokenRepository(iamCsrfRepository())
                        .csrfTokenRequestHandler(csrfHandler))
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository))
                .logout(logout -> logout
                        // Security 7 移除了 AntPathRequestMatcher，改用 PathPatternRequestMatcher
                        .logoutRequestMatcher(
                                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/iam/auth/logout"))
                        .invalidateHttpSession(true)
                        .deleteCookies("IAM_SESSION")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpStatus.NO_CONTENT.value())))
                .authenticationProvider(captchaProvider)
                // 未认证返回 401，让前端自己决定跳登录页；不在这里重定向
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

        return http.build();
    }
}

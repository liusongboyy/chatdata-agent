package com.liusongboyy.chatdata.ask.config;

import com.liusongboyy.chatdata.iam.client.IamIntrospector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 问数服务的 HTTP 安全配置。
 *
 * <p><strong>为什么必须有这个类：</strong>本模块引入了
 * {@code spring-boot-starter-oauth2-resource-server}（内省走
 * {@link IamIntrospector}），Spring Security 的自动配置会给出一条默认链。
 * 那条链假定每个请求都带令牌，结果是**匿名端点也被 401 挡下**——
 * 表现为「鉴权没生效」，实际是「多了一层没人认识的鉴权」。
 *
 * <p><strong>鉴权模型：</strong>自己承担。所有业务端点都在 Spring Security 层面放行，
 * 由各自的控制器解析令牌、校验角色（见 {@code TokenIdentityResolver}、
 * {@code AdminEngineController.requireAdmin}、{@code AskEngineStreamController}）。
 *
 * <p>放行不等于无鉴权，但**这份白名单必须与端点清单同步维护**：
 * 新增 API 路径忘了加到这里，表现为"所有请求都 403"，而报错里看不出是被安全配置挡的。
 *
 * <p>（历史：本类曾放行 {@code /jimu/**} 与 {@code /chat2bi/**}——那是 JimuChatBI
 * 自带界面的路径，鉴权由厂商拦截器回调我们的实现。厂商依赖已于本次移除，
 * 这两条也随之删除。）
 */
@Configuration
@EnableWebSecurity
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
public class AskSecurityConfig {

    /** 管理面的功能权限开关。关闭时退回旧的粗粒度角色判据（回滚闸）。 */
    private final boolean fineGrained;

    public AskSecurityConfig(
            @org.springframework.beans.factory.annotation.Value(
                    "${chatdata.iam.functional-permissions.enabled:true}") boolean fineGrained) {
        this.fineGrained = fineGrained;
    }

    @Bean
    SecurityFilterChain askSecurityFilterChain(HttpSecurity http, IamIntrospector iamIntrospector)
            throws Exception {
        http
                .authorizeHttpRequests(auth -> {
                    // 自研问数前端的构建产物（Vite 输出到 static/app）。
                    // 同样是静态资源：放行的是**文件**，不是接口——
                    // 它调用的 /api/ask/** 与 /sys/** 各有自己的鉴权。
                    auth.requestMatchers("/app/**").permitAll();

                    // 健康检查。探针没有令牌，也不该需要令牌——
                    // 它要回答的是"进程与关键依赖活着吗"，与"你是谁"无关。
                    // 只暴露 health 本身（见 application.yml 的 management 段），
                    // 不像 /api/** 那样需要内省一次令牌
                    auth.requestMatchers("/actuator/health").permitAll();

                    // 管理面。细粒度判定在**每个接口的 @PreAuthorize 上**
                    // （见 AdminEngineController），这里只要求"已登录"，
                    // 否则只有某一项权限的人进不来。关闭功能权限时退回旧判据
                    if (fineGrained) {
                        auth.requestMatchers("/api/engine/admin/**").authenticated();
                    } else {
                        auth.requestMatchers("/api/engine/admin/**").hasAnyRole("chat2bi", "admin");
                    }

                    // 业务接口：登录即可。**鉴权在资源服务器层完成**，
                    // 令牌由 IAM 签发、ask 内省校验（见 IamIntrospector）。
                    //
                    // ⚠️ 这个类是**白名单**模式，新增 API 路径必须同步加到这里，
                    // 否则表现为"所有请求都 403"，而报错里看不出是被安全配置挡的。
                    auth.requestMatchers("/api/ask/**", "/api/engine/**").authenticated();
                    // 其余一律拒绝。
                    auth.anyRequest().denyAll();
                })
                // 资源服务器：不透明令牌 + 内省。
                // ask 不再自己签发令牌，也不再查用户/角色表。
                .oauth2ResourceServer(oauth -> oauth
                        .opaqueToken(opaque -> opaque.introspector(iamIntrospector)))
                // ask 只被网关代理，CSRF 由网关统一把关（它面向浏览器）。
                // 这里关掉是为了不让内网调用被二次拦截。
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }
}

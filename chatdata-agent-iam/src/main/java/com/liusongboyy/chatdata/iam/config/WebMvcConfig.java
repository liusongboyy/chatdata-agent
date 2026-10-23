package com.liusongboyy.chatdata.iam.config;

import com.liusongboyy.chatdata.iam.policy.PolicyClientAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 装配。
 *
 * <p>目前只做一件事：给 {@code /iam/policy/**} 挂上客户端凭证校验。
 * 为什么不放在 {@code SecurityConfig} 里——见 {@link PolicyClientAuthInterceptor} 的说明：
 * 那条链管的是"用户"，而这个前缀的凭据是"客户端"。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final PolicyClientAuthInterceptor policyClientAuth;

    public WebMvcConfig(PolicyClientAuthInterceptor policyClientAuth) {
        this.policyClientAuth = policyClientAuth;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 用前缀匹配而不是逐个列出路径：以后在这个前缀下加 handler 自动就有闸门，
        // 漏挂的可能性从"每次都要记得"降为"不可能"
        registry.addInterceptor(policyClientAuth).addPathPatterns("/iam/policy/**");
    }
}

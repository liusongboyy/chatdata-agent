package com.liusongboyy.chatdata.iam.config;

import com.liusongboyy.chatdata.iam.serviceauth.ClientCredentialInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 装配。
 *
 * <p>只做一件事：给「服务间调用」的端点挂上客户端凭证校验。
 * 为什么不放在 {@code SecurityConfig} 里——见 {@link ClientCredentialInterceptor} 的说明：
 * 那条链管的是"用户"，而这些前缀的凭据是"客户端"。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 需要客户端凭证的前缀。
     *
     * <p><b>集中在这里列出来是刻意的。</b>拦截器只对注册过的路径生效，
     * 漏掉一个前缀等于那个前缀裸奔——而裸奔的端点在功能上完全正常，
     * 只有安全评审或者被人扫到才会发现。列成常量让"该不该有闸门"
     * 变成一次可复查的清单。
     *
     * <p>{@code /iam/identity/**} 尤其不能漏：它回答「某个 open_id 有没有绑定账号」，
     * 开放出去就是一个可枚举的探测接口。
     */
    private static final String[] SERVICE_PREFIXES = {
            "/iam/policy/**",
            "/iam/identity/**",
    };

    private final ClientCredentialInterceptor clientCredentials;

    public WebMvcConfig(ClientCredentialInterceptor clientCredentials) {
        this.clientCredentials = clientCredentials;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 用前缀匹配而不是逐个列出路径：以后在这个前缀下加 handler 自动就有闸门，
        // 漏挂的可能性从"每次都要记得"降为"不可能"
        registry.addInterceptor(clientCredentials).addPathPatterns(SERVICE_PREFIXES);
    }
}

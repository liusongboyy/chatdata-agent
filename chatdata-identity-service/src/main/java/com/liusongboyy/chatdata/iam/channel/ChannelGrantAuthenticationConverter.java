package com.liusongboyy.chatdata.iam.channel;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.web.authentication.AuthenticationConverter;

import java.util.HashMap;
import java.util.Map;

/**
 * 把 token 端点的 {@code application/x-www-form-urlencoded} 请求解析成
 * {@link ChannelGrantAuthenticationToken}。
 *
 * <h2>为什么返回 null 而不是抛异常</h2>
 *
 * <p>token 端点会依次询问一串 converter「这个请求你能处理吗」。
 * 返回 {@code null} 表示「不是我的」，由后面的 converter 接着试——
 * 抛异常会让授权码、刷新令牌等其他 grant 全部失效。
 *
 * <p>只有 {@code grant_type} 确实是本渠道类型、但参数不全时才抛。
 *
 * <h2>客户端身份从 SecurityContextHolder 取</h2>
 *
 * <p>与 Spring 自带的 {@code OAuth2ClientCredentialsAuthenticationConverter} 一致：
 * 前置的 {@code OAuth2ClientAuthenticationFilter} 已校验 Basic 凭据
 * 并把结果放进 SecurityContext。**不要从 request attribute 取**——
 * 那是另一套机制，属性名不公开且易变。
 */
public class ChannelGrantAuthenticationConverter implements AuthenticationConverter {

    @Override
    public Authentication convert(HttpServletRequest request) {
        String grantType = request.getParameter(OAuth2ParameterNames.GRANT_TYPE);
        if (!ChannelGrantType.VALUE.equals(grantType)) {
            return null;
        }

        String channel = request.getParameter(ChannelGrantType.PARAM_CHANNEL);
        String username = request.getParameter(ChannelGrantType.PARAM_USER);
        if (isBlank(channel) || isBlank(username)) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
        }

        Authentication clientPrincipal = SecurityContextHolder.getContext().getAuthentication();

        Map<String, Object> additional = new HashMap<>();
        String scope = request.getParameter(OAuth2ParameterNames.SCOPE);
        if (scope != null) {
            additional.put(OAuth2ParameterNames.SCOPE, scope);
        }
        return new ChannelGrantAuthenticationToken(channel.trim(), username.trim(),
                clientPrincipal, additional);
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}

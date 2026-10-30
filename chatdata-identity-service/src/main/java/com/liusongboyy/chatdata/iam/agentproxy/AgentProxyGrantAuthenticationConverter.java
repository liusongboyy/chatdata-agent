package com.liusongboyy.chatdata.iam.agentproxy;

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
 * 把 token 端点的表单请求解析成 {@link AgentProxyGrantAuthenticationToken}。
 *
 * <p>返回 {@code null} 表示「不是我的」——由后面的 converter 接着试。
 * 抛异常会打断整条 converter 链，让授权码、刷新令牌一起失效。
 *
 * <p>客户端身份从 {@code SecurityContextHolder} 取：前置的
 * {@code OAuth2ClientAuthenticationFilter} 已校验 Basic 凭据并放进去。
 */
public class AgentProxyGrantAuthenticationConverter implements AuthenticationConverter {

    @Override
    public Authentication convert(HttpServletRequest request) {
        String grantType = request.getParameter(OAuth2ParameterNames.GRANT_TYPE);
        if (!AgentProxyGrantType.VALUE.equals(grantType)) {
            return null;
        }

        String agentClient = request.getParameter(AgentProxyGrantType.PARAM_AGENT_CLIENT);
        String proxyUser = request.getParameter(AgentProxyGrantType.PARAM_USER);
        if (isBlank(agentClient) || isBlank(proxyUser)) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
        }

        Authentication clientPrincipal = SecurityContextHolder.getContext().getAuthentication();

        Map<String, Object> additional = new HashMap<>();
        String scope = request.getParameter(OAuth2ParameterNames.SCOPE);
        if (scope != null) {
            additional.put(OAuth2ParameterNames.SCOPE, scope);
        }
        return new AgentProxyGrantAuthenticationToken(
                agentClient.trim(), proxyUser.trim(), clientPrincipal, additional);
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}

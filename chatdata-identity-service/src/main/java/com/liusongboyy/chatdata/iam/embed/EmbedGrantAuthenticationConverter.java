package com.liusongboyy.chatdata.iam.embed;

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
 * 解析 embed grant 的 token 请求。
 *
 * <p>与 channel 的 converter 同构：不是本 grant 就返回 {@code null}
 * （让后面的 converter 接着试），是本 grant 但参数不全才抛。
 */
public class EmbedGrantAuthenticationConverter implements AuthenticationConverter {

    @Override
    public Authentication convert(HttpServletRequest request) {
        String grantType = request.getParameter(OAuth2ParameterNames.GRANT_TYPE);
        if (!EmbedGrantType.VALUE.equals(grantType)) {
            return null;
        }

        String userRef = request.getParameter(EmbedGrantType.PARAM_USER_REF);
        String assistant = request.getParameter(EmbedGrantType.PARAM_ASSISTANT);
        if (isBlank(userRef) || isBlank(assistant)) {
            // assistant 必填是刻意的：不填就等于"宿主可以替用户问任何域"
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
        }

        Authentication clientPrincipal = SecurityContextHolder.getContext().getAuthentication();

        Map<String, Object> additional = new HashMap<>();
        String scope = request.getParameter(OAuth2ParameterNames.SCOPE);
        if (scope != null) {
            additional.put(OAuth2ParameterNames.SCOPE, scope);
        }
        return new EmbedGrantAuthenticationToken(userRef.trim(), assistant.trim(),
                clientPrincipal, additional);
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}

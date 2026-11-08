package com.liusongboyy.chatdata.iam.embed;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;

import java.util.Map;

/**
 * embed grant 的认证请求。
 *
 * @param userRef   宿主系统里的用户标识
 * @param assistant 限定的助理 id
 */
public class EmbedGrantAuthenticationToken extends OAuth2AuthorizationGrantAuthenticationToken {

    private final String userRef;
    private final String assistant;

    public EmbedGrantAuthenticationToken(String userRef, String assistant,
                                         Authentication clientPrincipal,
                                         Map<String, Object> additionalParameters) {
        // OAuth2AuthorizationGrantAuthenticationToken 的构造器要 AuthorizationGrantType 对象，
        // 不是字符串——传字符串会编译不过
        super(new org.springframework.security.oauth2.core.AuthorizationGrantType(
                EmbedGrantType.VALUE), clientPrincipal, additionalParameters);
        this.userRef = userRef;
        this.assistant = assistant;
    }

    public String userRef() {
        return userRef;
    }

    public String assistant() {
        return assistant;
    }
}

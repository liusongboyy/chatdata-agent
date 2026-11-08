package com.liusongboyy.chatdata.iam.embed;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;

/**
 * 取已认证的客户端身份。
 *
 * <p>与 {@code ChannelGrantAuthenticationProvider} 里那个私有嵌套类等价。
 * **没有提成公共工具**：两个类各有一份 6 行的实现，比引入一个
 * "认证工具类"更不容易出错——那种工具类会长出第二个、第三个方法，
 * 而每加一个都要判断"它对所有 grant 都成立吗"。
 *
 * <p>真正该共用的东西（如 scope 校验）也不该塞在这里，因为
 * embed 与 channel 的 scope 语义不同：前者限助理，后者限渠道。
 */
final class EmbedClientAuth {

    private EmbedClientAuth() {
    }

    static OAuth2ClientAuthenticationToken getAuthenticatedClient(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof OAuth2ClientAuthenticationToken client && client.isAuthenticated()) {
            return client;
        }
        throw new OAuth2AuthenticationException(new OAuth2Error(
                OAuth2ErrorCodes.INVALID_CLIENT, "客户端认证失败",
                "https://datatracker.ietf.org/doc/html/rfc6749#section-5.2"));
    }
}

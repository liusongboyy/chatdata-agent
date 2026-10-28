package com.liusongboyy.chatdata.iam.channel;

import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;

import java.util.Map;

/**
 * 渠道换令牌的认证请求。
 *
 * <p>继承 {@link OAuth2AuthorizationGrantAuthenticationToken}，它已经带了
 * 「客户端身份 + 授权类型 + 额外参数」三样，正好够用——不自己从零实现
 * {@code Authentication}，那会漏掉 {@code AbstractAuthenticationToken} 的细节。
 *
 * <p>{@code authenticated=false}：本对象表示"请求还没被批准"，
 * 由 {@code ChannelGrantAuthenticationProvider} 校验后换成
 * {@code OAuth2AccessTokenAuthenticationToken}。
 */
public class ChannelGrantAuthenticationToken extends OAuth2AuthorizationGrantAuthenticationToken {

    private final String channel;

    private final String username;

    public ChannelGrantAuthenticationToken(String channel,
                                           String username,
                                           Authentication clientPrincipal,
                                           @Nullable Map<String, Object> additionalParameters) {
        super(ChannelGrantType.CHANNEL, clientPrincipal, additionalParameters);
        this.channel = channel;
        this.username = username;
    }

    /** 渠道标识，如 {@code feishu}。与 {@code iam_user.external_provider} 同源。 */
    public String getChannel() {
        return channel;
    }

    /** 目标用户的 IAM 用户名。 */
    public String getUsername() {
        return username;
    }
}

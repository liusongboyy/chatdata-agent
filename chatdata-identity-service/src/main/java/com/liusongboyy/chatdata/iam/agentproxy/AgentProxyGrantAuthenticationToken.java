package com.liusongboyy.chatdata.iam.agentproxy;

import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;

import java.util.Map;

/**
 * Agent 代理换令牌的认证请求。
 *
 * <p>与 {@code ChannelGrantAuthenticationToken} 同构，但携带的语义不同：
 * 渠道带的是「哪个渠道 + 哪个用户」，这里带的是「哪个 agent + 被代理的用户」。
 *
 * <p>{@code authenticated=false}：表示"请求还没被批准"，由
 * {@link AgentProxyAuthenticationProvider} 校验后换成
 * {@code OAuth2AccessTokenAuthenticationToken}。
 */
public class AgentProxyGrantAuthenticationToken extends OAuth2AuthorizationGrantAuthenticationToken {

    private final String agentClient;

    private final String proxyUser;

    public AgentProxyGrantAuthenticationToken(String agentClient,
                                              String proxyUser,
                                              Authentication clientPrincipal,
                                              @Nullable Map<String, Object> additionalParameters) {
        super(AgentProxyGrantType.AGENT_PROXY, clientPrincipal, additionalParameters);
        this.agentClient = agentClient;
        this.proxyUser = proxyUser;
    }

    /** 发起代理的 agent 标识，与 A2A 的 {@code X-Agent-Client} 一致。 */
    public String getAgentClient() {
        return agentClient;
    }

    /** 被代理的用户。绑定目标只来自授权表，不由调用方任意指定。 */
    public String getProxyUser() {
        return proxyUser;
    }
}

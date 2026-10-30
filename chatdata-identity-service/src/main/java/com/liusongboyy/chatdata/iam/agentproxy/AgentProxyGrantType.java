package com.liusongboyy.chatdata.iam.agentproxy;

import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * Agent 代理授权类型。
 *
 * <h2>为什么不复用渠道的 grant</h2>
 *
 * <p>渠道的 {@code urn:chatdata:...:channel} 判的是
 * {@code iam_user.external_id}——「这个飞书号绑定的是谁」。
 * 而 agent 代理判的是 {@code iam_agent_grant}——「这个人同意哪个 agent 代表他」。
 *
 * <p>两者语义不同，塞进同一个 grant 会让判据含糊：出问题时看
 * {@code oauth2_authorization} 里那条记录，分不清是渠道绑定放行的
 * 还是用户授权放行的。而那正是排查越权时第一个要看的东西。
 *
 * <p>代价是 IAM 多一个 {@code AuthenticationProvider}。但它本来就该分开——
 * 两种准入对应两种信任来源。
 */
public final class AgentProxyGrantType {

    /**
     * 授权类型标识。
     *
     * <p>与渠道一样用 {@code urn:} 前缀：grant type 没有统一注册表，
     * 裸名有撞名风险。
     */
    public static final String VALUE = "urn:chatdata:params:oauth:grant-type:agent-proxy";

    public static final AuthorizationGrantType AGENT_PROXY = new AuthorizationGrantType(VALUE);

    /** 请求参数：发起代理的 agent 标识。与 A2A 请求的 X-Agent-Client 一致。 */
    public static final String PARAM_AGENT_CLIENT = "agent_client";

    /** 请求参数：被代理的用户。 */
    public static final String PARAM_USER = "proxy_user";

    private AgentProxyGrantType() {
    }
}

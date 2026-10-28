package com.liusongboyy.chatdata.iam.channel;

import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * Channel 自定义授权类型。
 *
 * <h2>为什么不用标准 RFC 8693 Token Exchange</h2>
 *
 * <p>Spring Authorization Server 7.1.0 内置了 {@code OAuth2TokenExchangeAuthenticationProvider}，
 * 参数齐全（{@code subject_token} / {@code actor_token} / {@code audience}）。
 * 但它的第一件事是：
 *
 * <pre>
 * subjectAuthorization = authorizationService.findByToken(subjectToken, ACCESS_TOKEN);
 * if (subjectAuthorization == null) throw new OAuth2AuthenticationException(INVALID_GRANT);
 * </pre>
 *
 * <p>也就是 {@code subject_token} <strong>必须是一个已存在于 {@code oauth2_authorization}
 * 表里的令牌</strong>。而渠道用户（飞书里发消息的人）可能从没登录过 Web 端，
 * 库里没有他的令牌可换。
 *
 * <p>绕过它只能自己伪造一张 subject_token——那比直接写一个 grant 更复杂，
 * 还会在库里留下一张不该存在的令牌。所以自定义。
 *
 * <h2>它解决的问题</h2>
 *
 * <p>渠道侧拿到的是 {@code open_id}，ask 认的是 IAM 令牌。中间这一步就是本 grant：
 * 渠道用自己的客户端凭据 + 目标用户名，换一张「名义上是他」的令牌。
 * 权限、审计、撤销全部复用现有机制——ask 完全不知道自己面对的是渠道。
 *
 * <h2>安全边界</h2>
 *
 * <p><strong>不是「谁有这个 client secret 就能冒充任何人」。</strong>
 * 校验见 {@link ChannelGrantAuthenticationProvider}：目标用户必须确实绑定了
 * 本渠道的外部身份。这条不写的话，一个泄露的 secret 就是一把万能钥匙。
 */
public final class ChannelGrantType {

    /**
     * 授权类型标识。
     *
     * <p>用 {@code urn:} 前缀而不是裸字符串：grant type 没有统一注册表，
     * 裸名有与将来标准撞名的风险，而 URN 形式的命名空间保证了唯一性。
     */
    public static final String VALUE = "urn:chatdata:params:oauth:grant-type:channel";

    public static final AuthorizationGrantType CHANNEL = new AuthorizationGrantType(VALUE);

    /** 请求参数：渠道标识（如 {@code feishu}）。 */
    public static final String PARAM_CHANNEL = "channel";

    /** 请求参数：目标用户的 IAM 用户名。 */
    public static final String PARAM_USER = "channel_user";

    private ChannelGrantType() {
    }
}

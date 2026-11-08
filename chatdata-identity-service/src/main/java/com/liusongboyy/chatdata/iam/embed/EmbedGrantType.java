package com.liusongboyy.chatdata.iam.embed;

/**
 * 嵌入式问数的 grant type。
 *
 * <h2>它解决什么</h2>
 *
 * <p>宿主系统（CRM、工单系统…）要在自己的页面里嵌一个「问一句」，
 * 需要替<b>它的用户</b>拿一张令牌交给嵌入组件。现有的三种令牌来源都不适用：
 *
 * <table>
 *   <tr><th>来源</th><th>为什么不适用</th></tr>
 *   <tr><td>网关 BFF 会话</td><td>Cookie 同源，只对第一方页面有效</td></tr>
 *   <tr><td>{@code channel} grant</td><td>判据是「渠道账号 ↔ 本系统账号的<b>持久绑定</b>」，
 *       而 embed 是「这次请求是张三发的」，不需要张三事先绑定过</td></tr>
 *   <tr><td>{@code agent-proxy} grant</td><td>判据是 {@code iam_agent_grant}
 *       ——「这个人同意哪个 agent 代表他」。embed 的同意方是宿主系统，不是用户</td></tr>
 * </table>
 *
 * <p>三者语义不同。塞进同一个 grant 会让判据含糊——正是
 * {@code AgentProxyGrantType} 注释里说的那个问题。
 *
 * <h2>与 channel grant 的关键区别</h2>
 *
 * <table>
 *   <tr><th></th><th>channel</th><th>embed</th></tr>
 *   <tr><td>用户身份</td><td>从 {@code open_id} 反查绑定</td><td>宿主给的 {@code user_ref}</td></tr>
 *   <tr><td>权限范围</td><td>该用户的全部权限</td><td><b>限一个助理</b></td></tr>
 *   <tr><td>有效期</td><td>默认 30 分钟</td><td><b>5 分钟</b>——只用来换一次会话</td></tr>
 *   <tr><td>refresh</td><td>不签发</td><td>不签发</td></tr>
 * </table>
 */
public final class EmbedGrantType {

    /**
     * grant type 标识。
     *
     * <p>与 channel / agent-proxy 一样用 {@code urn:} 前缀：grant type 没有统一注册表，
     * 自定义值必须避免与标准值冲突。
     */
    public static final String VALUE = "urn:chatdata:params:oauth:grant-type:embed";

    /** 宿主系统里这个用户的标识。必须能映射到本系统账号。 */
    public static final String PARAM_USER_REF = "user_ref";

    /**
     * 限定的助理 id。
     *
     * <p><b>必填，且不可为空。</b>不填就等于「宿主可以替这个用户问任何域」，
     * 而宿主系统通常只该看它自己那个业务域的数据。
     */
    public static final String PARAM_ASSISTANT = "assistant";

    private EmbedGrantType() {
    }
}

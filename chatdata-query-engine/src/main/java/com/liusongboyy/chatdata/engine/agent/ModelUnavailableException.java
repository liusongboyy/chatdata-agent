package com.liusongboyy.chatdata.engine.agent;

/**
 * 模型服务**不可用**：供应商那边拒了请求，而不是我们配置错了、也不是模型答得不对。
 *
 * <h2>它和另外两个的区别</h2>
 *
 * <table>
 *   <tr><th>类型</th><th>含义</th><th>用户该做什么</th></tr>
 *   <tr><td>{@link EngineNotConfiguredException}</td><td>模型压根没建起来（缺 api-key）</td>
 *       <td>找管理员配密钥</td></tr>
 *   <tr><td>{@link DslGenerationException}</td><td>模型答得不对（返回空、不是 JSON）</td>
 *       <td>换个问法，或重试</td></tr>
 *   <tr><td><b>本类</b></td><td>模型在、配置对，但供应商拒绝了请求</td>
 *       <td><b>等一会儿</b>，或找管理员换模型</td></tr>
 * </table>
 *
 * <h2>为什么必须单独一个类型（真实教训）</h2>
 *
 * <p>2026-11 火山方舟的 CodingPlan 订阅过期，所有问数都失败。而用户看到的是
 * 「处理失败，请稍后重试」——那句话让人以为是我们系统坏了，排查时先去看
 * 应用日志、看数据库、看权限，花了不少时间才在日志里翻到真正的
 * {@code InvalidSubscription}。
 *
 * <p>「稍后重试」在这里也**不算误导**（订阅续上就真的好了），但它缺了
 * 最关键的一句：**这不是你的问题，也不是我们代码的问题**。
 *
 * <h2>可重试与不可重试</h2>
 *
 * <p>{@link #retryable()} 区分两类：
 *
 * <ul>
 *   <li><b>可重试</b>：限流（429）、供应商临时故障（5xx）、超时——
 *       用户等一会儿再来，或我们自动重试一次，可能就好了</li>
 *   <li><b>不可重试</b>：密钥无效（401）、订阅过期、模型不存在（404）——
 *       重试一万次也不会成功，必须有人去改配置</li>
 * </ul>
 *
 * <p>两者给用户的提示不同：前者「请稍后重试」，后者「请联系管理员」。
 */
public class ModelUnavailableException extends RuntimeException {

    /** 供应商的错误码，如 {@code InvalidSubscription}、{@code 401}。可为 null。 */
    private final String providerCode;

    /**
     * 重试有没有意义。
     *
     * <p>{@code false} 时**不要**在界面上说"稍后重试"——那是让人白等。
     */
    private final boolean retryable;

    public ModelUnavailableException(String message, String providerCode,
                                     boolean retryable, Throwable cause) {
        super(message, cause);
        this.providerCode = providerCode;
        this.retryable = retryable;
    }

    public String providerCode() {
        return providerCode;
    }

    public boolean retryable() {
        return retryable;
    }
}

package com.liusongboyy.chatdata.engine.agent;

/**
 * 引擎**还没配置好**，问数链路根本起不来。
 *
 * <h2>与 {@link DslGenerationException} 的区别</h2>
 *
 * <p>后者是"模型答得不对"——模型在、有响应、只是内容不合预期，属于**运行时**问题，
 * 换个问法或重试可能就好了。这个是"模型压根没被创建出来"（缺 api-key 之类），
 * 属于**服务端配置**问题：**重试一万次也不会成功**。
 *
 * <h2>为什么必须单独一个类型</h2>
 *
 * <p>两者的用户提示必须不同。混进同一个兜底分支的话，用户看到的是
 * "处理失败，请稍后重试"——而"稍后重试"在配置缺失时是**主动误导**：
 * 用户会反复重试，运维永远收不到"去配密钥"这个真实信号。
 *
 * <p>它同时也是审计要单独记的一类：配置问题持续出现，说明环境没起对，
 * 与"模型偶发抽风"是完全不同的两件事。
 */
public class EngineNotConfiguredException extends RuntimeException {

    public EngineNotConfiguredException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.liusongboyy.chatdata.engine.agent;

/**
 * DSL 生成失败：模型没返回内容、返回的不是合法 JSON、或内容为空。
 *
 * <p>与"DSL 语义有问题"（如丢了限定词）区分开——后者由渲染阶段的
 * {@code SemanticException} 报出。这里只覆盖**生成过程本身**的失败。
 */
public class DslGenerationException extends RuntimeException {

    public DslGenerationException(String message) {
        super(message);
    }

    public DslGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}

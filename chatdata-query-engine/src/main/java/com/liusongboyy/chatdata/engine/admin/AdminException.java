package com.liusongboyy.chatdata.engine.admin;

/**
 * 管理操作的失败。
 *
 * <p>与"程序出错"区分开：这是**用户输入有问题**，消息要能直接展示给管理员看。
 * 故它不继承 {@code IllegalStateException} 之类，而是单列一个类型，
 * 让控制器能明确地把它转成 4xx 而不是 500。
 */
public class AdminException extends RuntimeException {

    public AdminException(String message) {
        super(message);
    }

    public AdminException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.liusongboyy.chatdata.engine.feedback;

/**
 * 反馈链路里**可解释的失败**（输入不合法、状态不允许、SQL 不合规……）。
 *
 * <p>控制器把它映射成 400：这些话是写给提交者/管理员看的，可以直接透出。
 * 其它异常一律 500 并给通用提示——不把内部细节漏给前端。
 */
public class FeedbackException extends RuntimeException {

    public FeedbackException(String message) {
        super(message);
    }

    public FeedbackException(String message, Throwable cause) {
        super(message, cause);
    }
}

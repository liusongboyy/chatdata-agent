package com.liusongboyy.chatdata.engine.feedback;

/**
 * 反馈类型。
 *
 * <p>正向反馈也要收：没有它，你只知道"错在哪"，不知道"哪些改动是对的"，
 * 更没法算错误率的分母。
 */
public enum FeedbackType {

    /** 答案有帮助。不产生范例。 */
    HELPFUL,

    /** 答案不对，但用户没有给出正确 SQL。 */
    WRONG_ANSWER,

    /** 答案不对，并给出了修正后的 SQL。**审核通过后会写入范例库**。 */
    SQL_CORRECTION;

    public static FeedbackType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new FeedbackException("反馈类型不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new FeedbackException("未知的反馈类型：" + raw);
        }
    }
}

package com.liusongboyy.chatdata.engine.feedback;

/**
 * 反馈的审核状态。
 *
 * <p>只有 {@link #PENDING} 可以被审核；重复审核要被拒绝而不是静默覆盖——
 * 那会让"谁在什么时候把这条放进了范例库"变得无法解释。
 */
public enum FeedbackStatus {
    PENDING,
    APPROVED,
    REJECTED;

    public static FeedbackStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new FeedbackException("状态不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new FeedbackException("未知的状态：" + raw);
        }
    }
}

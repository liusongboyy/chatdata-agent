package com.liusongboyy.chatdata.engine.agent;

import com.liusongboyy.chatdata.engine.permission.PermissionDecision;

/**
 * 问数被拒绝。
 *
 * <p><strong>刻意用异常而不是返回值。</strong>调用方拿到一个"被拒绝"的返回值后
 * 很容易忘掉判空、当成正常继续走；异常让这条路径必须被处理。
 *
 * <p>这也是厂商那个坑的反面做法——ADR-0002 实测：厂商对"被拒绝"返回 **HTTP 200**，
 * 响应体是 {@code {"blocked":true}}，导致审计里「被拒绝」与「查了但无数据」无法区分。
 * 这里带上机器可读的 {@link #reason()}，审计可据此单独记一类。
 */
public class AskDeniedException extends RuntimeException {

    private final PermissionDecision.Reason reason;

    public AskDeniedException(PermissionDecision.Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * 拒绝原因。
     *
     * <p>注意 {@code NO_ROW_SCOPE} 这个取值在这里被**复用**来表示"没听懂问题"——
     * 语义上偏窄。等澄清追问（FR-5.6）落地时应当单列一个
     * {@code NOT_UNDERSTOOD}，届时需同步改审计的分类。
     */
    public PermissionDecision.Reason reason() {
        return reason;
    }
}

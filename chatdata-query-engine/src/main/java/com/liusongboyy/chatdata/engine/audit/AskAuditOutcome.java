package com.liusongboyy.chatdata.engine.audit;

/**
 * 一次问数的结局。
 *
 * <p><strong>BLOCKED 必须与 FAILURE 分开。</strong>ADR-0002 记录过厂商的坑：
 * 它对"被拒绝"返回 HTTP 200 + {@code {"blocked":true}}，导致审计里
 * 「被拒绝」与「查了但没数据」无法区分——而这两件事的处置完全不同：
 * 前者是权限问题，后者是数据或口径问题。
 */
public enum AskAuditOutcome {

    /** 正常完成（**不代表有数据**，可能返回 0 行）。 */
    SUCCESS,

    /** 执行失败：SQL 报错、连接不可用、超时。 */
    FAILURE,

    /** 被权限拦下。与 FAILURE 分开记，见类注释。 */
    BLOCKED
}

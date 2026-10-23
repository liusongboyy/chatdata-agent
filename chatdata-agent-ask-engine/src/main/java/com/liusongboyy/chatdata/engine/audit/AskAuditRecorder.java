package com.liusongboyy.chatdata.engine.audit;

/**
 * 审计记录器。
 *
 * <p>抽成接口的理由是**失败隔离**：审计写失败**不该让问数失败**。
 * 用户问到了数据，却因为审计表写不进去而拿不到结果，这个取舍是错的——
 * 审计的价值在于事后可查，不在于阻断当下。
 *
 * <p>故实现方应当自己吞掉异常并记日志，调用方不必 try/catch。
 * 这一条写在接口上，因为它是**契约的一部分**，不是某个实现的选择。
 */
public interface AskAuditRecorder {

    /**
     * 记录一条。
     *
     * <p><strong>实现方不得抛出异常。</strong>写失败时记日志即可——
     * 见接口说明里的取舍。
     */
    void record(AskAuditRecord record);

    /** 什么都不做的实现。用于测试，以及审计被显式关闭时。 */
    AskAuditRecorder NOOP = record -> {
        // 故意留空
    };
}

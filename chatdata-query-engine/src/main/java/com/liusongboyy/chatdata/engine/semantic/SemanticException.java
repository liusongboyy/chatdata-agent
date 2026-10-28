package com.liusongboyy.chatdata.engine.semantic;

/**
 * 语义层的错误。
 *
 * <p>带机器可读的 {@link Reason}，供上层决定怎么处理——是拒绝本次问数、
 * 还是提示管理员修配置。**不要靠 message 文本做判断。**
 */
public class SemanticException extends RuntimeException {

    private final Reason reason;

    public SemanticException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        /** 字段在域内找不到 */
        FIELD_NOT_FOUND,
        /** 字段在域内多张表都有——配置歧义，**不猜**，直接拒绝 */
        FIELD_AMBIGUOUS,
        /** 字段存在但类型不符（如把指标当维度用） */
        FIELD_KIND_MISMATCH,
        /** 聚合方式不受支持 */
        UNSUPPORTED_AGGREGATION,
        /**
         * DSL 里的筛选运算符不受支持（如模型填了中文「在」）。
         *
         * <p>与"字段找不到"分开：那个是**语义建模**的问题（域里没登记），
         * 这个是**模型输出**的问题（写了个我们只认 ASCII 的取值），
         * 而后者可以靠带纠正提示的重试修好。
         */
        UNSUPPORTED_OPERATOR,
        /**
         * 筛选取值的类型不对（如给日期列配了一个非日期的值）。
         *
         * <p>与"运算符不认识"分开：那个是取值本身不合法，这个是**取值与列类型不匹配**，
         * 两者的纠正提示不同（一个是"换个运算符"，一个是"时间条件请写进 timeRange"）。
         */
        INVALID_FILTER_VALUE,
        /** 注册表配置有问题（如 SQL 虚拟表没有 SQL 文本） */
        INVALID_TABLE_DEFINITION,
        /**
         * 用到的几张表在域内连不起来——没配关联，或必须反向遍历 LEFT JOIN 才能连通。
         * **不猜一个 join 条件**：猜错会返回看起来合理但错误的结果。
         */
        TABLES_NOT_CONNECTED
    }
}

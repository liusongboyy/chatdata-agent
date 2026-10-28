package com.liusongboyy.chatdata.engine.semantic;

import java.util.Locale;

/**
 * 列的聚合方式。
 *
 * <p>对应厂商 {@code columns_json[].agg}。维度列为 {@link #NONE}。
 *
 * <p><strong>未知取值抛异常，不回落 {@code NONE}。</strong>回落会把一个本该聚合的指标
 * 当成维度渲染进 GROUP BY —— 查询能跑通，但结果是错的，而且不报错。
 */
public enum Aggregation {

    /** 不聚合。用于维度列。 */
    NONE(null),

    SUM("SUM"),
    COUNT("COUNT"),
    COUNT_DISTINCT("COUNT(DISTINCT %s)"),
    AVG("AVG"),
    MAX("MAX"),
    MIN("MIN");

    private final String template;

    Aggregation(String template) {
        this.template = template;
    }

    public boolean isAggregating() {
        return this != NONE;
    }

    /**
     * 把列表达式包进聚合函数。
     *
     * @param columnExpression 已引用过的列表达式，如 {@code "p"."premium"}
     */
    public String wrap(String columnExpression) {
        if (this == NONE) {
            return columnExpression;
        }
        if (this == COUNT_DISTINCT) {
            return "COUNT(DISTINCT " + columnExpression + ")";
        }
        return template + "(" + columnExpression + ")";
    }

    /**
     * 解析配置里的取值。
     *
     * <p>接受空值（视为 {@link #NONE}）与常见的几种写法，其余抛异常。
     */
    public static Aggregation parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        String k = raw.trim().toUpperCase(Locale.ROOT).replace(" ", "_");
        return switch (k) {
            case "NONE", "无", "不聚合" -> NONE;
            case "SUM", "求和" -> SUM;
            case "COUNT", "计数" -> COUNT;
            case "COUNT_DISTINCT", "COUNTDISTINCT", "去重计数" -> COUNT_DISTINCT;
            case "AVG", "AVERAGE", "平均" -> AVG;
            case "MAX", "最大" -> MAX;
            case "MIN", "最小" -> MIN;
            default -> throw new SemanticException(
                    SemanticException.Reason.UNSUPPORTED_AGGREGATION,
                    "不支持的聚合方式：" + raw + "。支持的取值见 Aggregation 枚举");
        };
    }
}

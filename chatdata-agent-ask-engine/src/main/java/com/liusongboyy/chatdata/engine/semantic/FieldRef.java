package com.liusongboyy.chatdata.engine.semantic;

/**
 * 一次字段解析的结果：业务名 → 具体某张注册表上的某一列。
 *
 * <p><strong>{@code tableAlias} 是渲染器与权限模块之间最关键的一个字段。</strong>
 * 行规则配的是 {@code field: region}，而生成的 SQL 可能是多表查询——
 * 不带表限定的 {@code region = ?} 会歧义，或者注到错误的表上。
 * 解析出别名后，谓词才能写成 {@code "p"."region" = ?}。
 *
 * @param tableAlias 该列所属注册表在本次查询中的别名
 * @param column     物理列名（元数据原文，渲染时原样加引号）
 * @param agg        聚合方式；维度为 {@link Aggregation#NONE}
 * @param kind       指标还是维度
 */
public record FieldRef(String tableAlias, String column, Aggregation agg, FieldKind kind) {

    public FieldRef {
        if (tableAlias == null || tableAlias.isBlank() || column == null || column.isBlank()) {
            throw new IllegalArgumentException("表别名与列名都不能为空");
        }
        agg = agg == null ? Aggregation.NONE : agg;
    }

    /** 是否是需要聚合的指标。 */
    public boolean isMetric() {
        return kind == FieldKind.METRIC;
    }
}

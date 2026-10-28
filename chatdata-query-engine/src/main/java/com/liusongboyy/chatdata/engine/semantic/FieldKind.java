package com.liusongboyy.chatdata.engine.semantic;

/**
 * 字段在问数里的角色。
 *
 * <p>分开是有意义的：**同一个物理列可能既能当指标又能当维度**（例如 {@code premium}
 * 既可以求 SUM，也可以按它分桶），但**在 DSL 里它们出现在不同位置**——
 * 指标进 SELECT 的聚合表达式，维度进 GROUP BY。渲染器必须知道要按哪种语义处理，
 * 不能靠"有没有聚合函数"反推。
 */
public enum FieldKind {

    /** 指标：出现在 DSL 的 {@code metrics}，渲染进 SELECT 的聚合表达式。 */
    METRIC,

    /** 维度：出现在 DSL 的 {@code dimensions} 或筛选条件，渲染进 GROUP BY / WHERE。 */
    DIMENSION
}

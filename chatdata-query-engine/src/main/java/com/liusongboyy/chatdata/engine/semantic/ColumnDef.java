package com.liusongboyy.chatdata.engine.semantic;

import java.util.Locale;

/**
 * 注册表里的一列——**或一个派生指标**。
 *
 * <p>对应厂商 {@code vendor_table.columns_json} 的一项，字段名保持一致以便照搬配置。
 *
 * @param col     物理列名。**必须与 {@code DatabaseMetaData} 报告的原文一致**——
 *                渲染器会原样加引号引用它，差一个字符就查不到列。
 *                派生指标没有物理列，此字段为空
 * @param type    数据库类型名，仅用于展示与粗校验
 * @param agg     聚合方式；维度列为 {@link Aggregation#NONE}
 * @param alias   业务名（如「保费」「区域」）。**这是问数时用户与模型使用的名字**；
 *                为空时退化为用 {@code col} 匹配
 * @param enabled 是否对 LLM 可见。关闭的列不参与解析，也不进 prompt
 * @param derived 派生指标定义；普通列为 null。见 {@link DerivedMetric}
 * @param condition 统计条件：只把满足条件的行计入这一列；无条件的列为 null。
 *                  见 {@link MetricCondition}。与 {@code derived} 互斥
 * @param description 列的业务含义说明，由 {@code ColumnProfiler} 根据真实取值生成。
 *                  <b>会进 prompt</b>——BIRD 的实测是"有列注释 73.8% vs 无 66.5%"，
 *                  而自动生成的注释比人工写的还好（72.2% vs 71.0%）。
 *                  为空时 prompt 里不出现该列的描述，行为与未加此字段时一致
 * @param sampleValues 取值样例（最多几个），同样进 prompt。
 *                  对"状态"这类字段，让模型看到 {@code OPEN/CLOSED} 比看到"状态"两个字有用得多
 */
public record ColumnDef(String col, String type, Aggregation agg, String alias,
                        boolean enabled, DerivedMetric derived, MetricCondition condition,
                        String description, java.util.List<String> sampleValues) {

    /** 普通列：没有派生定义、没有统计条件、没有描述。 */
    public ColumnDef(String col, String type, Aggregation agg, String alias, boolean enabled) {
        this(col, type, agg, alias, enabled, null, null, null, null);
    }

    /** 普通列或派生指标，不带统计条件。 */
    public ColumnDef(String col, String type, Aggregation agg, String alias,
                     boolean enabled, DerivedMetric derived) {
        this(col, type, agg, alias, enabled, derived, null, null, null);
    }

    /** 带统计条件的列（不带描述）。 */
    public ColumnDef(String col, String type, Aggregation agg, String alias,
                     boolean enabled, DerivedMetric derived, MetricCondition condition) {
        this(col, type, agg, alias, enabled, derived, condition, null, null);
    }

    public ColumnDef {
        boolean isDerived = derived != null;
        if (!isDerived && (col == null || col.isBlank())) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "列定义缺少物理列名");
        }
        if (isDerived) {
            // 派生指标靠名字被引用，没有名字就永远匹配不上——那是"配了但不生效"的静默失效
            if (alias == null || alias.isBlank()) {
                throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                        "派生指标必须有业务名（alias）");
            }
            // 派生指标自带计算，不能再叠一层聚合；叠了会渲染成 SUM(SUM(a)/SUM(b)) 这种错 SQL
            if (agg != null && agg.isAggregating()) {
                throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                        "派生指标「" + alias + "」不能再配置聚合方式，它本身已经是聚合结果");
            }
        }
        agg = agg == null ? Aggregation.NONE : agg;
        description = (description == null || description.isBlank()) ? null : description.trim();
        sampleValues = sampleValues == null ? java.util.List.of() : java.util.List.copyOf(sampleValues);

        if (condition != null) {
            if (isDerived) {
                throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                        "「" + alias + "」不能同时是派生指标和带条件的指标");
            }
            // 条件只对聚合有意义：给维度加条件等于"这一列只显示部分行"，
            // 那会让分组结果少行且不报错
            if (!agg.isAggregating()) {
                throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                        "「" + displayNameOf(alias, col) + "」配置了统计条件，但没有聚合方式——"
                                + "条件只对指标有意义");
            }
        }
    }

    private static String displayNameOf(String alias, String col) {
        return (alias == null || alias.isBlank()) ? col : alias;
    }

    /**
     * 是否可作为指标引用。
     *
     * <p>派生指标也算——它本身就产出可聚合的值（或在渲染器里被展开成聚合表达式）。
     */
    public boolean isMetric() {
        return derived != null || agg.isAggregating();
    }

    /**
     * 名字是否命中本列。
     *
     * <p>业务名与物理列名**都接受**——配置里 alias 可能为空，用户也可能直接写列名。
     * 比对忽略大小写与前后空白，因为这两个差异几乎总是输入习惯而非语义差异。
     */
    public boolean matches(String name) {
        if (name == null) {
            return false;
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (alias != null && !alias.isBlank() && alias.trim().toLowerCase(Locale.ROOT).equals(n)) {
            return true;
        }
        return col != null && col.trim().toLowerCase(Locale.ROOT).equals(n);
    }

    /** 展示用名字：优先业务名。 */
    public String displayName() {
        return (alias == null || alias.isBlank()) ? col : alias;
    }
}

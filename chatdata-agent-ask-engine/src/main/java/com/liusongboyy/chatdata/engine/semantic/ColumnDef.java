package com.liusongboyy.chatdata.engine.semantic;

import java.util.Locale;

/**
 * 注册表里的一列。
 *
 * <p>对应厂商 {@code chat2bi_table.columns_json} 的一项，字段名保持一致以便照搬配置。
 *
 * @param col     物理列名。**必须与 {@code DatabaseMetaData} 报告的原文一致**——
 *                渲染器会原样加引号引用它，差一个字符就查不到列
 * @param type    数据库类型名，仅用于展示与粗校验
 * @param agg     聚合方式；维度列为 {@link Aggregation#NONE}
 * @param alias   业务名（如「保费」「区域」）。**这是问数时用户与模型使用的名字**；
 *                为空时退化为用 {@code col} 匹配
 * @param enabled 是否对 LLM 可见。关闭的列不参与解析，也不进 prompt
 */
public record ColumnDef(String col, String type, Aggregation agg, String alias, boolean enabled) {

    public ColumnDef {
        if (col == null || col.isBlank()) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "列定义缺少物理列名");
        }
        agg = agg == null ? Aggregation.NONE : agg;
    }

    /** 是否是可用于聚合的指标列。 */
    public boolean isMetric() {
        return agg.isAggregating();
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
        return col.trim().toLowerCase(Locale.ROOT).equals(n);
    }

    /** 展示用名字：优先业务名。 */
    public String displayName() {
        return (alias == null || alias.isBlank()) ? col : alias;
    }
}

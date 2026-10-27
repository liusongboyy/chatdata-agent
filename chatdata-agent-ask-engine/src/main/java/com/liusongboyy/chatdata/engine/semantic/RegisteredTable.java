package com.liusongboyy.chatdata.engine.semantic;

import java.util.List;

/**
 * 语义域内的一张注册表。
 *
 * <p>对应厂商 {@code chat2bi_table}。两种模式：
 * <ul>
 *   <li>{@link Mode#TABLE}——直接引用数据源里的物理表，{@code name} 就是表名</li>
 *   <li>{@link Mode#SQL}——SQL 虚拟表，{@code sqlText} 是它的定义，渲染时当作子查询</li>
 * </ul>
 *
 * @param id      注册表标识
 * @param name    物理表名（{@code mode=TABLE}）或虚拟表标识（{@code mode=SQL}）
 * @param label   显示名
 * @param mode    模式
 * @param sqlText SQL 虚拟表定义（{@code mode=SQL} 时必填）
 * @param enabled 是否对 LLM 可见。关闭的表不进 prompt，也不参与字段解析
 * @param columns 列定义
 * @param baseFilter 默认过滤条件（原始 SQL 片段，如 {@code status = '生效'}）。
 *
 *     <h3>为什么它必须存在</h3>
 *
 *     <p>实测反复出现同一类错：问「2025 年保费合计」，模型自己补上
 *     {@code AND status = '生效'}——它"觉得"过期的保单不算保费。
 *     这个判断不算离谱，但它是**猜的**，而且猜错了没人知道：
 *     SQL 合法、数字看着合理，只是少了三分之一。
 *
 *     <p>更根本的是：**"什么算有效数据"是业务口径，不该由模型每次猜**。
 *     配在这里，渲染器固定拼进 WHERE，模型不必猜、也猜不错。
 *
 *     <h3>为什么是自由 SQL 片段而不是结构化条件</h3>
 *
 *     <p>它只由管理员在建模时填写、**不经模型产出**，因此没有注入面。
 *     用自由片段可以表达 {@code deleted = 0}、{@code status IN ('生效','已过期')}、
 *     {@code amount > 0} 这些形态，而结构化条件要为每种加一个字段。
 */
public record RegisteredTable(
        String id,
        String name,
        String label,
        Mode mode,
        String sqlText,
        boolean enabled,
        List<ColumnDef> columns,
        String baseFilter
) {

    /** 不设默认过滤的表。绝大多数调用点用这个。 */
    public RegisteredTable(String id, String name, String label, Mode mode,
                           String sqlText, boolean enabled, List<ColumnDef> columns) {
        this(id, name, label, mode, sqlText, enabled, columns, null);
    }

    public enum Mode {
        TABLE,
        SQL
    }

    public RegisteredTable {
        if (id == null || id.isBlank()) {
            // id 不能空：SemanticModel 按 id 排序分配别名，为 null 会在排序时 NPE
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "注册表缺少 id");
        }
        if (name == null || name.isBlank()) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "注册表缺少名称");
        }
        mode = mode == null ? Mode.TABLE : mode;
        columns = columns == null ? List.of() : List.copyOf(columns);
        baseFilter = (baseFilter == null || baseFilter.isBlank()) ? null : baseFilter.trim();

        // SQL 虚拟表没有定义就没法渲染——在构造期就挡住，不要留到渲染时才发现
        if (mode == Mode.SQL && (sqlText == null || sqlText.isBlank())) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "SQL 虚拟表「" + name + "」没有 SQL 定义");
        }
    }

    /** 物理表 / 虚拟表在 SQL 里被引用时使用的名字。 */
    public String referenceName() {
        return name;
    }

    /** 是否直接是物理表——决定渲染器写 {@code FROM 表名} 还是 {@code FROM (子查询)}。 */
    public boolean isPhysical() {
        return mode == Mode.TABLE;
    }

    public String displayName() {
        return (label == null || label.isBlank()) ? name : label;
    }
}

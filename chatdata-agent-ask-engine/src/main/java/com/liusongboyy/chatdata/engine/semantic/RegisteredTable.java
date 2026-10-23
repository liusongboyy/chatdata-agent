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
 */
public record RegisteredTable(
        String id,
        String name,
        String label,
        Mode mode,
        String sqlText,
        boolean enabled,
        List<ColumnDef> columns
) {

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

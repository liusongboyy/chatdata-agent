package com.liusongboyy.chatdata.engine.semantic;

/**
 * {@code ask_domain_table} 的一行，**原始投影**。
 *
 * @param id          主键
 * @param domainId    所属域
 * @param name        表名或虚拟表标识
 * @param label       显示名
 * @param mode        TABLE / SQL
 * @param sqlText     SQL 虚拟表定义
 * @param columnsJson 列定义 JSON
 * @param enabled     是否对 LLM 可见
 */
public record DomainTableRow(
        long id,
        long domainId,
        String name,
        String label,
        String mode,
        String sqlText,
        String columnsJson,
        boolean enabled,
        /** 默认过滤条件的原始 SQL 片段；null 表示不过滤。见 {@link RegisteredTable#baseFilter()} */
        String baseFilter
) {

    /** 兼容构造器：没有默认过滤的表（绝大多数）。 */
    public DomainTableRow(long id, long domainId, String name, String label, String mode,
                          String sqlText, String columnsJson, boolean enabled) {
        this(id, domainId, name, label, mode, sqlText, columnsJson, enabled, null);
    }
}

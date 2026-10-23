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
        boolean enabled
) {
}

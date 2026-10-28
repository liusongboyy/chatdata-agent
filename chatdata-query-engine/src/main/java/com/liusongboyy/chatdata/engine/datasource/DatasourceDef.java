package com.liusongboyy.chatdata.engine.datasource;

/**
 * 一行数据源配置，对应 {@code ask_datasource}。
 *
 * <p><strong>不含密码。</strong>密码单独从 Mapper 取，用完即弃——把密码放进这个
 * 会在各处传递的对象里，等于让它出现在日志、异常堆栈、缓存、审计记录的每一个角落。
 *
 * <h2>这个 record 刻意不做配置校验</h2>
 *
 * <p>校验放在 {@code EngineConfigValidator} 里。理由不是"嫌麻烦"，是**失败形态**：
 *
 * <ul>
 *   <li>若在构造器里抛异常，MyBatis 的整条列表查询会失败——
 *       **一行坏数据就炸掉整份启动报告**，运维看不到其它问题，只能修一个重启一次</li>
 *   <li>配置校验的目标恰恰是"一次把所有问题列全"</li>
 * </ul>
 *
 * <p>这跟 {@code ColumnDef} 那类**结构不变量**不同：列没有名字就没有意义，
 * 那是对象自身的问题；而"这个 dbType 我们支不支持"是引擎的能力问题，
 * 属于配置校验的范畴。
 *
 * @param id          主键
 * @param name        名称
 * @param dbType      类型，需落在 {@code SqlDialects} 的注册表里
 * @param driverClass JDBC 驱动类名
 * @param url         JDBC URL
 * @param username    用户名
 * @param enabled     是否启用
 */
public record DatasourceDef(
        long id,
        String name,
        String dbType,
        String driverClass,
        String url,
        String username,
        boolean enabled
) {

    /** 供日志与错误提示使用。**刻意不含 url 与 username**——它们可能带敏感信息。 */
    @Override
    public String toString() {
        return "DatasourceDef[id=" + id + ", name=" + name + ", dbType=" + dbType
                + ", enabled=" + enabled + "]";
    }
}

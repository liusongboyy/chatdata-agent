package com.liusongboyy.chatdata.engine.datasource;

import javax.sql.DataSource;

/**
 * 按数据源 id 取连接池。
 *
 * <p>抽成接口是为了让 {@link SqlExecutor} 可测：它的测试应当只关心
 * "SQL 能不能正确执行、参数有没有错位、只读校验生不生效"，
 * 而不该被"注册表能不能建池"这类与它无关的事情拖住。
 *
 * @see DatasourceRegistry 生产实现
 */
@FunctionalInterface
public interface DatasourceResolver {

    /**
     * 取连接池。
     *
     * @throws DatasourceRegistry.DatasourceUnavailableException 数据源不存在或不可用
     */
    DataSource resolve(String datasourceId);
}

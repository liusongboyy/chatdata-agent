package com.liusongboyy.chatdata.engine.agent;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 按配置构建会话状态存储。
 *
 * <p>做成静态工厂而不是一堆 {@code @Bean}，是为了让"库名怎么定的""裁不裁剪"
 * 这两件有判断的事可以脱离 Spring 被测到——它们出错时的表现是会话串台或上下文暴涨，
 * 都属于不该靠启动一次来发现的类型。
 */
public final class AskSessionStores {

    private AskSessionStores() {
    }

    /**
     * 建存储：MySQL 持久化 + 可选的消息条数裁剪。
     *
     * @param dataSource 业务库数据源。**复用应用自己的库**，不另建库——
     *                   agentscope 默认要一个独立的 {@code agentscope} 库，
     *                   那在只授予单库权限的生产账号上会直接起不来。
     */
    public static AgentStateStore create(DataSource dataSource, AskSessionProperties props) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource 不能为空");
        }

        String database = resolveDatabase(dataSource, props.getDatabase());

        AgentStateStore mysql = new MysqlAgentStateStore(
                dataSource, database, props.getTable(), props.isCreateTable());

        return props.getMaxMessages() > 0
                ? new TrimmingAgentStateStore(mysql, props.getMaxMessages())
                : mysql;
    }

    /**
     * 定库名：配了就用配的，没配就从连接上读当前库。
     *
     * <p>不配默认值（比如写死 {@code jimureport}）是有意的——库名已经由 JDBC URL 表达过一次，
     * 再写一份就是给"改了 URL 忘了改这里"留机会。而读 {@code getCatalog()} 拿到的是
     * **驱动实际连上的那个库**，不会有第二种解释。
     *
     * @throws IllegalStateException 连接不上，或驱动不报告当前库
     */
    static String resolveDatabase(DataSource dataSource, String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }

        try (Connection conn = dataSource.getConnection()) {
            String catalog = conn.getCatalog();
            if (catalog == null || catalog.isBlank()) {
                // 说白了就是"我不知道该往哪个库建表"，这时候猜一个比报错危险
                throw new IllegalStateException(
                        "无法从数据源确定当前库名，请显式配置 chatdata.engine.session.database");
            }
            return catalog;
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "读取数据源当前库名失败，无法初始化会话存储：" + e.getMessage(), e);
        }
    }
}

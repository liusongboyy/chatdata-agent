package com.liusongboyy.chatdata.engine.datasource;

import com.liusongboyy.chatdata.engine.nlsql.RenderedSql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;

/**
 * 执行渲染好的 SQL。
 *
 * <h2>只接受 {@link RenderedSql}，不接受裸 SQL 字符串</h2>
 *
 * <p>这不是洁癖：{@code RenderedSql} 在构造期就断言了「占位符数量 == 参数数量」，
 * 而一个裸字符串没有这个保证。参数错位是这类代码最隐蔽的缺陷——
 * 不报错、不崩溃，只是把值绑到了错误的位置，于是查出来的数字是错的。
 *
 * <h2>三层防护</h2>
 *
 * <ol>
 *   <li><strong>只读</strong>——执行前再校验一次首关键字。渲染器本该只产出 SELECT，
 *       但这是纵深防御：渲染器的一个 bug 不该变成一次写操作</li>
 *   <li><strong>行数上限</strong>——{@code setMaxRows}。大结果集会打爆内存与网络</li>
 *   <li><strong>查询超时</strong>——{@code setQueryTimeout}。慢查询会占住连接直到池耗尽</li>
 * </ol>
 *
 * <p>脱敏不在这里做：脱敏列在渲染阶段就被换成了常量 {@code '***'}，
 * 真实值从未离开数据库。这也是为什么本类不需要知道权限——它拿到什么就执行什么。
 */
public class SqlExecutor {

    private static final Logger log = LoggerFactory.getLogger(SqlExecutor.class);

    private final DatasourceResolver registry;
    private final int maxRows;
    private final int timeoutSeconds;

    public SqlExecutor(DatasourceResolver registry) {
        this(registry, 1000, 30);
    }

    public SqlExecutor(DatasourceResolver registry, int maxRows, int timeoutSeconds) {
        this.registry = registry;
        this.maxRows = maxRows;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 执行并取回结果。
     *
     * @param datasourceId 数据源 id
     * @param sql          渲染结果
     * @throws SqlExecutionException 连接不可用、SQL 被拒、或执行失败
     */
    public QueryResult execute(String datasourceId, RenderedSql sql) {
        String rejection = rejectUnsafe(sql.sql());
        if (rejection != null) {
            // 渲染器本该只产出 SELECT。走到这里说明它有 bug——记 error 而不是 warn
            log.error("拒绝执行非只读 SQL（渲染器缺陷）：{}", sql.sql());
            throw new SqlExecutionException(rejection);
        }

        long t0 = System.currentTimeMillis();
        try (Connection c = registry.resolve(datasourceId).getConnection();
             PreparedStatement ps = c.prepareStatement(sql.sql())) {

            ps.setMaxRows(maxRows);
            ps.setQueryTimeout(timeoutSeconds);
            for (int i = 0; i < sql.params().size(); i++) {
                ps.setObject(i + 1, sql.params().get(i));
            }

            try (ResultSet rs = ps.executeQuery()) {
                QueryResult result = read(rs);
                log.debug("查询完成：{} 行，耗时 {}ms", result.rowCount(), System.currentTimeMillis() - t0);
                return result;
            }
        } catch (DatasourceRegistry.DatasourceUnavailableException e) {
            throw new SqlExecutionException(e.getMessage(), e);
        } catch (Exception e) {
            // 把数据库的报错原样带出来——排查时它是第一现场。
            // 但它可能含表名/列名，上层要决定是否给最终用户看
            throw new SqlExecutionException("查询执行失败：" + e.getMessage(), e);
        }
    }

    private static QueryResult read(ResultSet rs) throws Exception {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();

        List<String> columns = new ArrayList<>(cols);
        for (int i = 1; i <= cols; i++) {
            columns.add(md.getColumnLabel(i));
        }

        List<List<Object>> rows = new ArrayList<>();
        while (rs.next()) {
            List<Object> row = new ArrayList<>(cols);
            for (int i = 1; i <= cols; i++) {
                row.add(rs.getObject(i));
            }
            rows.add(row);
        }
        return new QueryResult(columns, rows);
    }

    /**
     * 只读校验。
     *
     * <p>与 {@code SqlTools.rejectUnsafe} 同源。这里再拦一次不是重复——
     * {@code SqlTools} 面向的是"模型写的 SQL"，而这里面向的是"我们渲染的 SQL"，
     * 两者是**不同的信任来源**，各自都要有闸门。
     *
     * @return null 表示通过
     */
    static String rejectUnsafe(String sql) {
        if (sql == null || sql.isBlank()) {
            return "拒绝执行：SQL 为空";
        }
        String normalized = sql.trim().toLowerCase();
        if (!normalized.startsWith("select") && !normalized.startsWith("with")) {
            return "拒绝执行：只允许 SELECT / WITH 开头的只读查询";
        }
        if (normalized.replaceAll(";\\s*$", "").contains(";")) {
            return "拒绝执行：不允许多语句";
        }
        return null;
    }

    /** 执行失败。消息里可能含数据库的原始报错，上层决定是否透出给用户。 */
    public static class SqlExecutionException extends RuntimeException {
        public SqlExecutionException(String message) {
            super(message);
        }

        public SqlExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

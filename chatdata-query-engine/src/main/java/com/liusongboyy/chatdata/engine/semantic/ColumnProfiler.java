package com.liusongboyy.chatdata.engine.semantic;

import com.liusongboyy.chatdata.engine.datasource.QueryResult;
import com.liusongboyy.chatdata.engine.datasource.SqlExecutor;
import com.liusongboyy.chatdata.engine.nlsql.RenderedSql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 给已注册的列做真实取值画像。
 *
 * <h2>为什么值得单独跑一次</h2>
 *
 * <p>管理员填的是「别名 + 聚合方式」，模型看不到**取值**。于是「状态」到底该写
 * {@code RESOLVED} 还是 {@code '已解决'}，只能靠猜——而猜错的表现是查出 0 行，
 * 用户以为是没数据。
 *
 * <p>画像跑一次、存进配置，之后每次提问都在 prompt 里带着。BIRD 的实测里这是
 * 投入产出比最高的一环，而且成本**一次生成、永久摊销**。
 *
 * <h2>只读、且限定在已注册的列上</h2>
 *
 * <p>不为"看起来有用的列"额外扫表：注册表就是管理员声明的问数边界，
 * 越过它去画像等于扩大了数据暴露面——画像结果会进 prompt，也就等于进了模型。
 */
public final class ColumnProfiler {

    private static final Logger log = LoggerFactory.getLogger(ColumnProfiler.class);

    /** 采样行数。画像只需要分布形状，不需要全表。 */
    private static final int SAMPLE_ROWS = 500;

    private final SqlExecutor executor;

    public ColumnProfiler(SqlExecutor executor) {
        this.executor = executor;
    }

    /**
     * 给一张表的所有已启用列画像。
     *
     * <p>一条 SQL 取回所有列的去重计数与若干样例，而不是每列查一次——
     * 一张 20 列的表要发 20 条查询，在慢库上会跑很久。
     *
     * <p>取不到（表不存在、权限不足、类型不支持）时返回空列表并记日志：
     * 画像失败不该阻断配置保存，只是这一批列没有描述。
     */
    public List<ColumnProfile> profile(String datasourceId, RegisteredTable table) {
        List<ColumnDef> cols = table.columns().stream()
                .filter(ColumnDef::enabled)
                .filter(c -> c.col() != null && !c.col().isBlank())
                .toList();
        if (cols.isEmpty()) {
            return List.of();
        }

        // 每列两个聚合：去重计数 + 一个样例。JSON 里带列名，避免位置对应出错
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            String c = "`" + cols.get(i).col().replace("`", "``") + "`";
            sql.append("COUNT(DISTINCT ").append(c).append(") AS d").append(i)
                    .append(", MIN(").append(c).append(") AS s").append(i);
        }
        sql.append(" FROM ").append(tableReference(table)).append(" LIMIT ").append(SAMPLE_ROWS);

        QueryResult rows;
        try {
            rows = executor.execute(datasourceId,
                    new RenderedSql(sql.toString(), List.of(), List.of(), null));
        } catch (RuntimeException e) {
            log.warn("列画像失败（不影响配置保存）：table={} 原因={}", table.name(), e.getMessage());
            return List.of();
        }
        if (rows.rowCount() == 0) {
            return List.of();
        }

        List<Object> row = rows.rows().get(0);
        List<ColumnProfile> out = new ArrayList<>(cols.size());
        for (int i = 0; i < cols.size(); i++) {
            long distinct = asLong(row, 2 * i);
            List<String> samples = distinct > 0 && distinct <= ColumnProfile.enumMaxDistinct()
                    ? listDistinct(datasourceId, table, cols.get(i), distinct)
                    : singleton(asString(row, 2 * i + 1));
            out.add(new ColumnProfile(cols.get(i).col(), cols.get(i).type(),
                    distinct, 0, SAMPLE_ROWS, samples));
        }
        return out;
    }

    /**
     * 枚举列取全部取值。
     *
     * <p>只有基数很小的列才走这条：它需要额外一次查询，而这样一次查询换来的
     * 是模型能直接照着写 {@code WHERE status = 'RESOLVED'}。
     */
    private List<String> listDistinct(String datasourceId, RegisteredTable table,
                                      ColumnDef column, long distinct) {
        String sql = "SELECT DISTINCT `%s` AS v FROM %s WHERE `%s` IS NOT NULL ORDER BY v LIMIT %d"
                .formatted(column.col().replace("`", "``"), tableReference(table),
                        column.col().replace("`", "``"), ColumnProfile.maxSamples());
        try {
            QueryResult r = executor.execute(datasourceId,
                    new RenderedSql(sql, List.of(), List.of(), null));
            List<String> values = new ArrayList<>(r.rowCount());
            for (List<Object> row : r.rows()) {
                String v = asString(row, 0);
                if (v != null && !v.isBlank()) {
                    values.add(v);
                }
            }
            return values;
        } catch (RuntimeException e) {
            log.debug("取枚举取值失败，退回单样例：table={} col={} 原因={}",
                    table.name(), column.col(), e.getMessage());
            return List.of();
        }
    }

    /** 表引用：物理表直接用表名，SQL 虚拟表包成子查询。 */
    private static String tableReference(RegisteredTable table) {
        return table.isPhysical()
                ? "`" + table.name().replace("`", "``") + "`"
                : "(" + table.sqlText() + ") p";
    }

    private static long asLong(List<Object> row, int idx) {
        Object v = idx < row.size() ? row.get(idx) : null;
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? 0 : Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String asString(List<Object> row, int idx) {
        Object v = idx < row.size() ? row.get(idx) : null;
        return v == null ? null : v.toString();
    }

    private static List<String> singleton(String v) {
        return v == null || v.isBlank() ? List.of() : List.of(v);
    }
}

package com.liusongboyy.chatdata.engine.raw;

import com.liusongboyy.chatdata.engine.datasource.DatasourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 直连模式下**模型看到的那份说明书**：表名、表注释、列（名/类型/注释）、以及几行采样值。
 *
 * <h2>采样值为什么重要</h2>
 *
 * <p>只看列名与类型，模型不知道 {@code status} 里到底是 {@code 有效/失效} 还是
 * {@code ACTIVE/CLOSED}，也不知道 {@code region} 存的是「华东」还是「East」。
 * 这类信息直接决定筛选条件写对写错，而它是**看一眼数据就知道**的。
 *
 * <h2>采样值怎么取</h2>
 *
 * <p>每张表一次 {@code SELECT * ... LIMIT 3}，然后按列去重取值。不用
 * 「每列一条 DISTINCT 查询」——那是 N 次查询，而这里只要几个例子。
 *
 * <h2>缓存</h2>
 *
 * <p>每问一句都重新读一遍业务库的元数据与样例行，既慢又给业务库添无谓负载。
 * 按「数据源 + 表清单」缓存 10 分钟：配置很少变，而 10 分钟足以避免连续提问时的重复扫描。
 *
 * <p>缓存的是**字符串**而不是解析后的结构：它的唯一用途就是拼进 prompt，
 * 缓存结构反而多一层维护。
 */
public final class RawSchemaBuilder {

    private static final Logger log = LoggerFactory.getLogger(RawSchemaBuilder.class);

    /** 采样行数。三行足够看出取值的形态，又不至于把大字段拖进来。 */
    private static final int SAMPLE_ROWS = 3;
    /** 缓存有效期。 */
    private static final long TTL_MILLIS = 10 * 60 * 1000L;
    /** 单个采样值截断长度：一列里出现几百字的长文本时，prompt 会被它撑爆。 */
    private static final int MAX_SAMPLE_LENGTH = 40;

    private final DatasourceResolver resolver;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public RawSchemaBuilder(DatasourceResolver resolver) {
        this.resolver = resolver;
    }

    private record Cached(String text, long expiresAt) {
    }

    /**
     * 为这批表生成说明书。
     *
     * @param datasourceId 数据源 id
     * @param tables       白名单表名
     */
    public String describe(String datasourceId, List<String> tables) {
        List<String> sorted = tables.stream().sorted().toList();
        String key = datasourceId + "|" + String.join(",", sorted);

        Cached hit = cache.get(key);
        if (hit != null && hit.expiresAt() > System.currentTimeMillis()) {
            return hit.text();
        }

        String text = build(datasourceId, sorted);
        cache.put(key, new Cached(text, System.currentTimeMillis() + TTL_MILLIS));
        return text;
    }

    /** 清缓存。表结构变了（如管理员刚加了列）时可强制刷新。 */
    public void invalidate() {
        cache.clear();
    }

    private String build(String datasourceId, List<String> tables) {
        StringBuilder sb = new StringBuilder();
        for (String table : tables) {
            sb.append("\n### 表 ").append(table).append('\n');
            try (Connection c = resolver.resolve(datasourceId).getConnection()) {
                appendColumns(sb, c, table);
                appendSamples(sb, c, table);
            } catch (Exception e) {
                // **单张表失败不连累其它表**：授权没给到某张表时，其余表照样能问
                log.warn("读取表结构失败：datasource={} table={} 原因={}", datasourceId, table, e.getMessage());
                sb.append("（该表暂时读不到结构：").append(e.getMessage()).append("）\n");
            }
        }
        return sb.toString();
    }

    private void appendColumns(StringBuilder sb, Connection c, String table) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT column_name, column_type, column_comment
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ?
                ORDER BY ordinal_position
                """)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    sb.append("  - ").append(rs.getString(1)).append(' ').append(rs.getString(2));
                    String comment = rs.getString(3);
                    if (comment != null && !comment.isBlank()) {
                        sb.append("  // ").append(comment);
                    }
                    sb.append('\n');
                }
                if (!any) {
                    sb.append("  （该表不存在或没有可读的列）\n");
                }
            }
        }
    }

    private void appendSamples(StringBuilder sb, Connection c, String table) throws Exception {
        // 表名来自白名单（管理员配置），但仍用标识符引用而不是拼接值——这里没有参数可绑
        String quoted = "`" + table.replace("`", "``") + "`";
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + quoted + " LIMIT " + SAMPLE_ROWS);
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            List<List<String>> rows = new ArrayList<>();
            while (rs.next()) {
                List<String> row = new ArrayList<>(cols);
                for (int i = 1; i <= cols; i++) {
                    Object v = rs.getObject(i);
                    row.add(v == null ? "NULL" : truncate(String.valueOf(v)));
                }
                rows.add(row);
            }
            if (rows.isEmpty()) {
                sb.append("  （表中没有数据，无法给出取值样例）\n");
                return;
            }

            List<List<String>> perColumn = new ArrayList<>();
            for (int i = 0; i < cols; i++) {
                List<String> samples = new ArrayList<>();
                for (List<String> row : rows) {
                    String v = row.get(i);
                    if (!samples.contains(v)) {
                        samples.add(v);
                    }
                }
                perColumn.add(samples);
            }
            sb.append("  样例取值：");
            for (int i = 0; i < cols; i++) {
                sb.append(md.getColumnLabel(i + 1)).append("={")
                        .append(String.join("/", perColumn.get(i))).append("} ");
            }
            sb.append('\n');
        }
    }

    private static String truncate(String v) {
        String s = v.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() <= MAX_SAMPLE_LENGTH ? s : s.substring(0, MAX_SAMPLE_LENGTH) + "…";
    }
}

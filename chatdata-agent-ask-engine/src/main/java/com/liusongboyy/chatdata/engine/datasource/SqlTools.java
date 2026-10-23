package com.liusongboyy.chatdata.engine.datasource;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 暴露给 LLM 的数据源工具：看表清单、看表结构、跑只读查询。
 *
 * <h2>这是「直连模式」的执行端</h2>
 *
 * <p>建模模式下模型只产出 DSL，SQL 由 {@code AskSqlRenderer} 渲染，权限在那里注入。
 * 直连模式下**模型自己写 SQL**，本类是它与数据库之间的唯一通道，所以三道闸门都在这里：
 *
 * <ol>
 *   <li><b>表级白名单</b>——SQL 里出现的每一张表都必须在白名单内（{@link #rejectOutsideAllowlist}）</li>
 *   <li><b>只读</b>——首关键字必须是 SELECT / WITH，且不允许语句</li>
 *   <li><b>资源</b>——行数上限与查询超时</li>
 * </ol>
 *
 * <h2>⚠️ 本类**不做行级/列级权限**</h2>
 *
 * <p>直连模式没有安全的注入点：模型写的 SQL 形状任意（CTE / UNION / 子查询 / 窗口函数），
 * 解析后注入谓词这条路 {code docs/adr/0002} 已评估并否决。所以该模式**只做表级授权**，
 * 敏感列要靠"不把带敏感列的表放进白名单"来挡。
 *
 * <h2>为什么用 JSqlParser 只做「取值校验」</h2>
 *
 * <p>与 ADR-0002 否决的方案是两件事：那里是**改写 SQL**（要处理所有语法形态，风险高），
 * 这里只是**读出 SQL 引用了哪些表**，读不懂就拒绝（fail-closed）。后者不改一个字符，
 * 失败形态是"拒绝执行"而不是"改错了但语法正确"。
 *
 * <h2>捕获最近一次查询</h2>
 *
 * <p>数据是模型通过工具取的，调用方（{@code RawAskService}）在 agent 跑完后需要拿到
 * 「那句 SQL + 那份结果」才能渲染给用户。故本类实例**按请求创建**，并记录最后一次成功的查询。
 */
public class SqlTools {

    private static final Logger log = LoggerFactory.getLogger(SqlTools.class);

    /** 单次查询最多返回行数。 */
    private final int maxRows;
    /** 单次查询超时（秒）。 */
    private final int timeoutSeconds;
    private final DataSource dataSource;

    /**
     * 允许访问的表（已归一化为小写）。
     *
     * <p><b>空集表示不限制</b>——建模模式的老用法与单测依赖这个行为。
     * 直连模式**必须**传非空集合，否则等于没有任何表级授权。
     */
    private final Set<String> allowedTables;

    /** 最近一次**成功**执行的查询。失败时不动它，避免把上一次的结果当成本次的。 */
    private volatile String lastSql;
    private volatile QueryResult lastResult;

    public SqlTools(DataSource dataSource) {
        this(dataSource, 200, 30, Set.of());
    }

    public SqlTools(DataSource dataSource, int maxRows, int timeoutSeconds) {
        this(dataSource, maxRows, timeoutSeconds, Set.of());
    }

    /**
     * @param allowedTables 表白名单；**空集 = 不限制**
     */
    public SqlTools(DataSource dataSource, int maxRows, int timeoutSeconds,
                    Set<String> allowedTables) {
        this.dataSource = dataSource;
        this.maxRows = maxRows;
        this.timeoutSeconds = timeoutSeconds;
        this.allowedTables = normalizeAll(allowedTables);
    }

    /** 最近一次成功执行的 SQL；还没执行过则为 null。 */
    public String lastSql() {
        return lastSql;
    }

    /** 最近一次成功执行的结果集；还没执行过则为 null。 */
    public QueryResult lastResult() {
        return lastResult;
    }

    @Tool(name = "list_tables", description = "列出可查询的表名。只能查这些表。")
    public String listTables() throws Exception {
        if (!allowedTables.isEmpty()) {
            // 白名单本身就是答案，不必查库——查 information_schema 反而会把
            // 这个库里的**其他**表名泄露给模型
            return String.join(", ", new TreeSet<>(allowedTables));
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT table_name FROM information_schema.tables "
                             + "WHERE table_schema = DATABASE() ORDER BY table_name");
             ResultSet rs = ps.executeQuery()) {
            List<String> names = new ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString(1));
            }
            return String.join(", ", names);
        }
    }

    @Tool(name = "describe_table", description = "查看指定表的字段名、类型与注释")
    public String describeTable(
            @ToolParam(name = "table", description = "表名") String table) throws Exception {
        if (!allowedTables.isEmpty() && !allowedTables.contains(normalize(table))) {
            // 不在白名单里连"表是否存在"都不能透露
            return "该表不可访问：" + table;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT column_name, column_type, column_comment FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name = ? ORDER BY ordinal_position")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                StringBuilder sb = new StringBuilder();
                while (rs.next()) {
                    sb.append(rs.getString(1)).append(' ').append(rs.getString(2));
                    String cm = rs.getString(3);
                    if (cm != null && !cm.isBlank()) {
                        sb.append("  -- ").append(cm);
                    }
                    sb.append('\n');
                }
                return sb.isEmpty() ? "表不存在：" + table : sb.toString();
            }
        }
    }

    /**
     * 执行只读查询。
     *
     * <p>四重防护：只读白名单、语句数、**表级白名单**、行数上限与超时。
     *
     * <p>白名单用「首关键字」而非「禁止关键字」是刻意的：禁止式规则总能被绕过
     * （注释、大小写、换行、子查询嵌套），白名单式只有一条通过路径。
     */
    @Tool(name = "run_query",
            description = "执行一条只读 SELECT 查询并返回结果表格。只允许 SELECT 或 WITH 开头，且只能查给定的表。")
    public String runQuery(
            @ToolParam(name = "sql", description = "一条 SELECT 语句") String sql) throws Exception {
        String rejection = rejectUnsafe(sql);
        if (rejection == null) {
            rejection = rejectOutsideAllowlist(sql, allowedTables);
        }
        if (rejection != null) {
            return rejection;
        }

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setMaxRows(maxRows);
            ps.setQueryTimeout(timeoutSeconds);
            try (ResultSet rs = ps.executeQuery()) {
                QueryResult result = read(rs);
                // 只有**成功**才更新，失败时保留上一次——否则模型重试失败后，
                // 调用方会拿着上一次的数据当本次结果
                lastSql = sql;
                lastResult = result;
                return render(result);
            }
        } catch (Exception e) {
            // 把失败原因回给模型，让它能自行改 SQL 重试；但不泄露连接串等内部信息
            return "查询失败：" + e.getMessage();
        }
    }

    /**
     * 只读校验。返回 null 表示通过，否则返回给模型看的拒绝原因。
     *
     * <p>公开是给**范例保存**用的（{@code SqlExampleInput} 用它拦掉 DELETE 之类的写法）：
     * 范例会原样进 prompt，一条写操作存进去，下一个相似问题就可能被带成删除语句。
     * 同一个判据两处共用，不各写一份。
     */
    public static String rejectUnsafe(String sql) {
        if (sql == null || sql.isBlank()) {
            return "拒绝执行：SQL 为空";
        }
        String normalized = sql.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("select") && !normalized.startsWith("with")) {
            return "拒绝执行：只允许 SELECT / WITH 开头的只读查询";
        }
        // 去掉结尾分号后若仍有分号 = 多语句
        if (normalized.replaceAll(";\\s*$", "").contains(";")) {
            return "拒绝执行：不允许多语句";
        }
        return null;
    }

    /**
     * 表级白名单校验。返回 null 表示通过。
     *
     * <p><b>这是"表级授权"真正的强制点</b>——光在 prompt 里写"只能查这几张表"等于没有授权：
     * 模型可以被问题诱导去查 {@code sys_user} 之类的表。
     *
     * <p>三种拒绝：解析不了（fail-closed）、引用了带库名前缀的表（可能是想绕到别的库）、
     * 引用了白名单外的表。
     *
     * <p>包级可见以便单测直接覆盖，不必真连库。
     *
     * @param allowedTables 已归一化的小写表名；**空集表示不限制**
     */
    public static String rejectOutsideAllowlist(String sql, Set<String> allowedTables) {
        if (allowedTables == null || allowedTables.isEmpty()) {
            return null;
        }
        Set<String> referenced;
        try {
            referenced = TablesNamesFinder.findTables(sql);
        } catch (Exception e) {
            // 解析不了就不执行：宁可拒绝，也不要"看不懂但先跑跑看"
            return "拒绝执行：SQL 无法解析，请改写为简单的 SELECT";
        }

        for (String raw : referenced) {
            String name = raw == null ? "" : raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (name.contains(".")) {
                // 带库名/模式名的一律拒绝：白名单是按裸表名配的，
                // 放行 otherdb.ins_policy 这种写法等于允许跨库
                return "拒绝执行：不要给表名加库名前缀，直接用表名。被拒绝的是：" + name;
            }
            if (!allowedTables.contains(normalize(name))) {
                return "拒绝执行：表「" + name + "」不在可访问范围内。可访问的表："
                        + String.join(", ", new TreeSet<>(allowedTables));
            }
        }
        return null;
    }

    private static String normalize(String tableName) {
        String n = tableName == null ? "" : tableName.trim().toLowerCase(Locale.ROOT);
        // 去掉反引号/双引号：JSqlParser 会把 `t` 保留成带引号的形态
        n = n.replace("`", "").replace("\"", "");
        return n;
    }

    private static Set<String> normalizeAll(Set<String> tables) {
        if (tables == null || tables.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String t : tables) {
            if (t != null && !t.isBlank()) {
                out.add(normalize(t));
            }
        }
        return Set.copyOf(out);
    }

    /** 读成结构化的结果。模型看字符串，调用方看对象——**两者必须来自同一次读取**。 */
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

    /** 给模型看的表格文本。 */
    private static String render(QueryResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(" | ", result.columns())).append('\n');
        for (List<Object> row : result.rows()) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    sb.append(" | ");
                }
                sb.append(row.get(i) == null ? "" : row.get(i));
            }
            sb.append('\n');
        }
        sb.append("（共 ").append(result.rowCount()).append(" 行）");
        log.debug("工具查询返回 {} 行", result.rowCount());
        return sb.toString();
    }
}

package com.liusongboyy.chatdata.engine.datasource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * 从数据源读取表与列的元数据。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>在此之前，注册表里的表名与列名全靠管理员手打。实测踩到过：
 * 种子数据里写了一个 `ins_customer.org_code`，而那张表根本没有这一列——
 * 注册、保存、启动校验全都通过，直到用户提问、SQL 执行时才炸，
 * 报错是 {@code Unknown column 't2.org_code'}，用户看不懂，管理员得翻日志。
 *
 * <p>有了它，列名可以从真实库结构里**选**出来而不是**敲**出来，
 * 保存时也能核对一遍。
 *
 * <h2>catalog 与 schema 的差异</h2>
 *
 * <p>各家的语义不同：MySQL 把「库」放在 catalog 里（schema 为 null），
 * PostgreSQL 用 schema（catalog 为 null），Oracle 用 schema 且大小写敏感。
 *
 * <p>故这里先按 catalog 查，查不到再按 schema 查——两种主流的写法都覆盖到，
 * 而不必为每种库写一套。**两者都查不到就是真的没有表**，不是"还没支持"。
 */
public class MetaReader {

    private static final Logger log = LoggerFactory.getLogger(MetaReader.class);

    /** 只看这两类。系统表、临时表不进问数。 */
    private static final String[] TABLE_TYPES = {"TABLE", "VIEW"};

    private final DatasourceResolver resolver;

    public MetaReader(DatasourceResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * 一张表。
     *
     * @param name    表名
     * @param comment 表注释。各库支持程度不一，取不到时为空串
     * @param type    TABLE / VIEW
     */
    public record TableMeta(String name, String comment, String type, String schema) {

        /**
         * 兼容旧调用方（不带库名）。
         *
         * <p>多库实例里 {@code schema} 是**必需信息**：两个库都有 {@code orders} 时，
         * 只报表名会让用户选错表，而错的表现是"数字不对"。
         */
        public TableMeta(String name, String comment, String type) {
            this(name, comment, type, null);
        }
    }

    /**
     * 一列。
     *
     * @param name     列名。**这是渲染器会原样引用的名字**，必须是库里的原文
     * @param type     数据库类型名，如 {@code varchar(50)}
     * @param nullable 是否可空
     * @param primary  是否主键
     * @param comment  列注释
     */
    public record ColumnMeta(String name, String type, boolean nullable,
                             boolean primary, String comment) {
    }

    /** 列出数据源里的表。 */
    public List<TableMeta> listTables(String datasourceId) {
        try (Connection c = resolver.resolve(datasourceId).getConnection()) {
            DatabaseMetaData md = c.getMetaData();

            List<TableMeta> tables = readTables(md, c.getCatalog(), null);
            if (tables.isEmpty()) {
                // MySQL 走 catalog，PostgreSQL/Oracle 走 schema——见类注释
                tables = readTables(md, null, c.getSchema());
            }
            // **连接没指定默认库时**（URL 不带库名），MySQL 体系的 getTables 常把
            // 所有库的表一起返回，且 TABLE_SCHEM 为空。补一次按 schema 读，
            // 这次才能拿到每张表真正属于哪个库。
            if (tables.stream().anyMatch(t -> t.schema() == null || t.schema().isBlank())) {
                List<TableMeta> bySchema = readTables(md, null, null);
                if (!bySchema.isEmpty()) {
                    tables = bySchema;
                }
            }

            // 注释为空时用 information_schema 补——Doris/StarRocks 的 JDBC
            // REMARKS 常为空，而表注释是挑表时最主要的线索
            boolean anyBlank = tables.stream()
                    .anyMatch(t -> t.comment() == null || t.comment().isBlank());
            if (anyBlank) {
                var extras = readTableExtras(c, c.getCatalog(), c.getSchema());
                List<TableMeta> enriched = new ArrayList<>(tables.size());
                for (TableMeta t : tables) {
                    TableExtras e = extras.get(t.name().toLowerCase(java.util.Locale.ROOT));
                    String comment = (t.comment() != null && !t.comment().isBlank())
                            ? t.comment()
                            : (e == null ? "" : e.comment());
                    // 库名优先用 JDBC 给的；Doris 的 JDBC 不给（全是 null），
                    // 从 information_schema 反查出来补上
                    String schema = (t.schema() != null && !t.schema().isBlank())
                            ? t.schema()
                            : (e == null ? null : e.schema());
                    enriched.add(
                            new TableMeta(t.name(), comment, t.type(),
                                    (schema == null || schema.isBlank()) ? null : schema));
                }
                tables = enriched;
            }

            // 过滤系统库。Doris/StarRocks 的 getTables 会把 __internal_schema、
            // information_schema、mysql 一起返回，这些表对建模毫无意义，
            // 却会占掉列表的一半以上（实测 525 张里有 60 多张是内部表）
            tables = tables.stream()
                    .filter(t -> !isSystemSchema(t.schema()))
                    .toList();

            log.debug("数据源 {} 读到 {} 张表", datasourceId, tables.size());
            return tables;
        } catch (Exception e) {
            throw new DatasourceRegistry.DatasourceUnavailableException(
                    "读取表清单失败：" + e.getMessage(), e);
        }
    }

    private static List<TableMeta> readTables(DatabaseMetaData md, String catalog, String schema)
            throws Exception {
        List<TableMeta> out = new ArrayList<>();
        try (ResultSet rs = md.getTables(catalog, schema, "%", TABLE_TYPES)) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name == null || name.isBlank()) {
                    continue;
                }
                out.add(new TableMeta(name, nullToEmpty(rs.getString("REMARKS")),
                        nullToEmpty(rs.getString("TABLE_TYPE")),
                        nullToEmpty(rs.getString("TABLE_SCHEM"))));
            }
        }
        return out;
    }

    /**
     * 用 {@code information_schema} 补表注释与行数。
     *
     * <h2>为什么 JDBC 的 REMARKS 不够</h2>
     *
     * <p>{@code DatabaseMetaData.getTables} 的 {@code REMARKS} 在不少驱动上是空的，
     * <b>Doris / StarRocks 尤其如此</b>——它们把注释存在 {@code information_schema.tables}
     * 里，JDBC 层不返回。
     *
     * <p>表注释在挑表时是<b>主要线索</b>：500 张表里靠名字猜用途，比看起来慢得多。
     * 所以注释拿不到时要主动去补，而不是接受它为空。
     *
     * <h2>失败不能影响主流程</h2>
     *
     * <p>这条查询依赖 {@code information_schema} 的列名（各家有差异）。
     * 查不到就返回空 map 让调用方保持原样——补注释是增强，不是前置条件。
     */
    private static java.util.Map<String, TableExtras> readTableExtras(
            Connection c, String catalog, String schema) {
        java.util.Map<String, TableExtras> out = new java.util.HashMap<>();
        // information_schema.table_schema 在 MySQL 体系里就是库名；
        // Doris 的 catalog 概念不同，schema（库）才是有效的那个
        // **不能以"有没有默认库"为前提。**
        //
        // 数据源的 URL 常见是不带库名的（jdbc:mysql://host:9030），
        // 此时 getCatalog()/getSchema() 都是 null——旧代码在这里直接 return，
        // 于是整段注释补全被跳过，500 张表全显示"无注释"。
        //
        // 没有默认库时查全部：Doris 的 information_schema 里，
        // 每行自带 table_schema，取到它就是表所属的库。
        String db = schema != null && !schema.isBlank() ? schema : catalog;
        // 带库名时只查那个库（快）；不带时查全部（否则注释取不到）
        String sql = (db != null && !db.isBlank())
                ? "SELECT table_schema, table_name, table_comment, table_rows "
                        + "FROM information_schema.tables WHERE table_schema = ?"
                : "SELECT table_schema, table_name, table_comment, table_rows "
                        + "FROM information_schema.tables";
        try (java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
            if (db != null && !db.isBlank()) {
                ps.setString(1, db);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("table_name");
                    if (name == null) {
                        continue;
                    }
                    String rowSchema = nullToEmpty(rs.getString("table_schema"));
                    // **键只用表名。** Doris/StarRocks 的 JDBC getTables 连
                    // TABLE_SCHEM 都不返回（实测全是 null），按"库\0表"拼键
                    // 永远匹配不上——注释就一直是空的。
                    // 库名放在值里，回填时顺便补上表所属的库。
                    out.put(name.toLowerCase(java.util.Locale.ROOT),
                            new TableExtras(nullToEmpty(rs.getString("table_comment")),
                                    rs.getLong("table_rows"), rowSchema));
                }
            }
        } catch (Exception e) {
            // Doris 的 information_schema 列名可能与 MySQL 有差异。
            // 失败时退一步：只要 (table_name, table_comment) 两列，
            // 这是各版本都有的最小集合
            log.debug("读取 information_schema 全量失败，退化为最小列集：{}", e.getMessage());
            return readTableExtrasMinimal(c);
        }
        return out;
    }

    /**
     * 最小列集兜底。
     *
     * <p>Doris / StarRocks / TiDB 的 {@code information_schema} 在列名上有差异
     * （{@code table_comment} vs {@code table_commentary}，有无 {@code table_rows}）。
     * 带 {@code table_rows} 的查询失败时不能就此放弃注释——
     * <b>注释是挑表时最需要的那个信息</b>。
     */
    private static java.util.Map<String, TableExtras> readTableExtrasMinimal(Connection c) {
        java.util.Map<String, TableExtras> out = new java.util.HashMap<>();
        String sql = "SELECT table_schema, table_name, table_comment FROM information_schema.tables";
        try (java.sql.Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString("table_name");
                if (name == null) {
                    continue;
                }
                String rowSchema = nullToEmpty(rs.getString("table_schema"));
                out.put(name.toLowerCase(java.util.Locale.ROOT),
                        new TableExtras(nullToEmpty(rs.getString("table_comment")), -1, rowSchema));
            }
        } catch (Exception e) {
            log.debug("最小列集也取不到表注释：{}", e.getMessage());
        }
        return out;
    }

    /**
     * 系统库。这些库里的表不该出现在挑表列表里。
     *
     * <p>不同引擎的系统库不同，这里列的是 MySQL 体系（含 Doris/StarRocks/TiDB）
     * 会遇到的那些。用精确匹配而不是 startsWith：
     * 用户自己的库可能叫 {@code mysql_analysis}，那是有意义的。
     */
    private static boolean isSystemSchema(String schema) {
        if (schema == null || schema.isBlank()) {
            return false;
        }
        return switch (schema.toLowerCase(java.util.Locale.ROOT)) {
            case "__internal_schema", "information_schema", "mysql",
                 "performance_schema", "sys", "pg_catalog", "pg_toast" -> true;
            default -> false;
        };
    }

    /** {@code information_schema} 里的补充信息。 */
    private record TableExtras(String comment, long rowCount, String schema) {
    }

    /** 列出某张表的列。表不存在时返回空列表。 */
    public List<ColumnMeta> listColumns(String datasourceId, String table) {
        return listColumns(datasourceId, null, table);
    }

    /**
     * 列出某张表的列（指定库）。
     *
     * <p><b>多库实例必须传 schema。</b>两个库都有 {@code orders} 时，只按表名查
     * 会拿到其中一个的列——而选错列的表现是"数字不对"，不报错。
     */
    public List<ColumnMeta> listColumns(String datasourceId, String schema, String table) {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("表名不能为空");
        }
        try (Connection c = resolver.resolve(datasourceId).getConnection()) {
            DatabaseMetaData md = c.getMetaData();

            // 指定了库就只查那个库；没指定按老逻辑回落
            String useSchema = (schema != null && !schema.isBlank()) ? schema : c.getSchema();
            List<ColumnMeta> columns = useSchema == null || useSchema.isBlank()
                    ? readColumns(md, c.getCatalog(), null, table)
                    : readColumns(md, null, useSchema, table);
            if (columns.isEmpty()) {
                columns = readColumns(md, c.getCatalog(), null, table);
            }
            if (columns.isEmpty()) {
                columns = readColumns(md, null, c.getSchema(), table);
            }
            return columns;
        } catch (Exception e) {
            throw new DatasourceRegistry.DatasourceUnavailableException(
                    "读取字段清单失败：" + e.getMessage(), e);
        }
    }

    private static List<ColumnMeta> readColumns(DatabaseMetaData md, String catalog,
                                                String schema, String table) throws Exception {
        // 主键要单独查——getColumns 的结果里没有这个信息。
        // 早先版本用 IS_AUTOINCREMENT 冒充主键，那是错的：
        // 自增列通常是主键，但主键不一定是自增（复合主键、业务主键都不自增）。
        java.util.Set<String> primaryKeys = new java.util.HashSet<>();
        try (ResultSet pk = md.getPrimaryKeys(catalog, schema, table)) {
            while (pk.next()) {
                String name = pk.getString("COLUMN_NAME");
                if (name != null) {
                    primaryKeys.add(name.toLowerCase(java.util.Locale.ROOT));
                }
            }
        } catch (Exception e) {
            // 部分驱动对不存在的表会在这里抛。主键信息缺失不该让整次读取失败
            log.debug("读取表 {} 的主键失败：{}", table, e.getMessage());
        }

        List<ColumnMeta> out = new ArrayList<>();
        try (ResultSet rs = md.getColumns(catalog, schema, table, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                if (name == null || name.isBlank()) {
                    continue;
                }
                String typeName = nullToEmpty(rs.getString("TYPE_NAME"));
                int size = rs.getInt("COLUMN_SIZE");
                String fullType = size > 0 && !typeName.contains("(")
                        ? typeName + "(" + size + ")"
                        : typeName;

                out.add(new ColumnMeta(
                        name,
                        fullType,
                        rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        primaryKeys.contains(name.toLowerCase(java.util.Locale.ROOT)),
                        nullToEmpty(rs.getString("REMARKS"))));
            }
        }
        return out;
    }

    /**
     * 核对一组列名是否都真实存在于表里。
     *
     * <p>返回**不存在的那些列名**，空表示全部通过。
     *
     * <p>用于保存注册表前的校验：把"用户提问时才炸"提前到"保存时就报"，
     * 而且报的是"这几列不存在"，不是数据库的 {@code Unknown column}。
     */
    public List<String> findMissingColumns(String datasourceId, String table, List<String> declared) {
        if (declared == null || declared.isEmpty()) {
            return List.of();
        }
        List<String> actual = listColumns(datasourceId, table).stream()
                .map(ColumnMeta::name)
                .toList();

        if (actual.isEmpty()) {
            // 表都读不到，不能断言"列不存在"——那会把"表名写错"报成"列写错"。
            // 返回空让调用方跳过列校验，由表级校验去报
            return List.of();
        }

        // 大小写不敏感比对：MySQL 在多数平台不区分，PostgreSQL 折小写、Oracle 折大写。
        // 用逐字符比对会把合法配置判成错误，那比漏判更烦人
        List<String> lower = actual.stream().map(s -> s.toLowerCase(java.util.Locale.ROOT)).toList();
        return declared.stream()
                .filter(col -> col != null && !col.isBlank())
                .filter(col -> !lower.contains(col.trim().toLowerCase(java.util.Locale.ROOT)))
                .toList();
    }

    /** 表是否真实存在。 */
    public boolean tableExists(String datasourceId, String table) {
        return listTables(datasourceId).stream()
                .anyMatch(t -> t.name().equalsIgnoreCase(table));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}

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
    public record TableMeta(String name, String comment, String type) {
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
                        nullToEmpty(rs.getString("TABLE_TYPE"))));
            }
        }
        return out;
    }

    /** 列出某张表的列。表不存在时返回空列表。 */
    public List<ColumnMeta> listColumns(String datasourceId, String table) {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("表名不能为空");
        }
        try (Connection c = resolver.resolve(datasourceId).getConnection()) {
            DatabaseMetaData md = c.getMetaData();

            List<ColumnMeta> columns = readColumns(md, c.getCatalog(), null, table);
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

package com.liusongboyy.chatdata.engine.datasource.dialect;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按 {@code db_type} 取方言。
 *
 * <p><strong>未知类型抛异常，不回落默认方言。</strong>回落是最省事也最危险的写法：
 * 拿 MySQL 方言去渲染 Oracle 查询，生成的 SQL 语法就是错的——而错误会以
 * "SQL 语法错误"的形式出现在执行阶段，离真实原因（方言选错）很远。
 * 宁可在这里明确失败。
 *
 * <p>新增一种库时，只需实现 {@link SqlDialect} 并在此注册一行。
 */
public final class SqlDialects {

    /** 注册表。key 统一小写，查表时也小写化，避免大小写差异导致查不到。 */
    private static final Map<String, SqlDialect> REGISTRY = new LinkedHashMap<>();

    static {
        MySqlDialect mysql = new MySqlDialect();
        PostgreSqlDialect postgres = new PostgreSqlDialect();
        OracleDialect oracle = new OracleDialect();
        SqlServerDialect sqlServer = new SqlServerDialect();
        DorisDialect doris = new DorisDialect();

        // 首版支持的五种
        register(mysql, "MYSQL5.7");
        register(doris, "Doris");
        register(postgres, "POSTGRESQL");
        register(oracle, "ORACLE");
        register(sqlServer, "SQLSERVER");

        // 同方言的别名。它们与本尊的 SQL 方言一致，但驱动或版本不同：
        //   MYSQL5.5 —— 厂商的历史数据里有这个类型，不加会直接查不到
        //   TIDB     —— 兼容 MySQL 协议与方言
        //   MARIADB  —— 兼容 MySQL，差异点我们用不到
        register(mysql, "MYSQL5.5", "MYSQL", "TIDB", "MARIADB");
    }

    private SqlDialects() {
    }

    private static void register(SqlDialect dialect, String... keys) {
        for (String k : keys) {
            REGISTRY.put(k.toLowerCase(Locale.ROOT), dialect);
        }
    }

    /**
     * 取方言。
     *
     * @param dbType 数据源类型，如 {@code MYSQL5.7}。大小写不敏感
     * @throws UnsupportedDialectException 类型未注册时
     */
    public static SqlDialect of(String dbType) {
        if (dbType == null || dbType.isBlank()) {
            throw new UnsupportedDialectException("数据源类型为空，无法确定 SQL 方言");
        }
        SqlDialect d = REGISTRY.get(dbType.trim().toLowerCase(Locale.ROOT));
        if (d == null) {
            throw new UnsupportedDialectException(
                    "暂不支持的数据源类型：%s。已支持：%s".formatted(dbType, supportedTypes()));
        }
        return d;
    }

    /** 是否支持该类型。供数据源管理页做前置校验，避免配完了才发现渲染不了。 */
    public static boolean supports(String dbType) {
        return dbType != null && REGISTRY.containsKey(dbType.trim().toLowerCase(Locale.ROOT));
    }

    /** 已注册的类型列表，用于错误提示与管理页下拉。 */
    public static List<String> supportedTypes() {
        return List.copyOf(REGISTRY.keySet());
    }

    /** 类型不受支持。**不要捕获它去回落到别的方言。** */
    public static class UnsupportedDialectException extends RuntimeException {
        public UnsupportedDialectException(String message) {
            super(message);
        }
    }
}

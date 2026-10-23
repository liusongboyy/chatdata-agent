package com.liusongboyy.chatdata.engine.datasource;

import com.liusongboyy.chatdata.engine.nlsql.RenderedSql;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SQL 执行器的真库验证。
 *
 * <p>前面那些编排测试用的是假执行器——它们验证"什么时候执行"，
 * 而这里验证"执行本身对不对"：参数有没有错位、只读校验生不生效、
 * 行数上限有没有真的生效。
 *
 * <p>参数错位是这类代码最隐蔽的缺陷：不报错、不崩溃，
 * 只是把值绑到了错误的位置，于是查出来的数字是错的。
 */
class SqlExecutorRealDbTest {

    private static final String URL =
            "jdbc:mysql://127.0.0.1:3306/jimureport?useUnicode=true&characterEncoding=UTF-8"
                    + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";

    /** 金标准：华东生效保单保费合计。 */
    private static final BigDecimal GOLD = new BigDecimal("369140.20");

    private static SqlExecutor executor;

    @BeforeAll
    static void setUp() {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setURL(URL);
        ds.setUser("root");
        ds.setPassword("root");

        boolean ok;
        try (Connection c = ds.getConnection()) {
            ok = c.isValid(3);
        } catch (Exception e) {
            ok = false;
        }
        Assumptions.assumeTrue(ok, "本地 MySQL 不可用，跳过执行器真库验证");

        executor = new SqlExecutor(id -> ds);
    }

    @Test
    @DisplayName("★ 参数按位置正确绑定 —— 错位不会报错，只会查出错数字")
    void parametersBoundInOrder() {
        RenderedSql sql = new RenderedSql(
                "SELECT SUM(`premium`) FROM `ins_policy` WHERE `status` = ? AND `region` = ?",
                List.of("生效", "华东"),
                List.of(),
                "t1");

        QueryResult r = executor.execute("1", sql);

        assertThat(new BigDecimal(String.valueOf(r.rows().get(0).get(0))))
                .as("把两个参数调换会得到华北的数字（337711.23），而不会报任何错")
                .isEqualByComparingTo(GOLD);
    }

    @Test
    @DisplayName("多行结果与列名都正确返回")
    void multiRowResult() {
        RenderedSql sql = new RenderedSql(
                "SELECT `region`, SUM(`premium`) AS total FROM `ins_policy` "
                        + "WHERE `status` = ? GROUP BY `region` ORDER BY total DESC",
                List.of("生效"),
                List.of(),
                "t1");

        QueryResult r = executor.execute("1", sql);

        assertThat(r.columns()).containsExactly("region", "total");
        assertThat(r.rowCount()).isGreaterThan(1);
        // 第一行按 total 降序应当是华东
        assertThat(String.valueOf(r.rowAsMap(0).get("region"))).isEqualTo("华东");
        assertThat(new BigDecimal(String.valueOf(r.rowAsMap(0).get("total"))))
                .isEqualByComparingTo(GOLD);
    }

    @Test
    @DisplayName("★ 非只读 SQL 被拒 —— 渲染器有 bug 也不该变成一次写操作")
    void rejectsNonReadOnly() {
        RenderedSql delete = new RenderedSql("DELETE FROM ins_policy", List.of(), List.of(), "t1");

        assertThatThrownBy(() -> executor.execute("1", delete))
                .isInstanceOf(SqlExecutor.SqlExecutionException.class)
                .hasMessageContaining("只读");
    }

    @Test
    @DisplayName("★ 多语句被拒")
    void rejectsMultipleStatements() {
        RenderedSql multi = new RenderedSql(
                "SELECT 1; DROP TABLE ins_policy", List.of(), List.of(), "t1");

        assertThatThrownBy(() -> executor.execute("1", multi))
                .isInstanceOf(SqlExecutor.SqlExecutionException.class);
    }

    @Test
    @DisplayName("★ 行数上限真的生效")
    void maxRowsEnforced() {
        SqlExecutor limited = new SqlExecutor(id -> {
            MysqlDataSource ds = new MysqlDataSource();
            ds.setURL(URL);
            ds.setUser("root");
            ds.setPassword("root");
            return ds;
        }, 5, 30);

        RenderedSql all = new RenderedSql(
                "SELECT `policy_no` FROM `ins_policy`", List.of(), List.of(), "t1");

        QueryResult r = limited.execute("1", all);

        assertThat(r.rowCount())
                .as("库里 300 行，上限 5 就该只回 5 行——大结果集会打爆内存与网络")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("SQL 报错时把数据库的原始信息带出来 —— 排查时它是第一现场")
    void databaseErrorSurfaced() {
        RenderedSql bad = new RenderedSql(
                "SELECT `no_such_column` FROM `ins_policy`", List.of(), List.of(), "t1");

        assertThatThrownBy(() -> executor.execute("1", bad))
                .isInstanceOf(SqlExecutor.SqlExecutionException.class)
                .hasMessageContaining("no_such_column");
    }

    @Test
    @DisplayName("数据源不可用时给出可读的失败，不是 NPE")
    void unavailableDatasource() {
        SqlExecutor broken = new SqlExecutor(id -> {
            throw new DatasourceRegistry.DatasourceUnavailableException("数据源 id=99 不存在或未启用");
        });

        assertThatThrownBy(() -> broken.execute("99",
                new RenderedSql("SELECT 1", List.of(), List.of(), "t1")))
                .isInstanceOf(SqlExecutor.SqlExecutionException.class)
                .hasMessageContaining("不存在");
    }
}

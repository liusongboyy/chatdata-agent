package com.liusongboyy.chatdata.engine.datasource.dialect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 方言层的测试。
 *
 * <p>重点不是"每个方法都调通了"，而是三件事：
 * <ol>
 *   <li><b>大小写折叠</b>——同一份元数据在五种方言下必须引用同一个标识符</li>
 *   <li><b>格式串不能串</b>——MySQL 的 {@code %Y-%m} 与 PG/Oracle 的 {@code YYYY-MM}
 *       是两种语言，写混了不报错、只出错结果</li>
 *   <li><b>未知类型必须失败</b>——回落默认方言会生成语法错误的 SQL，且错因离现场很远</li>
 * </ol>
 */
class SqlDialectTest {

    /** 首版支持的五种。 */
    private static final String[] FIVE = {"MYSQL5.7", "Doris", "POSTGRESQL", "ORACLE", "SQLSERVER"};

    @Nested
    @DisplayName("标识符引用 —— 防大小写折叠")
    class IdentifierQuoting {

        @ParameterizedTest
        @ValueSource(strings = {"MYSQL5.7", "Doris", "POSTGRESQL", "ORACLE", "SQLSERVER"})
        @DisplayName("★ 同一份元数据在五种方言下都必须带引号 —— 否则 PostgreSQL 折小写、Oracle 折大写")
        void alwaysQuotesInEveryDialect(String dbType) {
            SqlDialect d = SqlDialects.of(dbType);

            String quoted = d.quoteIdentifier("Region");

            assertThat(quoted)
                    .as("%s 必须给标识符加引号，不加会被大小写折叠规则改写", dbType)
                    .isIn("`Region`", "\"Region\"");
            // 关键：大小写原样保留，不做任何转换
            assertThat(quoted).contains("Region");
            assertThat(quoted).doesNotContain("region").doesNotContain("REGION");
        }

        @Test
        @DisplayName("MySQL 系用反引号，其余用双引号")
        void quotingStylePerFamily() {
            assertThat(SqlDialects.of("MYSQL5.7").quoteIdentifier("t")).isEqualTo("`t`");
            assertThat(SqlDialects.of("Doris").quoteIdentifier("t")).isEqualTo("`t`");
            assertThat(SqlDialects.of("POSTGRESQL").quoteIdentifier("t")).isEqualTo("\"t\"");
            assertThat(SqlDialects.of("ORACLE").quoteIdentifier("t")).isEqualTo("\"t\"");
            assertThat(SqlDialects.of("SQLSERVER").quoteIdentifier("t")).isEqualTo("\"t\"");
        }

        @Test
        @DisplayName("限定名逐段引用")
        void quotesQualifiedName() {
            assertThat(SqlDialects.of("MYSQL5.7").quoteQualified("p.region")).isEqualTo("`p`.`region`");
            assertThat(SqlDialects.of("ORACLE").quoteQualified("p.region")).isEqualTo("\"p\".\"region\"");
        }

        @Test
        @DisplayName("标识符内部的引号被转义 —— 不转义就从「一个名字」变成「两个 token」")
        void escapesEmbeddedQuote() {
            assertThat(SqlDialects.of("MYSQL5.7").quoteIdentifier("a`b")).isEqualTo("`a``b`");
            assertThat(SqlDialects.of("POSTGRESQL").quoteIdentifier("a\"b")).isEqualTo("\"a\"\"b\"");
        }

        @Test
        @DisplayName("空标识符拒绝，不生成空引号对")
        void rejectsBlank() {
            assertThatThrownBy(() -> SqlDialects.of("MYSQL5.7").quoteIdentifier("  "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> SqlDialects.of("MYSQL5.7").quoteIdentifier(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("行数限制 —— SQL Server 是唯一用前缀的")
    class RowLimit {

        @Test
        @DisplayName("四种用后缀 LIMIT，Oracle 用 FETCH FIRST")
        void suffixStyle() {
            for (String t : new String[]{"MYSQL5.7", "Doris", "POSTGRESQL"}) {
                SqlDialect d = SqlDialects.of(t);
                assertThat(d.limitPrefix(10)).isEmpty();
                assertThat(d.limitSuffix(10)).isEqualTo("LIMIT 10");
            }

            SqlDialect oracle = SqlDialects.of("ORACLE");
            assertThat(oracle.limitPrefix(10)).isEmpty();
            assertThat(oracle.limitSuffix(10))
                    .as("Oracle 12c+ 用 ANSI 的 FETCH FIRST")
                    .isEqualTo("FETCH FIRST 10 ROWS ONLY");
        }

        @Test
        @DisplayName("★ SQL Server 用前缀 TOP —— 渲染器不能统一当后缀处理")
        void sqlServerUsesPrefix() {
            SqlDialect sqlServer = SqlDialects.of("SQLSERVER");

            assertThat(sqlServer.limitPrefix(10)).isEqualTo("TOP 10 ");
            assertThat(sqlServer.limitSuffix(10))
                    .as("SQL Server 没有后缀形式，若这里返回 LIMIT 会生成语法错误的 SQL")
                    .isEmpty();
        }

        @Test
        @DisplayName("非正数拒绝")
        void rejectsNonPositive() {
            assertThatThrownBy(() -> SqlDialects.of("MYSQL5.7").limitSuffix(0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> SqlDialects.of("ORACLE").limitSuffix(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("按月分组的格式串 —— 两种模式语言，最容易串")
    class MonthBucket {

        @Test
        @DisplayName("MySQL 系用 %Y-%m")
        void mysqlFamilyUsesPercentPattern() {
            assertThat(SqlDialects.of("MYSQL5.7").monthBucket("`d`"))
                    .isEqualTo("DATE_FORMAT(`d`, '%Y-%m')");
            assertThat(SqlDialects.of("Doris").monthBucket("`d`"))
                    .isEqualTo("DATE_FORMAT(`d`, '%Y-%m')");
        }

        @Test
        @DisplayName("PostgreSQL / Oracle 用 YYYY-MM")
        void pgAndOracleUseIsoPattern() {
            assertThat(SqlDialects.of("POSTGRESQL").monthBucket("\"d\""))
                    .isEqualTo("to_char(\"d\", 'YYYY-MM')");
            assertThat(SqlDialects.of("ORACLE").monthBucket("\"d\""))
                    .isEqualTo("to_char(\"d\", 'YYYY-MM')");
        }

        @Test
        @DisplayName("★ MySQL 的格式串绝不能出现在 PG/Oracle 的表达式里")
        void patternsNeverCross() {
            for (String t : new String[]{"POSTGRESQL", "ORACLE", "SQLSERVER"}) {
                assertThat(SqlDialects.of(t).monthBucket("d"))
                        .as("%s 用错了 MySQL 的 %%Y-%%m 模式语言，不会报错、只会得到错的分组", t)
                        .doesNotContain("%Y");
            }
            assertThat(SqlDialects.of("MYSQL5.7").monthBucket("d"))
                    .as("反过来也不能把 YYYY-MM 塞进 DATE_FORMAT")
                    .doesNotContain("YYYY-MM");
        }

        @Test
        @DisplayName("SQL Server 用 CONVERT 而不是 FORMAT")
        void sqlServerUsesConvert() {
            assertThat(SqlDialects.of("SQLSERVER").monthBucket("d"))
                    .isEqualTo("CONVERT(varchar(7), d, 120)");
        }
    }

    @Nested
    @DisplayName("查表 —— 未知类型必须失败")
    class Lookup {

        @ParameterizedTest
        @ValueSource(strings = {"MYSQL5.7", "mysql5.7", "MySql5.7", "MYSQL5.7  "})
        @DisplayName("大小写与空白不敏感")
        void caseInsensitive(String dbType) {
            assertThat(SqlDialects.of(dbType).id()).isEqualTo("MYSQL5.7");
        }

        @Test
        @DisplayName("★ 未知类型抛异常，绝不回落默认方言")
        void unknownTypeThrows() {
            assertThatThrownBy(() -> SqlDialects.of("HIVE"))
                    .as("回落默认方言会拿 MySQL 语法去渲染别的库，生成的 SQL 是错的，"
                            + "而报错会出现在执行阶段，离真实原因很远")
                    .isInstanceOf(SqlDialects.UnsupportedDialectException.class)
                    .hasMessageContaining("HIVE");

            assertThatThrownBy(() -> SqlDialects.of(""))
                    .isInstanceOf(SqlDialects.UnsupportedDialectException.class);
            assertThatThrownBy(() -> SqlDialects.of(null))
                    .isInstanceOf(SqlDialects.UnsupportedDialectException.class);
        }

        @Test
        @DisplayName("supports 用于管理页前置校验，不抛异常")
        void supportsIsNonThrowing() {
            assertThat(SqlDialects.supports("MYSQL5.7")).isTrue();
            assertThat(SqlDialects.supports("HIVE")).isFalse();
            assertThat(SqlDialects.supports(null)).isFalse();
        }

        @Test
        @DisplayName("MySQL 同族别名可用 —— 库里已有 db_type=MYSQL5.5 的历史数据")
        void mysqlFamilyAliases() {
            assertThat(SqlDialects.of("MYSQL5.5").id()).isEqualTo("MYSQL5.7");
            assertThat(SqlDialects.of("TIDB").id()).isEqualTo("MYSQL5.7");
            assertThat(SqlDialects.of("MARIADB").id()).isEqualTo("MYSQL5.7");
        }

        @Test
        @DisplayName("五种都注册了")
        void allFiveRegistered() {
            for (String t : FIVE) {
                assertThat(SqlDialects.supports(t)).as("%s 应已注册", t).isTrue();
            }
        }
    }
}

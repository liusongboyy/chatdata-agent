package com.liusongboyy.chatdata.engine.datasource.dialect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 新增方言的 SQL 生成。
 *
 * <p>这几种库看起来都像 SQL，但问数渲染器用到的四个时间函数
 * <b>没有一个能跨库照抄</b>。测试逐条锁住差异——写混了不会报错，
 * 只会得到错误结果或字面量，那是最难查的一类失败。
 */
class NewDialectsTest {

    private static final String COL = "`t`.`d`";

    @Nested
    @DisplayName("StarRocks")
    class StarRocks {

        private final StarRocksDialect d = new StarRocksDialect();

        @Test
        @DisplayName("★ 时间函数与 MySQL 完全一致——所以能继承")
        void matchesMySql() {
            MySqlDialect mysql = new MySqlDialect();
            // 这四项是渲染器真正用到的全部时间函数，逐个对过
            assertThat(d.monthBucket(COL)).isEqualTo(mysql.monthBucket(COL));
            assertThat(d.yearBucket(COL)).isEqualTo(mysql.yearBucket(COL));
            assertThat(d.addInterval("MONTH", COL, 1)).isEqualTo(mysql.addInterval("MONTH", COL, 1));
            assertThat(d.timeDiffToNumber("DAY", COL, "x"))
                    .isEqualTo(mysql.timeDiffToNumber("DAY", COL, "x"));
        }

        @Test
        @DisplayName("标识符用反引号（MySQL 协议）")
        void backtickQuoting() {
            assertThat(d.quoteIdentifier("order")).isEqualTo("`order`");
        }
    }

    @Nested
    @DisplayName("Hive")
    class Hive {

        private final HiveDialect d = new HiveDialect();

        @Test
        @DisplayName("★ 月份格式是 yyyy-MM，不是 MySQL 的 %Y-%m")
        void monthPatternIsJavaStyle() {
            // 写成 %Y-%m 不会报错，只会得到一个字面量字符串——最难查的那种
            assertThat(d.monthBucket(COL)).isEqualTo("date_format(`t`.`d`, 'yyyy-MM')");
            assertThat(d.monthBucket(COL)).doesNotContain("%Y");
        }

        @Test
        @DisplayName("★ 没有 INTERVAL 语法，用 add_months / date_add")
        void noIntervalSyntax() {
            assertThat(d.addInterval("MONTH", COL, 1)).isEqualTo("add_months(`t`.`d`, 1)");
            assertThat(d.addInterval("YEAR", COL, 2)).isEqualTo("add_months(`t`.`d`, 24)");
            assertThat(d.addInterval("DAY", COL, 7)).isEqualTo("date_add(`t`.`d`, 7)");
            // 绝不能出现 MySQL 的 DATE_ADD(...INTERVAL...)
            assertThat(d.addInterval("MONTH", COL, 1)).doesNotContain("INTERVAL");
        }

        @Test
        @DisplayName("★ datediff 参数顺序与 TIMESTAMPDIFF 相反")
        void datediffArgumentOrder() {
            // TIMESTAMPDIFF(unit, from, to)；datediff(to, from)
            assertThat(d.timeDiffToNumber("DAY", "start", "end"))
                    .isEqualTo("datediff(end, start)");
        }

        @Test
        @DisplayName("★ 时间差只支持天，其余明确拒绝")
        void onlyDayGranularity() {
            // 静默算错比失败糟：Hive 的 datediff 只给天数，
            // 用它冒充小时差会让"平均处理时长 3 小时"变成"3 天"
            assertThatThrownBy(() -> d.timeDiffToNumber("HOUR", "a", "b"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("只返回天数");
        }
    }

    @Nested
    @DisplayName("Trino")
    class Trino {

        private final TrinoDialect d = new TrinoDialect();

        @Test
        @DisplayName("★ 标识符必须双引号——反引号是语法错误")
        void doubleQuoteQuoting() {
            assertThat(d.quoteIdentifier("order")).isEqualTo("\"order\"");
        }

        @Test
        @DisplayName("★ date_add 单位在前且带引号")
        void dateAddUnitFirst() {
            assertThat(d.addInterval("MONTH", COL, 1))
                    .isEqualTo("date_add('month', 1, `t`.`d`)");
            // 与 MySQL 的 DATE_ADD(...INTERVAL n MONTH) 完全不同
            assertThat(d.addInterval("MONTH", COL, 1)).doesNotContain("INTERVAL");
        }

        @Test
        @DisplayName("★ date_diff 单位是带引号的字符串，且在第一个参数")
        void dateDiffSignature() {
            assertThat(d.timeDiffToNumber("DAY", "a", "b"))
                    .isEqualTo("date_diff('day', a, b)");
        }

        @Test
        @DisplayName("支持小时/分钟等更细粒度（与 Hive 不同）")
        void supportsFineGrainedDiff() {
            assertThat(d.timeDiffToNumber("HOUR", "a", "b")).contains("'hour'");
            assertThat(d.timeDiffToNumber("MINUTE", "a", "b")).contains("'minute'");
        }
    }

    @Test
    @DisplayName("★ 注册表能查到三种新方言及其别名")
    void registeredWithAliases() {
        assertThat(SqlDialects.of("StarRocks")).isInstanceOf(StarRocksDialect.class);
        assertThat(SqlDialects.of("STARROCKS")).isInstanceOf(StarRocksDialect.class);
        assertThat(SqlDialects.of("HIVE")).isInstanceOf(HiveDialect.class);
        assertThat(SqlDialects.of("TRINO")).isInstanceOf(TrinoDialect.class);
        // Presto 是 Trino 的旧名，方言一致
        assertThat(SqlDialects.of("PRESTO")).isInstanceOf(TrinoDialect.class);
        assertThat(SqlDialects.of("PRESTOSQL")).isInstanceOf(TrinoDialect.class);
    }

    @Test
    @DisplayName("未注册的类型仍然抛异常，不回落默认方言")
    void unknownStillFails() {
        // 回落是最危险的写法：拿错方言渲染出的 SQL 语法是错的，
        // 而错误会以"SQL 语法错误"出现在执行阶段，离真实原因很远
        assertThatThrownBy(() -> SqlDialects.of("CLICKHOUSE_FAKE"))
                .isInstanceOf(SqlDialects.UnsupportedDialectException.class);
    }
}

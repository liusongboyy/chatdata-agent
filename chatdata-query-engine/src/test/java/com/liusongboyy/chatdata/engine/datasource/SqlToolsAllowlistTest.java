package com.liusongboyy.chatdata.engine.datasource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 直连模式的**表级白名单**校验。
 *
 * <h2>为什么这是最要紧的一组测试</h2>
 *
 * <p>直连模式没有行级/列级权限（ADR-0002 否决了 SQL 改写），所以
 * <b>表白名单是唯一的授权闸门</b>。而"只在 prompt 里告诉模型只能查这几张表"
 * 等于没有闸门——模型可以被问题诱导去查 {@code sys_user}、{@code chatdata_user}
 * 这类表。这里测的就是那道真闸门。
 *
 * <p>全部是纯函数调用，不连库：拒绝逻辑必须在**执行之前**发生，所以它本来就不该需要数据库。
 */
class SqlToolsAllowlistTest {

    private static final Set<String> ALLOWED = Set.of("ins_policy", "ins_customer");

    @Nested
    @DisplayName("放行")
    class Allows {

        @Test
        @DisplayName("白名单内的单表查询")
        void singleAllowedTable() {
            assertThat(SqlTools.rejectOutsideAllowlist(
                    "SELECT SUM(premium) FROM ins_policy WHERE region = '华东'", ALLOWED)).isNull();
        }

        @Test
        @DisplayName("表名带反引号 / 大写也认")
        void quotingAndCase() {
            assertThat(SqlTools.rejectOutsideAllowlist("SELECT * FROM `ins_policy`", ALLOWED)).isNull();
            assertThat(SqlTools.rejectOutsideAllowlist("SELECT * FROM INS_POLICY", ALLOWED)).isNull();
        }

        @Test
        @DisplayName("JOIN 的两张表都在白名单内")
        void joinOfAllowedTables() {
            String sql = "SELECT p.region, c.cust_name FROM ins_policy p "
                    + "JOIN ins_customer c ON p.cust_id = c.id";
            assertThat(SqlTools.rejectOutsideAllowlist(sql, ALLOWED)).isNull();
        }

        @Test
        @DisplayName("CTE / 子查询里引用的表也在白名单内")
        void cteAndSubquery() {
            String sql = "WITH t AS (SELECT * FROM ins_policy) "
                    + "SELECT * FROM t WHERE region IN (SELECT region FROM ins_customer)";
            assertThat(SqlTools.rejectOutsideAllowlist(sql, ALLOWED)).isNull();
        }

        @Test
        @DisplayName("空白名单 = 不限制（建模模式的老用法）")
        void emptyAllowlistMeansUnrestricted() {
            assertThat(SqlTools.rejectOutsideAllowlist("SELECT * FROM sys_user", Set.of())).isNull();
            assertThat(SqlTools.rejectOutsideAllowlist("SELECT * FROM sys_user", null)).isNull();
        }
    }

    @Nested
    @DisplayName("拒绝 —— 这才是白名单存在的意义")
    class Rejects {

        @Test
        @DisplayName("★ 白名单外的表一律拒绝，且告诉模型可以用哪些")
        void outsideAllowlist() {
            String r = SqlTools.rejectOutsideAllowlist("SELECT * FROM sys_user", ALLOWED);

            assertThat(r).isNotNull().contains("sys_user").contains("不在可访问范围内");
            assertThat(r).contains("ins_policy");
        }

        @Test
        @DisplayName("★ JOIN 里夹带一张白名单外的表也拒绝 —— 只看第一张表是最常见的漏洞")
        void joinWithOneOutsideTable() {
            String sql = "SELECT * FROM ins_policy p JOIN sys_user u ON p.cust_id = u.id";
            assertThat(SqlTools.rejectOutsideAllowlist(sql, ALLOWED)).contains("sys_user");
        }

        @Test
        @DisplayName("★ 子查询里夹带也拒绝 —— 逐词匹配会漏掉这种")
        void subqueryWithOutsideTable() {
            String sql = "SELECT * FROM ins_policy WHERE cust_id IN (SELECT id FROM sys_user)";
            assertThat(SqlTools.rejectOutsideAllowlist(sql, ALLOWED)).contains("sys_user");
        }

        @Test
        @DisplayName("★ 从 CTE 里夹带也拒绝")
        void cteWithOutsideTable() {
            String sql = "WITH u AS (SELECT * FROM sys_user) SELECT * FROM u";
            assertThat(SqlTools.rejectOutsideAllowlist(sql, ALLOWED)).contains("sys_user");
        }

        @Test
        @DisplayName("★ 带库名前缀一律拒绝 —— 否则 otherdb.ins_policy 可绕到别的库")
        void schemaQualifiedName() {
            String r = SqlTools.rejectOutsideAllowlist("SELECT * FROM otherdb.ins_policy", ALLOWED);
            assertThat(r).isNotNull().contains("库名前缀");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "SELECT * FROM ins_policy UNION SELECT * FROM chatdata_user",
                "SELECT (SELECT count(*) FROM other_table) FROM ins_policy",
        })
        @DisplayName("★ UNION / 标量子查询里夹带 —— 正则式筛查会被这些绕过")
        void sneakyForms(String sql) {
            assertThat(SqlTools.rejectOutsideAllowlist(sql, ALLOWED))
                    .as("必须拒绝：%s", sql)
                    .isNotNull();
        }

        @Test
        @DisplayName("★ 注释里的表名不算引用 —— 这正是不用正则而用解析器的理由")
        void tableNameInCommentIsNotAReference() {
            // 正则式筛查会在这里误报（把注释里的 sys_user 当成引用），
            // 而解析器知道它是注释。误报同样有害：它会让一条完全正常的查询被拒
            assertThat(SqlTools.rejectOutsideAllowlist(
                    "SELECT * FROM ins_policy WHERE 1=1 -- 参考 sys_user 的写法", ALLOWED))
                    .isNull();
        }

        @Test
        @DisplayName("★ 解析不了的 SQL 拒绝，而不是「看不懂就先跑跑看」（fail-closed）")
        void unparseableIsRejected() {
            String r = SqlTools.rejectOutsideAllowlist("SELECT FROM WHERE ((", ALLOWED);
            assertThat(r).isNotNull().contains("无法解析");
        }
    }

    @Nested
    @DisplayName("只读校验（既有能力，与白名单是两道独立的闸门）")
    class ReadOnly {

        @Test
        @DisplayName("SELECT / WITH 放行")
        void allowsSelect() {
            assertThat(SqlTools.rejectUnsafe("SELECT 1")).isNull();
            assertThat(SqlTools.rejectUnsafe("  with t as (select 1) select * from t")).isNull();
        }

        @Test
        @DisplayName("写操作与多语句拒绝")
        void rejectsWrites() {
            assertThat(SqlTools.rejectUnsafe("DELETE FROM ins_policy")).isNotNull();
            assertThat(SqlTools.rejectUnsafe("DROP TABLE ins_policy")).isNotNull();
            assertThat(SqlTools.rejectUnsafe("SELECT 1; DROP TABLE ins_policy")).contains("多语句");
        }
    }
}

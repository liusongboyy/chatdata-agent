package com.liusongboyy.chatdata.engine.datasource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只读校验的单元测试。不连库——{@code rejectUnsafe} 是纯函数。
 *
 * <p>这一层是 SQL 工具的第一道防线，绕过它模型就能对生产库做写操作。
 * 故对**绕过手法**逐一覆盖，而不只测正常路径。
 */
class SqlToolsReadOnlyGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT 1",
            "select * from ins_policy",
            "  SELECT premium FROM ins_policy WHERE region='华东'  ",
            "WITH t AS (SELECT 1) SELECT * FROM t",
            "with t as (select 1) select * from t",
    })
    @DisplayName("合法的只读查询放行")
    void allowsReadOnly(String sql) {
        assertThat(SqlTools.rejectUnsafe(sql)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM ins_policy",
            "UPDATE ins_policy SET premium=0",
            "INSERT INTO ins_policy VALUES (1)",
            "DROP TABLE ins_policy",
            "TRUNCATE TABLE ins_policy",
            "ALTER TABLE ins_policy ADD COLUMN x INT",
            "CREATE TABLE evil (id INT)",
            "GRANT ALL ON *.* TO 'x'@'%'",
    })
    @DisplayName("写操作全部拒绝")
    void rejectsWrites(String sql) {
        assertThat(SqlTools.rejectUnsafe(sql)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT 1; DROP TABLE ins_policy",
            "SELECT 1; DELETE FROM ins_policy",
            "SELECT 1 ;DROP TABLE t",
    })
    @DisplayName("多语句拼接全部拒绝（否则白名单形同虚设）")
    void rejectsMultipleStatements(String sql) {
        assertThat(SqlTools.rejectUnsafe(sql)).isNotNull();
    }

    @Test
    @DisplayName("结尾的单个分号是合法的，不算多语句")
    void allowsTrailingSemicolon() {
        assertThat(SqlTools.rejectUnsafe("SELECT 1;")).isNull();
        assertThat(SqlTools.rejectUnsafe("SELECT 1 ;  ")).isNull();
    }

    @Test
    @DisplayName("空输入拒绝，不抛异常")
    void rejectsBlank() {
        assertThat(SqlTools.rejectUnsafe(null)).isNotNull();
        assertThat(SqlTools.rejectUnsafe("")).isNotNull();
        assertThat(SqlTools.rejectUnsafe("   ")).isNotNull();
    }

    @Test
    @DisplayName("首关键字白名单对大小写与前后空白不敏感")
    void keywordCheckIsCaseAndWhitespaceInsensitive() {
        assertThat(SqlTools.rejectUnsafe("\n\tSeLeCt 1")).isNull();
        assertThat(SqlTools.rejectUnsafe("\n\tDeLeTe FROM t")).isNotNull();
    }
}

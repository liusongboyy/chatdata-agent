package com.liusongboyy.chatdata.engine.nlsql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 展示用的 SQL：把参数内联成字面量。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>执行必须用参数化语句（防注入），但**展示与审计不能**——
 * 用户看到 {@code WHERE org_path LIKE ? ESCAPE '\'} 完全不知道查了什么，
 * 审计里也一样查不出"这个用户当时到底按什么条件取的数"。
 *
 * <p>所以：执行用 {@link RenderedSql#sql()}，展示用 {@link RenderedSql#displaySql()}，
 * 两者**不共用**——把内联版本拿去执行，等于把注入防护丢了。
 *
 * <p><strong>它不是执行语句。</strong>本测试只断言"看起来对、值被正确转义"，
 * 任何把它传给 JDBC 的用法都是缺陷。
 */
class RenderedSqlDisplayTest {

    private static RenderedSql of(String sql, Object... params) {
        // 用 ArrayList 而不是 List.of：后者不接受 null，而 NULL 参数正是要测的场景之一
        return new RenderedSql(sql, java.util.Arrays.asList(params), List.of(), "t1");
    }

    @Test
    @DisplayName("★ 字符串参数内联成单引号字面量")
    void inlinesString() {
        RenderedSql r = of("SELECT * FROM t WHERE org_path LIKE ?", "/1/100/110/%");

        assertThat(r.displaySql())
                .isEqualTo("SELECT * FROM t WHERE org_path LIKE '/1/100/110/%'");
    }

    @Test
    @DisplayName("数值与布尔不加引号")
    void inlinesNumbersWithoutQuotes() {
        assertThat(of("WHERE a = ? AND b = ?", 42, new BigDecimal("3.14")).displaySql())
                .isEqualTo("WHERE a = 42 AND b = 3.14");
        assertThat(of("WHERE flag = ?", true).displaySql()).isEqualTo("WHERE flag = true");
    }

    @Test
    @DisplayName("★ 单引号必须翻倍，否则复制出去就是坏 SQL")
    void escapesSingleQuote() {
        RenderedSql r = of("WHERE name = ?", "O'Brien");

        assertThat(r.displaySql()).isEqualTo("WHERE name = 'O''Brien'");
    }

    @Test
    @DisplayName("★ 反斜杠必须转义（MySQL 默认把 \\ 当转义符）")
    void escapesBackslash() {
        RenderedSql r = of("WHERE p LIKE ?", "C:\\temp");

        assertThat(r.displaySql()).isEqualTo("WHERE p LIKE 'C:\\\\temp'");
    }

    @Test
    @DisplayName("日期按字面量加引号")
    void inlinesDate() {
        assertThat(of("WHERE d >= ?", LocalDate.of(2026, 1, 1)).displaySql())
                .isEqualTo("WHERE d >= '2026-01-01'");
    }

    @Test
    @DisplayName("多个参数按顺序替换，不串位")
    void replacesInOrder() {
        RenderedSql r = of("WHERE a = ? AND b = ? AND c = ?", "x", 2, "y");

        assertThat(r.displaySql()).isEqualTo("WHERE a = 'x' AND b = 2 AND c = 'y'");
    }

    @Test
    @DisplayName("没有参数时原样返回，不做任何改写")
    void noParamsReturnsInput() {
        RenderedSql r = of("SELECT count(*) FROM t");

        assertThat(r.displaySql()).isEqualTo("SELECT count(*) FROM t");
    }

    @Test
    @DisplayName("参数化版本保持不变——执行路径不能受影响")
    void parameterizedSqlIsUntouched() {
        RenderedSql r = of("WHERE org_path LIKE ?", "/1/10/%");

        assertThat(r.sql()).as("执行用的仍是占位符").isEqualTo("WHERE org_path LIKE ?");
        assertThat(r.params()).containsExactly("/1/10/%");
    }
}

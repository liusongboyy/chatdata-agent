package com.liusongboyy.chatdata.engine.eval;

import com.liusongboyy.chatdata.engine.datasource.QueryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结果集比对的规则。**这是评测的可信度来源**——
 * 比对写松了会把错答案算对，写紧了会把正确结果算错。
 */
class ResultSetComparatorTest {

    private static QueryResult result(List<String> columns, List<List<Object>> rows) {
        return new QueryResult(columns, rows);
    }

    @Test
    @DisplayName("行序无关：同样的行换个顺序仍然一致")
    void unorderedRowsMatch() {
        QueryResult gold = result(List.of("status", "cnt"), List.of(
                List.of("OPEN", 2), List.of("CLOSED", 1)));
        QueryResult actual = result(List.of("status", "cnt"), List.of(
                List.of("CLOSED", 1), List.of("OPEN", 2)));

        assertThat(ResultSetComparator.compare(gold, actual, false, true).equal()).isTrue();
    }

    @Test
    @DisplayName("声明 ordered 后行序参与比对——LIMIT 取前 N 名时顺序就是语义")
    void orderedRowsMatter() {
        QueryResult gold = result(List.of("title"), List.of(List.of("A"), List.of("B")));
        QueryResult actual = result(List.of("title"), List.of(List.of("B"), List.of("A")));

        assertThat(ResultSetComparator.compare(gold, actual, true, true).equal()).isFalse();
    }

    @Test
    @DisplayName("数值归一：2、2.0、2.0000 是同一个数")
    void numericScaleIgnored() {
        QueryResult gold = result(List.of("cnt"), List.of(List.of(new BigDecimal("2.0000"))));
        QueryResult actual = result(List.of("cnt"), List.of(List.of(2L)));

        assertThat(ResultSetComparator.compare(gold, actual, false, true).equal()).isTrue();
    }

    @Test
    @DisplayName("列序默认无关：(status, cnt) 与 (cnt, status) 是同一份答案")
    void columnOrderIgnoredByDefault() {
        QueryResult gold = result(List.of("status", "cnt"), List.of(List.of("OPEN", 2)));
        QueryResult actual = result(List.of("cnt", "status"), List.of(List.of(2, "OPEN")));

        assertThat(ResultSetComparator.compare(gold, actual, false, true).equal()).isTrue();
    }

    @Test
    @DisplayName("声明 STRICT 后列序参与比对")
    void strictColumnOrder() {
        QueryResult gold = result(List.of("status", "cnt"), List.of(List.of("OPEN", 2)));
        QueryResult actual = result(List.of("cnt", "status"), List.of(List.of(2, "OPEN")));

        assertThat(ResultSetComparator.compare(gold, actual, false, false).equal()).isFalse();
    }

    @Test
    @DisplayName("行数不同即不一致——多一行或少一行都是口径问题")
    void rowCountMismatch() {
        QueryResult gold = result(List.of("cnt"), List.of(List.of(1), List.of(2)));
        QueryResult actual = result(List.of("cnt"), List.of(List.of(2)));

        ResultSetComparator.Verdict v = ResultSetComparator.compare(gold, actual, false, true);
        assertThat(v.equal()).isFalse();
        assertThat(v.reason()).contains("行");
    }

    @Test
    @DisplayName("列数不同即不一致")
    void columnCountMismatch() {
        QueryResult gold = result(List.of("a", "b"), List.of(List.of(1, 2)));
        QueryResult actual = result(List.of("a"), List.of(List.of(1)));

        ResultSetComparator.Verdict v = ResultSetComparator.compare(gold, actual, false, true);
        assertThat(v.equal()).isFalse();
        assertThat(v.reason()).contains("列数不同");
    }

    @Test
    @DisplayName("包含式 EX：多返回的列不算错，但 gold 的列必须匹配上")
    void extraColumnsAllowedInContainsMode() {
        QueryResult gold = result(List.of("cnt"), List.of(List.of(3)));
        QueryResult verbose = result(List.of("cnt", "note"), List.of(List.of(3, "说明")));

        assertThat(ResultSetComparator.compare(gold, verbose, false, true, false).equal())
                .as("严格 EX：多一列就是不一致").isFalse();
        assertThat(ResultSetComparator.compare(gold, verbose, false, true, true).equal())
                .as("包含式 EX：数据对，话多不算错").isTrue();

        QueryResult wrong = result(List.of("cnt", "note"), List.of(List.of(4, "说明")));
        assertThat(ResultSetComparator.compare(gold, wrong, false, true, true).equal())
                .as("多列不能掩盖核心数值错误").isFalse();
    }

    @Test
    @DisplayName("两边都是空结果 → 一致（没数据也是答案）")
    void bothEmpty() {
        assertThat(ResultSetComparator.compare(
                result(List.of("a"), List.of()), result(List.of(), List.of()),
                false, true).equal()).isTrue();
    }

    @Test
    @DisplayName("NULL 与空串不是一回事")
    void nullIsNotNullString() {
        QueryResult gold = result(List.of("x"), List.of(rowsWithNull()));
        QueryResult actual = result(List.of("x"),
                List.of(java.util.Collections.<Object>singletonList("")));

        assertThat(ResultSetComparator.compare(gold, actual, false, true).equal()).isFalse();
    }

    @Test
    @DisplayName("日期时间按 ISO 形状归一，Timestamp 与 LocalDateTime 一致")
    void timestampNormalized() {
        QueryResult gold = result(List.of("t"),
                List.of(List.of(Timestamp.valueOf("2026-08-19 00:06:33"))));
        QueryResult actual = result(List.of("t"),
                List.of(List.of("2026-08-19T00:06:33")));

        assertThat(ResultSetComparator.compare(gold, actual, false, true).equal()).isTrue();
    }

    private static List<Object> rowsWithNull() {
        return java.util.Collections.singletonList(null);
    }

    @Test
    @DisplayName("★ 浮点求和顺序不同不算答错——末位噪音不是数据差异")
    void floatAccumulationTolerance() {
        QueryResult gold = new QueryResult(List.of("name", "amt"),
                List.of(List.of("Advanced Bicycles", 25793.748)));
        QueryResult actual = new QueryResult(List.of("name", "amt"),
                List.of(List.of("Advanced Bicycles", 25793.747999999984)));

        assertThat(ResultSetComparator.compare(gold, actual, false, true, false).equal())
                .as("同一个数的两种 double 表示")
                .isTrue();
    }

    @Test
    @DisplayName("真实的计算差异仍然判错——容差不能宽到放过错误")
    void realDifferenceStillFails() {
        QueryResult gold = new QueryResult(List.of("amt"), List.of(List.of(25793.748)));
        QueryResult actual = new QueryResult(List.of("amt"), List.of(List.of(25793.758)));

        assertThat(ResultSetComparator.compare(gold, actual, false, true, false).equal())
                .as("相对差 4e-7 已超 6 位有效数字")
                .isFalse();
    }
}

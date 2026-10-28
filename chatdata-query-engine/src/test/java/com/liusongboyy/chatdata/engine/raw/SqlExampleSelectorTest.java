package com.liusongboyy.chatdata.engine.raw;

import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;
import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.Term;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 范例检索。
 *
 * <p>核心取舍是**宁缺勿滥**：给一条不相关的范例，模型会照着它的形状写，而那个形状
 * 与本问题无关——比不给范例更糟。所以这里既测"该挑中的挑中"，也测"不相关的一条都不给"。
 */
class SqlExampleSelectorTest {

    private static Domain domain() {
        RegisteredTable policy = new RegisteredTable("1", "ins_policy", "保单表",
                RegisteredTable.Mode.TABLE, null, true, List.of(
                new ColumnDef("premium", "decimal", Aggregation.SUM, "保费", true),
                new ColumnDef("region", "varchar", Aggregation.NONE, "区域", true)));

        Term valid = new Term("有效", List.of("生效", "在保"), "status='生效'", Term.ApplyKind.NONE);
        Term large = new Term("大额保单", List.of("高额保单"), "premium > 10000", Term.ApplyKind.NONE);

        return new Domain("d1", "直连测试域", "1", "MYSQL5.7", true, List.of(policy), List.of(),
                DomainPermission.from(null), List.of(valid, large), List.of(), Domain.Mode.RAW);
    }

    private static SqlExample ex(long id, String question, String sql) {
        return new SqlExample(id, 1, question, sql, null, "MANUAL", true);
    }

    private static final SqlExample BY_REGION =
            ex(1, "各区域的保费合计是多少", "SELECT region, SUM(premium) FROM ins_policy GROUP BY region");
    private static final SqlExample BY_MONTH =
            ex(2, "每月的保费趋势", "SELECT DATE_FORMAT(sign_date,'%Y-%m') m, SUM(premium) FROM ins_policy GROUP BY 1");
    private static final SqlExample VALID_COUNT =
            ex(3, "有效保单有多少张", "SELECT COUNT(*) FROM ins_policy WHERE status='生效'");
    private static final SqlExample UNRELATED =
            ex(4, "客户的注册城市分布", "SELECT city, COUNT(*) FROM ins_customer GROUP BY city");

    private static List<SqlExample> all() {
        return List.of(BY_REGION, BY_MONTH, VALID_COUNT, UNRELATED);
    }

    @Nested
    @DisplayName("挑得中")
    class Matches {

        @Test
        @DisplayName("★ 同构问题挑中对应范例")
        void similarIsSelected() {
            List<SqlExample> picked = SqlExampleSelector.select(
                    all(), "按区域看保费的合计是多少", domain());

            assertThat(picked).isNotEmpty();
            assertThat(picked.get(0).id()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 术语归一：用户说「在保」，范例说「有效」，仍能匹配")
        void termNormalization() {
            // 不归一时，「在保」与「有效保单有多少张」没有共同字面
            List<SqlExample> picked = SqlExampleSelector.select(
                    all(), "在保的保单有几张", domain());

            assertThat(picked).extracting(SqlExample::id).contains(3L);
        }

        @Test
        @DisplayName("给多条的按相似度降序，最多 limit 条")
        void respectsLimit() {
            List<SqlExample> picked = SqlExampleSelector.select(
                    all(), "各区域每月的保费合计", domain(), 2);

            assertThat(picked).hasSizeLessThanOrEqualTo(2);
        }
    }

    @Nested
    @DisplayName("宁缺勿滥")
    class DoesNotOverServe {

        @Test
        @DisplayName("★ 完全不相关的问题 → 一条都不给")
        void unrelatedGivesNothing() {
            assertThat(SqlExampleSelector.select(
                    all(), "今天天气怎么样", domain())).isEmpty();
        }

        @Test
        @DisplayName("关闭的范例不参与")
        void disabledIsSkipped() {
            SqlExample disabled = new SqlExample(9, 1, "各区域的保费合计是多少",
                    "SELECT 1", null, "MANUAL", false);

            assertThat(SqlExampleSelector.select(
                    List.of(disabled), "各区域的保费合计是多少", domain())).isEmpty();
        }

        @Test
        @DisplayName("空范例库 / 空问题都安全返回空")
        void emptyInputs() {
            assertThat(SqlExampleSelector.select(List.of(), "各区域的保费", domain())).isEmpty();
            assertThat(SqlExampleSelector.select(all(), "", domain())).isEmpty();
            assertThat(SqlExampleSelector.select(all(), null, domain())).isEmpty();
        }
    }

    @Test
    @DisplayName("★ 排序必须可复现 —— 同分时按 id，否则同一问题两次给出不同 prompt")
    void deterministicOrdering() {
        SqlExample a = ex(20, "各区域保费合计", "SELECT 1");
        SqlExample b = ex(10, "各区域保费合计", "SELECT 2");

        List<SqlExample> first = SqlExampleSelector.select(List.of(a, b), "各区域保费合计", domain());
        List<SqlExample> second = SqlExampleSelector.select(List.of(b, a), "各区域保费合计", domain());

        assertThat(first).extracting(SqlExample::id).isEqualTo(second.stream().map(SqlExample::id).toList());
        assertThat(first.get(0).id()).isEqualTo(10);
    }

    @Test
    @DisplayName("过长的 SQL 进 prompt 前截断 —— 多层 CTE 会撑爆 prompt")
    void truncatesLongSql() {
        String longSql = "SELECT " + "a,".repeat(1000) + "b FROM t";
        String out = SqlExampleSelector.truncateSql(longSql);

        assertThat(out.length()).isLessThan(longSql.length());
        assertThat(out).contains("已截断");
        assertThat(SqlExampleSelector.truncateSql(null)).isEmpty();
    }

    @Test
    @DisplayName("★ 关键词命中即强相关 —— 实测字面分不开时靠它")
    void keywordHitWins() {
        // 实测：这两句的字面 cosine 只有 0.096，而一句不相关的有 0.057，阈值分不开。
        // 管理员打上「分档」标签之后，命中即入选
        SqlExample byTier = new SqlExample(5, 1,
                "保费按档位分布：大额(>=10000)、中等(1000-10000)、小额(<1000)各有几张",
                "SELECT CASE WHEN premium>=10000 THEN '大额' END AS tier, COUNT(*) FROM ins_policy GROUP BY tier",
                null, "分档、档位、大额", "MANUAL", true);

        List<SqlExample> picked = SqlExampleSelector.select(
                List.of(byTier), "华东的大额保单和中额保单各有多少张", domain());

        assertThat(picked).extracting(SqlExample::id).contains(5L);
    }

    @Test
    @DisplayName("没命中关键词、字面也不够像时不入选 —— 关键词不是万能通行证")
    void keywordMissStillNeedsLiteralOverlap() {
        SqlExample byCity = new SqlExample(6, 1, "客户注册城市分布",
                "SELECT city, COUNT(*) FROM ins_customer GROUP BY city",
                null, "城市、地区", "MANUAL", true);

        assertThat(SqlExampleSelector.select(
                List.of(byCity), "上个月的保费合计是多少", domain())).isEmpty();
    }
}

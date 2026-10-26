package com.liusongboyy.chatdata.engine.nlsql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liusongboyy.chatdata.engine.datasource.dialect.SqlDialect;
import com.liusongboyy.chatdata.engine.datasource.dialect.SqlDialects;
import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import com.liusongboyy.chatdata.engine.permission.PermissionContext;
import com.liusongboyy.chatdata.engine.permission.PermissionDecision;
import com.liusongboyy.chatdata.engine.permission.PermissionGuard;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;
import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.SemanticException;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 排序 / Top-N、占比、同环比的渲染。
 *
 * <h2>为什么每一条都要钉住 SQL 形状</h2>
 *
 * <p>这三类能力都不是"跑起来就行"：排序方向反了会给最差的 N 个、
 * 占比的分母错了会给出>100%的数、同环比的时间窗错位会让增长率看起来正常但全错。
 * 它们全都**不报错**，只是答案不对——所以只测"没有异常"没有意义。
 */
class AnalyticsDslRenderTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);

    private static DomainPermission permOf(String json) {
        try {
            return DomainPermission.from(M.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException("夹具 JSON 写错：" + json, e);
        }
    }

    private static DomainPermission open() {
        return permOf("""
                {"v":1,"grantRoles":["分析师"],"unrestrictedRoles":["分析师"]}""");
    }

    private static PermissionDecision.Allowed allowed(DomainPermission perm) {
        PermissionDecision d = PermissionGuard.check(
                null, perm, PermissionContext.of("u1", Set.of("分析师")));
        assertThat(d.isAllowed()).as("夹具本身应当放行").isTrue();
        return (PermissionDecision.Allowed) d;
    }

    private static SemanticModel model() {
        RegisteredTable orders = new RegisteredTable("tbl-order", "f_order", "订单表",
                RegisteredTable.Mode.TABLE, null, true, List.of(
                new ColumnDef("order_date", "date", Aggregation.NONE, "统计日期", true),
                new ColumnDef("region", "varchar", Aggregation.NONE, "区域", true),
                new ColumnDef("amount", "decimal", Aggregation.SUM, "订单金额", true),
                new ColumnDef("id", "bigint", Aggregation.COUNT, "订单数", true)));

        Domain domain = new Domain("d1", "订单域", "ds1", "MYSQL5.7", true,
                List.of(orders), List.of(), open(), List.of(), List.of());
        return new SemanticModel(domain);
    }

    /** 2025 全年，右开 → [2025-01-01, 2026-01-01)。 */
    private static AskDsl.TimeRange year2025() {
        return new AskDsl.TimeRange("自定义", "2025-01-01", "2025-12-31");
    }

    private static RenderedSql render(AskDsl dsl, String dbType) {
        return AskSqlRenderer.render(dsl, model(), SqlDialects.of(dbType),
                allowed(open()), TODAY);
    }

    private static AskDsl dsl(List<String> metrics, List<String> dims,
                              AskDsl.Granularity g, List<AskDsl.Sort> sort, Integer limit,
                              List<String> shareOf, AskDsl.Compare compare) {
        return new AskDsl(metrics, dims, null, List.of(), g, sort, limit, shareOf, compare);
    }

    @Nested
    @DisplayName("排序与 Top-N")
    class Sorting {

        @Test
        @DisplayName("★ 按指标倒序取前 5：ORDER BY 输出列名 + LIMIT 5")
        void topN() {
            RenderedSql r = render(dsl(List.of("订单金额"), List.of("区域"),
                    AskDsl.Granularity.DAY,
                    List.of(new AskDsl.Sort("订单金额", "desc")), 5, List.of(), null), "MYSQL5.7");

            assertThat(r.sql())
                    .contains("ORDER BY `订单金额` DESC")
                    .endsWith("LIMIT 5");
        }

        @Test
        @DisplayName("SQL Server 用 TOP 前缀，不出现 LIMIT")
        void sqlServerTop() {
            RenderedSql r = render(dsl(List.of("订单金额"), List.of("区域"),
                    AskDsl.Granularity.DAY,
                    List.of(new AskDsl.Sort("订单金额", "desc")), 5, List.of(), null), "SQLSERVER");

            assertThat(r.sql()).startsWith("SELECT TOP 5 ").doesNotContain("LIMIT");
        }

        @Test
        @DisplayName("★ limit 只能收紧，不能突破系统上限")
        void limitIsCapped() {
            RenderedSql r = render(dsl(List.of("订单金额"), List.of("区域"),
                    AskDsl.Granularity.DAY, List.of(), 5000, List.of(), null), "MYSQL5.7");

            assertThat(r.sql()).endsWith("LIMIT 1000");
        }

        @Test
        @DisplayName("没排序时不出 ORDER BY —— 顺序不该由我们凭空决定")
        void noOrderByWithoutSort() {
            assertThat(render(dsl(List.of("订单金额"), List.of("区域"),
                    AskDsl.Granularity.DAY, List.of(), null, List.of(), null), "MYSQL5.7").sql())
                    .doesNotContain("ORDER BY");
        }

        @Test
        @DisplayName("排一个不存在的列 → 拒绝并列出可用列，而不是静默忽略")
        void unknownSortFieldRejected() {
            assertThatThrownBy(() -> render(dsl(List.of("订单金额"), List.of("区域"),
                    AskDsl.Granularity.DAY,
                    List.of(new AskDsl.Sort("不存在的列", "desc")), 5, List.of(), null), "MYSQL5.7"))
                    .isInstanceOf(SemanticException.class)
                    .hasMessageContaining("不在本次查询的输出里")
                    .hasMessageContaining("订单金额");
        }
    }

    @Nested
    @DisplayName("占比")
    class Share {

        @Test
        @DisplayName("★ 分母是结果总和：SUM(SUM(x)) OVER ()，不是某一行")
        void shareOfTotal() {
            RenderedSql r = render(dsl(List.of("订单金额"), List.of("区域"),
                    AskDsl.Granularity.DAY, List.of(), null, List.of("订单金额"), null), "MYSQL5.7");

            assertThat(r.sql())
                    .contains("SUM(SUM(`t1`.`amount`)) OVER ()")
                    .contains("NULLIF(")
                    .contains("AS `订单金额占比`");
            assertThat(r.columns().stream().map(c -> c.name()))
                    .containsExactly("区域", "订单金额", "订单金额占比");
        }

        @Test
        @DisplayName("占比指标被脱敏时整列也脱敏 —— 否则能反推相对分布")
        void maskedShareIsMasked() {
            DomainPermission maskedPerm = permOf("""
                    {"v":1,"grantRoles":["分析师"],"unrestrictedRoles":["分析师"],
                     "colRules":[{"id":1,"role":"分析师","field":"amount","action":"脱敏"}]}""");

            RenderedSql r = AskSqlRenderer.render(dsl(List.of("订单金额"), List.of("区域"),
                            AskDsl.Granularity.DAY, List.of(), null, List.of("订单金额"), null),
                    model(), SqlDialects.of("MYSQL5.7"), allowed(maskedPerm), TODAY);

            assertThat(r.sql()).doesNotContain("OVER ()");
            assertThat(r.maskedColumnNames()).contains("订单金额", "订单金额占比");
        }

        @Test
        @DisplayName("shareOf 里写了不是本次指标的字段 → 拒绝")
        void shareOfUnknownMetric() {
            assertThatThrownBy(() -> render(dsl(List.of("订单金额"), List.of("区域"),
                    AskDsl.Granularity.DAY, List.of(), null, List.of("订单数"), null), "MYSQL5.7"))
                    .isInstanceOf(SemanticException.class)
                    .hasMessageContaining("shareOf");
        }
    }

    @Nested
    @DisplayName("同比 / 环比")
    class Compare {

        @Test
        @DisplayName("★ 按月同比：上期行平移一年 + 两个时间窗 + 本期/上期/同比三列")
        void yearOverYearMonthly() {
            AskDsl dsl = new AskDsl(List.of("订单金额"), List.of("统计日期"), year2025(),
                    List.of(), AskDsl.Granularity.MONTH, List.of(), null, List.of(),
                    AskDsl.Compare.YOY);

            RenderedSql r = render(dsl, "MYSQL5.7");

            assertThat(r.sql())
                    .as("上一期的行必须平移到本期时间轴，否则 2024-03 与 2025-03 是两个组")
                    .contains("CASE WHEN `t1`.`order_date` < ? THEN DATE_ADD(`t1`.`order_date`, INTERVAL 1 YEAR)")
                    .as("本期与上期要一起取")
                    .contains("OR (`t1`.`order_date` >= ? AND `t1`.`order_date` < ?)")
                    .contains("AS `订单金额`")
                    .contains("AS `订单金额上期`")
                    .contains("AS `订单金额同比`")
                    .contains("/ NULLIF(");
            assertThat(r.columns().stream().map(c -> c.name()))
                    .containsExactly("统计日期", "订单金额", "订单金额上期", "订单金额同比");
        }

        @Test
        @DisplayName("两个窗口的参数都进了 params，且顺序是 本期起、本期止、上期起、上期止")
        void compareParamsCarryBothWindows() {
            AskDsl dsl = new AskDsl(List.of("订单金额"), List.of("统计日期"), year2025(),
                    List.of(), AskDsl.Granularity.MONTH, List.of(), null, List.of(),
                    AskDsl.Compare.YOY);

            List<Object> params = render(dsl, "MYSQL5.7").params();

            assertThat(params).containsSubsequence(
                    LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1),
                    LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1));
            assertThat(params.get(params.size() - 1))
                    .as("最后一个参数是 GROUP BY 里那份 CASE 的——同一表达式出现两次，参数也要两次")
                    .isEqualTo(LocalDate.of(2025, 1, 1));
        }

        @Test
        @DisplayName("★ 环比按天、PostgreSQL：平移用 INTERVAL '1 month'")
        void monthOverMonthPg() {
            AskDsl dsl = new AskDsl(List.of("订单金额"), List.of("统计日期"), year2025(),
                    List.of(), AskDsl.Granularity.DAY, List.of(), null, List.of(),
                    AskDsl.Compare.MOM);

            RenderedSql r = render(dsl, "POSTGRESQL");

            assertThat(r.sql())
                    .contains("+ INTERVAL '1 month'")
                    .contains("AS \"订单金额环比\"");
        }

        @Test
        @DisplayName("没有时间范围 → 拒绝：无从'与上期比'，猜一个会给出用户没问过的对比")
        void compareNeedsTimeRange() {
            AskDsl dsl = new AskDsl(List.of("订单金额"), List.of("统计日期"), null,
                    List.of(), AskDsl.Granularity.MONTH, List.of(), null, List.of(),
                    AskDsl.Compare.YOY);

            assertThatThrownBy(() -> render(dsl, "MYSQL5.7"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("需要明确的时间范围");
        }

        @Test
        @DisplayName("★ 按天做同比 → 拒绝：2 月 29 日平移后会撞组，增长率静默算错")
        void yearOverYearRejectsDayGranularity() {
            AskDsl dsl = new AskDsl(List.of("订单金额"), List.of("统计日期"), year2025(),
                    List.of(), AskDsl.Granularity.DAY, List.of(), null, List.of(),
                    AskDsl.Compare.YOY);

            assertThatThrownBy(() -> render(dsl, "MYSQL5.7"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("按月或按年");
        }

        @Test
        @DisplayName("没有时间维度时，粒度无关：语义是'本期总额 vs 上期总额'")
        void compareWithoutTimeDimension() {
            AskDsl dsl = new AskDsl(List.of("订单金额"), List.of(), year2025(),
                    List.of(), AskDsl.Granularity.DAY, List.of(), null, List.of(),
                    AskDsl.Compare.YOY);

            RenderedSql r = render(dsl, "MYSQL5.7");

            assertThat(r.sql())
                    .doesNotContain("CASE WHEN `t1`.`order_date` < ?")
                    .contains("AS `订单金额同比`")
                    .contains("OR (`t1`.`order_date` >= ? AND `t1`.`order_date` < ?)");
        }

        @Test
        @DisplayName("脱敏指标的三个同环比列全部是 ***，不能只遮住本期")
        void maskedCompare() {
            DomainPermission maskedPerm = permOf("""
                    {"v":1,"grantRoles":["分析师"],"unrestrictedRoles":["分析师"],
                     "colRules":[{"id":1,"role":"分析师","field":"amount","action":"脱敏"}]}""");
            AskDsl dsl = new AskDsl(List.of("订单金额"), List.of("统计日期"), year2025(),
                    List.of(), AskDsl.Granularity.MONTH, List.of(), null, List.of(),
                    AskDsl.Compare.YOY);

            RenderedSql r = AskSqlRenderer.render(dsl, model(), SqlDialects.of("MYSQL5.7"),
                    allowed(maskedPerm), TODAY);

            assertThat(r.maskedColumnNames())
                    .contains("订单金额", "订单金额上期", "订单金额同比");
        }
    }

    @Nested
    @DisplayName("时间平移的方言写法")
    class Interval {

        @Test
        @DisplayName("四种库各有各的写法，且都不是手拼的")
        void dialectSyntax() {
            assertThat(SqlDialects.of("MYSQL5.7").addInterval("MONTH", "x", 1))
                    .isEqualTo("DATE_ADD(x, INTERVAL 1 MONTH)");
            assertThat(SqlDialects.of("POSTGRESQL").addInterval("MONTH", "x", 1))
                    .isEqualTo("(x + INTERVAL '1 month')");
            assertThat(SqlDialects.of("ORACLE").addInterval("YEAR", "x", 1))
                    .as("Oracle 用 ADD_MONTHS 才能正确处理月末")
                    .isEqualTo("ADD_MONTHS(x, 12)");
            assertThat(SqlDialects.of("SQLSERVER").addInterval("DAY", "x", -1))
                    .isEqualTo("DATEADD(day, -1, x)");
        }

        @Test
        @DisplayName("不认识的单位 → 抛异常，不猜")
        void rejectsUnknownUnit() {
            assertThatThrownBy(() -> SqlDialects.of("MYSQL5.7").addInterval("HOUR", "x", 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}

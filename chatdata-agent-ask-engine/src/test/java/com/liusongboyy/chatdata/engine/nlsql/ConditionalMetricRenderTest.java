package com.liusongboyy.chatdata.engine.nlsql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liusongboyy.chatdata.engine.datasource.dialect.SqlDialects;
import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import com.liusongboyy.chatdata.engine.permission.PermissionContext;
import com.liusongboyy.chatdata.engine.permission.PermissionDecision;
import com.liusongboyy.chatdata.engine.permission.PermissionGuard;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;
import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.ConditionTerm;
import com.liusongboyy.chatdata.engine.semantic.DerivedMetric;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.MetricCondition;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.SemanticException;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import com.liusongboyy.chatdata.engine.semantic.TableJoin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 带统计条件的指标。
 *
 * <h2>为什么必须钉住 SQL 形状</h2>
 *
 * <p>条件写错（漏掉、包错层、参数错位）都不会报错：SQL 合法、执行成功，
 * 只是数字变成了"全量"——「已解决工单数」等于「工单数」时，用户看不出差别。
 *
 * <p>另外两条要害：
 * <ul>
 *   <li><b>条件只能用 CASE，不能进 WHERE</b>——进了 WHERE 会连分母一起过滤，
 *       「已解决占比」就永远是 100%</li>
 *   <li><b>条件字段可能跨表</b>——维表上的状态列必须被 JOIN 进来</li>
 * </ul>
 */
class ConditionalMetricRenderTest {

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

    /** 工单表 + 分类维表，条件字段在维表上。 */
    private static SemanticModel model() {
        RegisteredTable tickets = new RegisteredTable("tbl-ticket", "ticket", "工单表",
                RegisteredTable.Mode.TABLE, null, true, List.of(
                new ColumnDef("ticket_no", "varchar", Aggregation.COUNT, "工单数", true),
                new ColumnDef("status", "varchar", Aggregation.NONE, "状态", true),
                new ColumnDef("created_at", "datetime", Aggregation.NONE, "生效日期", true),
                new ColumnDef("category_id", "bigint", Aggregation.NONE, "分类ID", true),
                new ColumnDef("ticket_no", "varchar", Aggregation.COUNT, "已解决工单数", true,
                        null, new MetricCondition("状态", "=", "已解决")),
                new ColumnDef("ticket_no", "varchar", Aggregation.COUNT, "分类已解决工单数", true,
                        null, new MetricCondition("分类名称", "=", "支付")),
                new ColumnDef("priority", "int", Aggregation.NONE, "优先级", true),
                new ColumnDef("closed_at", "datetime", Aggregation.NONE, "关闭时间", true),
                new ColumnDef("sla_deadline", "datetime", Aggregation.NONE, "SLA截止时间", true),
                new ColumnDef("ticket_no", "varchar", Aggregation.COUNT, "高优已解决工单数", true,
                        null, new MetricCondition(MetricCondition.Logic.AND, List.of(
                                new ConditionTerm("状态", "=", "RESOLVED", null),
                                new ConditionTerm("优先级", ">", "2", null)))),
                new ColumnDef("ticket_no", "varchar", Aggregation.COUNT, "未完成工单数", true,
                        null, new MetricCondition(MetricCondition.Logic.OR, List.of(
                                new ConditionTerm("状态", "=", "OPEN", null),
                                new ConditionTerm("状态", "=", "IN_PROGRESS", null)))),
                new ColumnDef("ticket_no", "varchar", Aggregation.COUNT, "超时关闭工单数", true,
                        null, new MetricCondition("关闭时间", ">", null, "SLA截止时间")),
                new ColumnDef(null, "decimal", Aggregation.NONE, "已解决占比", true,
                        new DerivedMetric(DerivedMetric.Kind.RATIO,
                                "已解决工单数", "工单数", null, null, null), null)));

        RegisteredTable categories = new RegisteredTable("tbl-category", "ticket_category", "分类表",
                RegisteredTable.Mode.TABLE, null, true, List.of(
                new ColumnDef("name", "varchar", Aggregation.NONE, "分类名称", true)));

        Domain domain = new Domain("d1", "工单域", "ds1", "MYSQL5.7", true,
                List.of(tickets, categories),
                List.of(new TableJoin(1L, "tbl-ticket", "tbl-category",
                        "category_id", "id", TableJoin.JoinType.INNER)),
                open(), List.of(), List.of());
        return new SemanticModel(domain);
    }

    private static RenderedSql render(String metric) {
        return render(List.of(metric), List.of(), AskDsl.Granularity.DAY, null, null);
    }

    private static RenderedSql render(List<String> metrics, List<String> dims,
                                      AskDsl.Granularity g, AskDsl.TimeRange time,
                                      AskDsl.Compare compare) {
        AskDsl dsl = new AskDsl(metrics, dims, time, List.of(), g,
                List.of(), null, List.of(), compare);
        return AskSqlRenderer.render(dsl, model(), SqlDialects.of("MYSQL5.7"), allowed(open()), TODAY);
    }

    @Nested
    @DisplayName("渲染")
    class Render {

        @Test
        @DisplayName("★ 条件渲染成聚合里的 CASE，不是 WHERE")
        void conditionBecomesCase() {
            RenderedSql r = render("已解决工单数");

            assertThat(r.sql())
                    .contains("COUNT(CASE WHEN `t2`.`status` = ? THEN `t2`.`ticket_no` END)")
                    .as("进了 WHERE 就会把分母一起过滤掉")
                    .doesNotContain("WHERE `t1`.`status`");
            assertThat(r.params()).containsExactly("已解决");
        }

        @Test
        @DisplayName("★ 条件字段在维表上时，维表被 JOIN 进来")
        void crossTableConditionPullsJoin() {
            RenderedSql r = render("分类已解决工单数");

            assertThat(r.sql())
                    .contains("JOIN `ticket_category` `t1`")
                    .contains("CASE WHEN `t1`.`name` = ?");
            assertThat(r.params()).containsExactly("支付");
        }

        @Test
        @DisplayName("★ 派生指标引用带条件的指标时，条件不能丢")
        void derivedKeepsCondition() {
            RenderedSql r = render("已解决占比");

            assertThat(r.sql())
                    .contains("COUNT(CASE WHEN `t2`.`status` = ? THEN `t2`.`ticket_no` END)")
                    .contains("NULLIF(COUNT(`t2`.`ticket_no`), 0)");
        }

        @Test
        @DisplayName("带条件的指标与其他指标并存时，互不影响")
        void coexistsWithPlainMetric() {
            RenderedSql r = render(List.of("已解决工单数", "工单数"), List.of(),
                    AskDsl.Granularity.DAY, null, null);

            assertThat(r.sql())
                    .contains("COUNT(CASE WHEN `t2`.`status` = ? THEN `t2`.`ticket_no` END) AS `已解决工单数`")
                    .contains("COUNT(`t2`.`ticket_no`) AS `工单数`");
        }

        @Test
        @DisplayName("★ 同环比 + 条件：窗口 CASE 在外、条件 CASE 在内，参数顺序不能反")
        void compareAndCondition() {
            RenderedSql r = render(List.of("已解决工单数"), List.of("生效日期"),
                    AskDsl.Granularity.MONTH,
                    new AskDsl.TimeRange("自定义", "2025-01-01", "2025-12-31"),
                    AskDsl.Compare.yoy());

            assertThat(r.sql()).contains("CASE WHEN `t2`.`created_at` >= ? AND `t2`.`created_at` < ? "
                    + "THEN CASE WHEN `t2`.`status` = ? THEN `t2`.`ticket_no` END END");
            // 第 0 个参数是维度那个平移 CASE 的；紧接着是本期指标的「窗口、条件」
            assertThat(r.params().subList(1, 4))
                    .as("占位符在文本里是「窗口、条件」，参数顺序必须一致")
                    .containsExactly(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1), "已解决");
        }

        @Test
        @DisplayName("★ 多条件 AND：整体加括号，两个取值都进参数")
        void andGroup() {
            RenderedSql r = render("高优已解决工单数");

            assertThat(r.sql()).contains(
                    "CASE WHEN (`t2`.`status` = ? AND `t2`.`priority` > ?) THEN `t2`.`ticket_no` END");
            assertThat(r.params()).containsExactly("RESOLVED", "2");
        }

        @Test
        @DisplayName("多条件 OR：用 OR 连接")
        void orGroup() {
            RenderedSql r = render("未完成工单数");

            assertThat(r.sql()).contains(
                    "CASE WHEN (`t2`.`status` = ? OR `t2`.`status` = ?) THEN `t2`.`ticket_no` END");
            assertThat(r.params()).containsExactly("OPEN", "IN_PROGRESS");
        }

        @Test
        @DisplayName("★ 字段与字段比较：两侧都是列引用，不产生参数")
        void fieldComparison() {
            RenderedSql r = render("超时关闭工单数");

            assertThat(r.sql())
                    .contains("CASE WHEN `t2`.`closed_at` > `t2`.`sla_deadline` "
                            + "THEN `t2`.`ticket_no` END")
                    .as("列与列比较没有常量，也就没有参数")
                    .doesNotContain("?");
            assertThat(r.params()).isEmpty();
        }

        @Test
        @DisplayName("条件字段被脱敏不影响指标本身；值列被脱敏时整列 ***")
        void maskingOnValueColumn() {
            DomainPermission masked = permOf("""
                    {"v":1,"grantRoles":["分析师"],"unrestrictedRoles":["分析师"],
                     "colRules":[{"id":1,"role":"分析师","field":"ticket_no","action":"脱敏"}]}""");
            AskDsl dsl = new AskDsl(List.of("已解决工单数"), List.of(), null, List.of(),
                    AskDsl.Granularity.DAY, List.of(), null, List.of(), null);

            RenderedSql r = AskSqlRenderer.render(dsl, model(), SqlDialects.of("MYSQL5.7"),
                    allowed(masked), TODAY);

            assertThat(r.sql()).doesNotContain("CASE WHEN `t2`.`status`");
            assertThat(r.maskedColumnNames()).contains("已解决工单数");
        }
    }

    @Nested
    @DisplayName("配置校验")
    class Config {

        @Test
        @DisplayName("维度配条件 → 拒绝：会让分组静默少行")
        void dimensionCannotHaveCondition() {
            assertThatThrownBy(() -> new ColumnDef("status", "varchar", Aggregation.NONE, "状态", true,
                    null, new MetricCondition("状态", "=", "已解决")))
                    .isInstanceOf(SemanticException.class)
                    .hasMessageContaining("条件只对指标有意义");
        }

        @Test
        @DisplayName("派生指标 + 条件 → 拒绝")
        void derivedCannotHaveCondition() {
            assertThatThrownBy(() -> new ColumnDef(null, "decimal", Aggregation.NONE, "占比", true,
                    new DerivedMetric(DerivedMetric.Kind.RATIO, "a", "b", null, null, null),
                    new MetricCondition("状态", "=", "已解决")))
                    .isInstanceOf(SemanticException.class)
                    .hasMessageContaining("不能同时");
        }

        @Test
        @DisplayName("不认识的运算符 → 拒绝，不静默变成不过滤")
        void unknownOperatorRejected() {
            assertThatThrownBy(() -> new MetricCondition("状态", "contains", "已解决"))
                    .isInstanceOf(SemanticException.class)
                    .hasMessageContaining("运算符不受支持");
        }

        @Test
        @DisplayName("条件字段不存在 → 渲染前就报错")
        void unknownConditionField() {
            RegisteredTable t = new RegisteredTable("tbl-x", "ticket", "工单表",
                    RegisteredTable.Mode.TABLE, null, true, List.of(
                    new ColumnDef("ticket_no", "varchar", Aggregation.COUNT, "工单数", true,
                            null, new MetricCondition("不存在的列", "=", "x"))));
            Domain d = new Domain("d1", "域", "ds1", "MYSQL5.7", true, List.of(t),
                    List.of(), open(), List.of(), List.of());

            assertThatThrownBy(() -> AskSqlRenderer.render(
                    new AskDsl(List.of("工单数"), List.of(), null, List.of()),
                    new SemanticModel(d), SqlDialects.of("MYSQL5.7"), allowed(open()), TODAY))
                    .isInstanceOf(SemanticException.class)
                    .hasMessageContaining("找不到字段");
        }
    }
}

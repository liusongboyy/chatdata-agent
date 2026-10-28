package com.liusongboyy.chatdata.engine.nlsql;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 表的默认过滤条件。
 *
 * <h2>它防的是"模型替业务做决定"</h2>
 *
 * <p>实测反复出现：问「2025 年保费合计」，模型自己补 {@code AND status = '生效'}。
 * 这个判断不算离谱，但它是猜的——SQL 合法、数字合理，只是少了三分之一，
 * 而用户看不出差别。
 *
 * <p>配在建模里之后，模型不必猜；渲染器固定拼进 WHERE。
 */
class BaseFilterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);

    private static DomainPermission perm() {
        try {
            return DomainPermission.from(new ObjectMapper().readTree(
                    "{\"v\":1,\"grantRoles\":[\"分析师\"],\"unrestrictedRoles\":[\"分析师\"]}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static PermissionDecision.Allowed allowed() {
        return (PermissionDecision.Allowed) PermissionGuard.check(
                null, perm(), PermissionContext.of("u1", Set.of("分析师")));
    }

    private static SemanticModel model(String orderFilter, String dimFilter) {
        RegisteredTable orders = new RegisteredTable("t1", "ins_policy", "保单表",
                RegisteredTable.Mode.TABLE, null, true, List.of(
                new ColumnDef("premium", "decimal", Aggregation.SUM, "保费", true),
                new ColumnDef("status", "varchar", Aggregation.NONE, "保单状态", true),
                new ColumnDef("product_type", "varchar", Aggregation.NONE, "险种", true)),
                orderFilter);

        RegisteredTable dim = new RegisteredTable("t2", "ins_dim", "维表",
                RegisteredTable.Mode.TABLE, null, true, List.of(
                new ColumnDef("name", "varchar", Aggregation.NONE, "名称", true)),
                dimFilter);

        Domain d = new Domain("d1", "保险域", "ds1", "MYSQL5.7", true,
                List.of(orders, dim),
                List.of(new TableJoin(1L, "t1", "t2", "product_type", "name",
                        TableJoin.JoinType.INNER)),
                perm(), List.of(), List.of());
        return new SemanticModel(d);
    }

    private static RenderedSql render(SemanticModel m, AskDsl dsl) {
        return AskSqlRenderer.render(dsl, m, SqlDialects.of("MYSQL5.7"), allowed(), TODAY);
    }

    private static AskDsl metric(String name) {
        return new AskDsl(List.of(name), List.of(), null, List.of());
    }

    @Nested
    @DisplayName("渲染")
    class Render {

        @Test
        @DisplayName("★ 默认过滤拼进 WHERE，并给裸列名补上表别名")
        void baseFilterApplied() {
            RenderedSql r = render(model("status = '生效'", null), metric("保费"));

            assertThat(r.sql()).contains("(`t1`.`status` = '生效')");
        }

        @Test
        @DisplayName("条件里带 AND / IN / 字符串字面量都正确处理")
        void complexCondition() {
            RenderedSql r = render(
                    model("status IN ('生效', '已过期') AND premium > 0", null), metric("保费"));

            assertThat(r.sql())
                    .contains("`t1`.`status` IN ('生效', '已过期')")
                    .contains("`t1`.`premium` > 0");
        }

        @Test
        @DisplayName("★ 只拼**参与本次查询**的表——没 JOIN 进来的表拼它的条件会 Unknown column")
        void onlyUsedTables() {
            // 只查维表的字段，不碰保单表
            RenderedSql r = render(model("status = '生效'", "name <> 'x'"),
                    new AskDsl(List.of(), List.of("名称"), null, List.of()));

            assertThat(r.sql())
                    .as("保单表没进 FROM，它的条件也不该出现")
                    .doesNotContain("status")
                    .contains("`t2`.`name` <> 'x'");
        }

        @Test
        @DisplayName("没有默认过滤时不产生任何额外条件")
        void noFilterNoClause() {
            RenderedSql r = render(model(null, null), metric("保费"));

            assertThat(r.sql()).doesNotContain("WHERE");
        }

        @Test
        @DisplayName("★ 条件里带占位符 → 拒绝（参数顺序会乱，而且我们没有它的值）")
        void placeholderRejected() {
            assertThatThrownBy(() -> render(model("status = ?", null), metric("保费")))
                    .isInstanceOf(SemanticException.class)
                    .hasMessageContaining("不能带占位符");
        }
    }

    @Nested
    @DisplayName("与其它条件共存")
    class Coexist {

        @Test
        @DisplayName("默认过滤与用户筛选、时间范围共存，都进同一个 WHERE")
        void withUserFilters() {
            AskDsl dsl = new AskDsl(List.of("保费"), List.of(), null,
                    List.of(new AskDsl.Filter("险种", "=", "健康险")));

            RenderedSql r = render(model("status = '生效'", null), dsl);

            assertThat(r.sql())
                    .contains("`t1`.`status` = '生效'")
                    .contains("`t1`.`product_type` = ?");
        }
    }
}

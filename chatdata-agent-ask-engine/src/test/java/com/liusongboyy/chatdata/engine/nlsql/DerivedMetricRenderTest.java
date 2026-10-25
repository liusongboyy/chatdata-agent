package com.liusongboyy.chatdata.engine.nlsql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liusongboyy.chatdata.engine.datasource.dialect.SqlDialects;
import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import com.liusongboyy.chatdata.engine.permission.PermissionContext;
import com.liusongboyy.chatdata.engine.permission.PermissionDecision;
import com.liusongboyy.chatdata.engine.permission.PermissionGuard;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;
import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.DerivedMetric;
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
 * 派生指标：比值 / 差值 / 时长。
 *
 * <h2>为什么这些要单测</h2>
 *
 * <p>派生指标的算错是**静默**的：SQL 语法合法、执行成功，只是数字不对。
 * 而这类指标（赔付率、平均时长）恰恰是业务最常问的。所以每个分支都要钉住 SQL 形状。
 *
 * <p>其中**脱敏传导**是最重要的一条：派生指标由输入列算出，
 * 若输入被脱敏而派生指标照常输出，受限用户就能通过比值反推出被脱敏的金额。
 */
class DerivedMetricRenderTest {

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

    private static AskDsl oneMetric(String name) {
        return new AskDsl(List.of(name), List.of(), null, List.of());
    }

    /** 一张理赔表 + 一张保单表，用来覆盖跨表派生。 */
    private static RegisteredTable claimTable(DerivedMetric... extraDerived) {
        List<ColumnDef> cols = new java.util.ArrayList<>(List.of(
                new ColumnDef("settle_amount", "decimal", Aggregation.SUM, "赔付金额", true),
                new ColumnDef("claim_amount", "decimal", Aggregation.SUM, "申请金额", true),
                new ColumnDef("report_date", "date", Aggregation.NONE, "报案日期", true)));
        for (DerivedMetric d : extraDerived) {
            cols.add(new ColumnDef(null, "decimal", Aggregation.NONE, d.kind() == DerivedMetric.Kind.DURATION
                    ? "平均报案时长" : "赔付率", true, d));
        }
        return new RegisteredTable("tbl-claim", "ins_claim", "理赔表",
                RegisteredTable.Mode.TABLE, null, true, cols);
    }

    private static SemanticModel model(DerivedMetric... derived) {
        RegisteredTable policy = new RegisteredTable("tbl-policy", "ins_policy", "保单表",
                RegisteredTable.Mode.TABLE, null, true, List.of(
                new ColumnDef("premium", "decimal", Aggregation.SUM, "保费", true),
                new ColumnDef("policy_id", "bigint", Aggregation.NONE, "保单ID", true)));

        Domain domain = new Domain("d1", "理赔域", "ds1", "MYSQL5.7", true,
                List.of(claimTable(derived), policy),
                List.of(new TableJoin(1L, "tbl-claim", "tbl-policy",
                        "policy_id", "id", TableJoin.JoinType.INNER)),
                open(), List.of(), List.of());
        return new SemanticModel(domain);
    }

    @Nested
    @DisplayName("比值")
    class Ratio {

        @Test
        @DisplayName("★ SUM(a) / NULLIF(SUM(b), 0)")
        void rendersRatio() {
            SemanticModel m = model(new DerivedMetric(
                    DerivedMetric.Kind.RATIO, "赔付金额", "申请金额", null, null, null));

            RenderedSql r = AskSqlRenderer.render(oneMetric("赔付率"), m,
                    SqlDialects.of("MYSQL5.7"), allowed(open()), TODAY);

            assertThat(r.sql())
                    .contains("SUM(`t1`.`settle_amount`)")
                    .contains("NULLIF(SUM(`t1`.`claim_amount`), 0)")
                    .contains("AS `赔付率`");
        }

        @Test
        @DisplayName("分母为 0 时返回 NULL 而不是报错——不能生成裸除法")
        void dividesSafely() {
            SemanticModel m = model(new DerivedMetric(
                    DerivedMetric.Kind.RATIO, "赔付金额", "申请金额", null, null, null));

            RenderedSql r = AskSqlRenderer.render(oneMetric("赔付率"), m,
                    SqlDialects.of("MYSQL5.7"), allowed(open()), TODAY);

            assertThat(r.sql()).contains("NULLIF");
        }
    }

    @Nested
    @DisplayName("差值")
    class Diff {

        @Test
        @DisplayName("SUM(a) - SUM(b)")
        void rendersDiff() {
            SemanticModel m = model(new DerivedMetric(
                    DerivedMetric.Kind.DIFF, "赔付金额", "申请金额", null, null, null));

            RenderedSql r = AskSqlRenderer.render(oneMetric("赔付率"), m,
                    SqlDialects.of("MYSQL5.7"), allowed(open()), TODAY);

            assertThat(r.sql())
                    .contains("SUM(`t1`.`settle_amount`)")
                    .contains("- ")
                    .contains("SUM(`t1`.`claim_amount`)");
        }
    }

    @Nested
    @DisplayName("时长")
    class Duration {

        @Test
        @DisplayName("★ MySQL 用 TIMESTAMPDIFF，并按 HOUR 取平均")
        void rendersDurationMySql() {
            SemanticModel m = model(new DerivedMetric(
                    DerivedMetric.Kind.DURATION, null, null, "报案日期", "报案日期", "HOUR"));

            RenderedSql r = AskSqlRenderer.render(oneMetric("平均报案时长"), m,
                    SqlDialects.of("MYSQL5.7"), allowed(open()), TODAY);

            assertThat(r.sql())
                    .contains("AVG(TIMESTAMPDIFF(HOUR,")
                    .contains("`t1`.`report_date`");
        }

        @Test
        @DisplayName("★ PostgreSQL 用 EXTRACT(EPOCH ...) 折算——方言差异不能靠猜")
        void rendersDurationPostgreSql() {
            SemanticModel m = model(new DerivedMetric(
                    DerivedMetric.Kind.DURATION, null, null, "报案日期", "报案日期", "HOUR"));

            RenderedSql r = AskSqlRenderer.render(oneMetric("平均报案时长"), m,
                    SqlDialects.of("POSTGRESQL"), allowed(open()), TODAY);

            assertThat(r.sql())
                    .contains("EXTRACT(EPOCH FROM")
                    .contains("/ 3600");
        }

        @Test
        @DisplayName("Oracle 用日期相减乘 24（天 → 小时）")
        void rendersDurationOracle() {
            SemanticModel m = model(new DerivedMetric(
                    DerivedMetric.Kind.DURATION, null, null, "报案日期", "报案日期", "HOUR"));

            RenderedSql r = AskSqlRenderer.render(oneMetric("平均报案时长"), m,
                    SqlDialects.of("ORACLE"), allowed(open()), TODAY);

            assertThat(r.sql()).contains("* 24");
        }
    }

    @Test
    @DisplayName("★ 跨表派生：两个输入列分属不同表时，JOIN 由规划器自动补齐")
    void crossTableDerivedPullsJoin() {
        // 分子在理赔表、分母在保单表——手拼 SQL 最容易漏掉的那条 JOIN
        SemanticModel m = model(new DerivedMetric(
                DerivedMetric.Kind.RATIO, "赔付金额", "保费", null, null, null));

        RenderedSql r = AskSqlRenderer.render(oneMetric("赔付率"), m,
                SqlDialects.of("MYSQL5.7"), allowed(open()), TODAY);

        assertThat(r.sql())
                .contains("SUM(`t1`.`settle_amount`)")
                .contains("SUM(`t2`.`premium`)")
                .contains("JOIN `ins_policy` `t2`");
    }

    @Nested
    @DisplayName("★ 脱敏传导")
    class Masking {

        @Test
        @DisplayName("输入列被脱敏 → 派生指标整体脱敏，输出 *** 而不是算出来的数")
        void maskedInputMasksDerived() {
            DomainPermission maskedPerm = permOf("""
                    {"v":1,"grantRoles":["分析师"],"unrestrictedRoles":["分析师"],
                     "colRules":[{"id":1,"role":"分析师","field":"claim_amount","action":"脱敏"}]}""");

            SemanticModel m = model(new DerivedMetric(
                    DerivedMetric.Kind.RATIO, "赔付金额", "申请金额", null, null, null));

            RenderedSql r = AskSqlRenderer.render(oneMetric("赔付率"), m,
                    SqlDialects.of("MYSQL5.7"), allowed(maskedPerm), TODAY);

            assertThat(r.sql())
                    .as("能从比值反推出被脱敏的分母，所以必须整条脱敏")
                    .doesNotContain("NULLIF")
                    .contains("'***'");
            assertThat(r.maskedColumnNames()).contains("赔付率");
        }
    }

    @Nested
    @DisplayName("约束")
    class Constraints {

        @Test
        @DisplayName("派生指标不能当维度")
        void derivedCannotBeDimension() {
            SemanticModel m = model(new DerivedMetric(
                    DerivedMetric.Kind.RATIO, "赔付金额", "申请金额", null, null, null));

            assertThatThrownBy(() -> AskSqlRenderer.render(
                    new AskDsl(List.of(), List.of("赔付率"), null, List.of()),
                    m, SqlDialects.of("MYSQL5.7"), allowed(open()), TODAY))
                    .isInstanceOf(SemanticException.class)
                    .hasMessageContaining("派生指标");
        }

        @Test
        @DisplayName("输入列不存在时拒绝，不静默跳过")
        void unknownInputRejected() {
            SemanticModel m = model(new DerivedMetric(
                    DerivedMetric.Kind.RATIO, "赔付金额", "不存在的列", null, null, null));

            assertThatThrownBy(() -> AskSqlRenderer.render(oneMetric("赔付率"), m,
                    SqlDialects.of("MYSQL5.7"), allowed(open()), TODAY))
                    .isInstanceOf(SemanticException.class);
        }
    }
}

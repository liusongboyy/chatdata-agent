package com.liusongboyy.chatdata.engine.chat;

import com.liusongboyy.chatdata.engine.nlsql.AskDsl;
import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import com.liusongboyy.chatdata.engine.permission.PermissionContext;
import com.liusongboyy.chatdata.engine.permission.PermissionDecision;
import com.liusongboyy.chatdata.engine.permission.PermissionGuard;
import com.liusongboyy.chatdata.engine.permission.RowScope;
import com.liusongboyy.chatdata.engine.permission.SqlPredicate;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;
import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「我理解成了」的回显测试。
 *
 * <p>这块信息唯一的用途是**让用户发现系统漏掉了他说的条件**，
 * 所以这里的用例基本都是围绕"漏掉的东西还看不看得出来"写的，
 * 而不是围绕字段有没有正确填充。
 */
class AskUnderstandingTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    private static SemanticModel model() {
        RegisteredTable policy = new RegisteredTable("1", "ins_policy", "保单表",
                RegisteredTable.Mode.TABLE, null, true, List.of(
                new ColumnDef("premium", "decimal", Aggregation.SUM, "保费", true),
                new ColumnDef("region", "varchar", Aggregation.NONE, "区域", true),
                new ColumnDef("status", "varchar", Aggregation.NONE, "状态", true),
                new ColumnDef("effective_date", "date", Aggregation.NONE, "生效日期", true)));
        Domain domain = new Domain("d1", "保险分析域", "1", "MYSQL5.7", true,
                List.of(policy), List.of(), DomainPermission.from(null), List.of(), List.of());
        return new SemanticModel(domain);
    }

    private static PermissionDecision.Allowed allowed(RowScope scope) {
        return new PermissionDecision.Allowed(scope, Set.of());
    }

    private static final PermissionDecision.Allowed UNRESTRICTED =
            allowed(new RowScope.Unrestricted());

    @Nested
    @DisplayName("漏掉的约束必须看得见")
    class DroppedConstraints {

        @Test
        @DisplayName("问题里说了「华东」而 DSL 里没有筛选——筛选槽是空的，而不是不显示")
        void missingFiltersShowUpAsEmptyNotHidden() {
            // spike 实测的那个失败形态：metrics 有、timeRange 有、filters 整个丢了
            AskDsl dropped = new AskDsl(List.of("保费"), List.of(),
                    new AskDsl.TimeRange("上个月", null, null), List.of());

            AskUnderstanding u = AskUnderstanding.of(dropped, model(), UNRESTRICTED, TODAY);

            assertThat(u.filters()).isEmpty();
            assertThat(u.metrics()).containsExactly("保费");
            // 关键：时间槽有值、筛选槽为空，两者同时呈现，用户才看得出对比
            assertThat(u.time()).isNotNull();
        }

        @Test
        @DisplayName("问题里说了时间而 DSL 里没有——时间槽必须能表达「没有限制」")
        void missingTimeIsRepresentable() {
            AskDsl noTime = new AskDsl(List.of("保费"), List.of("区域"), null, List.of());

            AskUnderstanding u = AskUnderstanding.of(noTime, model(), UNRESTRICTED, TODAY);

            assertThat(u.time()).isNull();
            assertThat(u.dimensions()).containsExactly("区域");
        }
    }

    @Nested
    @DisplayName("时间：以解析结果为准，不以 DSL 原文为准")
    class TimeWindowTest {

        @Test
        @DisplayName("「上个月」显示成闭区间——止期不能显示成下个月的 1 号")
        void resolvesToInclusiveRange() {
            AskDsl dsl = new AskDsl(List.of("保费"), List.of(),
                    new AskDsl.TimeRange("上个月", null, null), List.of());

            AskUnderstanding u = AskUnderstanding.of(dsl, model(), UNRESTRICTED, TODAY);

            assertThat(u.time()).isNotNull();
            assertThat(u.time().kind()).isEqualTo("上个月");
            // 内部是左闭右开 [09-01, 10-01)，显示必须收成 09-01 ~ 09-30
            assertThat(u.time().from()).isEqualTo("2026-09-01");
            assertThat(u.time().to()).isEqualTo("2026-09-30");
        }

        @Test
        @DisplayName("有 timeRange 但解析不出条件 → 显示为「没有时间限制」，不能回显一个不存在的口径")
        void unresolvableKindMeansNoTimeCondition() {
            // kind 为空且没有日期：TimeRangeResolver 返回 null，渲染器不加时间条件。
            // 此时若照着 DSL 回显，等于替一次**全时段查询**背书
            AskDsl dsl = new AskDsl(List.of("保费"), List.of(),
                    new AskDsl.TimeRange(null, null, null), List.of());

            AskUnderstanding u = AskUnderstanding.of(dsl, model(), UNRESTRICTED, TODAY);

            assertThat(u.time()).isNull();
        }
    }

    @Nested
    @DisplayName("字段名翻成业务名")
    class DisplayNames {

        @Test
        @DisplayName("物理列名进、业务名出——用户不认识 premium")
        void mapsPhysicalNamesToBusinessNames() {
            AskDsl dsl = new AskDsl(
                    List.of("premium"),
                    List.of("region"),
                    null,
                    List.of(new AskDsl.Filter("status", "=", "有效")));

            AskUnderstanding u = AskUnderstanding.of(dsl, model(), UNRESTRICTED, TODAY);

            assertThat(u.metrics()).containsExactly("保费");
            assertThat(u.dimensions()).containsExactly("区域");
            assertThat(u.filters()).singleElement()
                    .satisfies(f -> {
                        assertThat(f.field()).isEqualTo("状态");
                        assertThat(f.value()).isEqualTo("有效");
                    });
        }

        @Test
        @DisplayName("翻不出来就用原文——绝不猜，也绝不因此失败")
        void unknownNameFallsBackToRaw() {
            AskDsl dsl = new AskDsl(List.of("某个没登记的字段"), List.of(), null, List.of());

            AskUnderstanding u = AskUnderstanding.of(dsl, model(), UNRESTRICTED, TODAY);

            assertThat(u.metrics()).containsExactly("某个没登记的字段");
        }

        @Test
        @DisplayName("语义模型缺失时降级为原文，不抛异常")
        void nullModelDegradesGracefully() {
            AskDsl dsl = new AskDsl(List.of("premium"), List.of("region"), null,
                    List.of(new AskDsl.Filter("status", "=", "有效")));

            AskUnderstanding u = AskUnderstanding.of(dsl, null, UNRESTRICTED, TODAY);

            assertThat(u.metrics()).containsExactly("premium");
            assertThat(u.filters()).singleElement()
                    .satisfies(f -> assertThat(f.field()).isEqualTo("status"));
        }
    }

    @Nested
    @DisplayName("数据范围：解释「为什么我看到的数字和别人不一样」")
    class DataScopeTest {

        @Test
        @DisplayName("有行规则：把谓词里的占位符与参数填回去，写成能读的形式")
        void rendersRestrictedScope() {
            // 谓词形态与 RowRuleCompiler 编译出来的一致：{col:xxx} + ?
            RowScope restricted = new RowScope.Restricted(
                    SqlPredicate.of("({col:region} = ?)", "华东"));

            AskUnderstanding u = AskUnderstanding.of(
                    new AskDsl(List.of("保费"), List.of(), null, List.of()),
                    model(), allowed(restricted), TODAY);

            assertThat(u.dataScope().restricted()).isTrue();
            // 物理列名 region 翻成「区域」，参数值填回 ?
            assertThat(u.dataScope().text()).isEqualTo("(区域 = '华东')");
        }

        @Test
        @DisplayName("多条规则按 OR 合并时，参数按序填对位置")
        void multipleParamsFillInOrder() {
            RowScope restricted = new RowScope.Restricted(
                    SqlPredicate.of("({col:region} = ?) OR ({col:region} = ?)", "华东", "华北"));

            AskUnderstanding u = AskUnderstanding.of(
                    new AskDsl(List.of("保费"), List.of(), null, List.of()),
                    model(), allowed(restricted), TODAY);

            assertThat(u.dataScope().text()).isEqualTo("(区域 = '华东') OR (区域 = '华北')");
        }

        @Test
        @DisplayName("显式全量与隐式全量都不算受限——用户看到的都是全部数据")
        void unrestrictedScopes() {
            AskDsl dsl = new AskDsl(List.of("保费"), List.of(), null, List.of());

            assertThat(AskUnderstanding.of(dsl, model(),
                    allowed(new RowScope.Unrestricted()), TODAY).dataScope().restricted())
                    .isFalse();
            assertThat(AskUnderstanding.of(dsl, model(),
                    allowed(new RowScope.ImplicitUnrestricted()), TODAY).dataScope().restricted())
                    .isFalse();
        }

        @Test
        @DisplayName("与真实权限判定串起来：区域经理看到的是自己的范围")
        void worksWithRealPermissionGuard() {
            DomainPermission perm = DomainPermission.from(permissionJson());
            PermissionDecision decision = PermissionGuard.check(
                    null, perm, PermissionContext.of("u1", Set.of("区域经理")));

            assertThat(decision).isInstanceOf(PermissionDecision.Allowed.class);

            AskUnderstanding u = AskUnderstanding.of(
                    new AskDsl(List.of("保费"), List.of(), null, List.of()),
                    model(), decision, TODAY);

            assertThat(u.dataScope().restricted()).isTrue();
            assertThat(u.dataScope().text()).contains("区域").contains("华东");
        }

        private com.fasterxml.jackson.databind.JsonNode permissionJson() {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                        {"v":1,"grantRoles":["区域经理"],
                         "rowRules":[{"id":1,"role":"区域经理","field":"region",
                                      "op":"=","value":"华东"}]}""");
            } catch (Exception e) {
                throw new IllegalStateException("夹具 JSON 写错", e);
            }
        }
    }

    @Test
    @DisplayName("DSL 为 null 时不抛异常——诊断信息不该影响已经拿到的数据")
    void nullDslIsTolerated() {
        AskUnderstanding u = AskUnderstanding.of(null, model(), UNRESTRICTED, TODAY);

        assertThat(u.metrics()).isEmpty();
        assertThat(u.dimensions()).isEmpty();
        assertThat(u.filters()).isEmpty();
        assertThat(u.time()).isNull();
    }
}

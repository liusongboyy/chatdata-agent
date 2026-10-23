package com.liusongboyy.chatdata.engine.semantic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 语义模型解析的测试。
 *
 * <p>重点是**歧义与角色错配必须拒绝而不是猜**——猜错的后果是查询打到错误的表上，
 * 返回一个看起来合理但错误的数字，用户无从察觉。
 */
class SemanticModelTest {

    // ── 测试夹具：贴近真实保险业务表的形状 ──

    private static ColumnDef metric(String col, String alias) {
        return new ColumnDef(col, "decimal", Aggregation.SUM, alias, true);
    }

    private static ColumnDef dim(String col, String alias) {
        return new ColumnDef(col, "varchar", Aggregation.NONE, alias, true);
    }

    private static RegisteredTable policyTable() {
        return new RegisteredTable("tbl-policy", "ins_policy", "保单表", RegisteredTable.Mode.TABLE,
                null, true, List.of(
                metric("premium", "保费"),
                dim("region", "区域"),
                dim("status", "状态"),
                dim("effect_date", "生效日期")));
    }

    private static RegisteredTable customerTable() {
        return new RegisteredTable("tbl-customer", "ins_customer", "客户表", RegisteredTable.Mode.TABLE,
                null, true, List.of(
                metric("premium_total", "保费合计"),
                dim("region", "区域"),          // ← 与保单表的「区域」撞名，用于测歧义
                dim("cust_name", "客户姓名")));
    }

    private static Domain domain(RegisteredTable... tables) {
        return new Domain("d1", "保险分析域", "ds1", "MYSQL5.7", true, List.of(tables), null, null, List.of(), List.of());
    }

    @Nested
    @DisplayName("正常解析")
    class HappyPath {

        @Test
        @DisplayName("按业务名解析指标")
        void resolvesMetricByAlias() {
            SemanticModel m = new SemanticModel(domain(policyTable()));

            FieldRef ref = m.resolveMetric("保费");

            assertThat(ref.column()).isEqualTo("premium");
            assertThat(ref.agg()).isEqualTo(Aggregation.SUM);
            assertThat(ref.kind()).isEqualTo(FieldKind.METRIC);
            assertThat(ref.isMetric()).isTrue();
        }

        @Test
        @DisplayName("按物理列名也能解析 —— 用户可能直接写列名")
        void resolvesByPhysicalColumnName() {
            SemanticModel m = new SemanticModel(domain(policyTable()));

            assertThat(m.resolveMetric("premium").column()).isEqualTo("premium");
        }

        @Test
        @DisplayName("解析维度")
        void resolvesDimension() {
            SemanticModel m = new SemanticModel(domain(policyTable()));

            FieldRef ref = m.resolveDimension("区域");

            assertThat(ref.column()).isEqualTo("region");
            assertThat(ref.agg()).isEqualTo(Aggregation.NONE);
            assertThat(ref.isMetric()).isFalse();
        }

        @Test
        @DisplayName("大小写与前后空白不敏感")
        void ignoresCaseAndWhitespace() {
            SemanticModel m = new SemanticModel(domain(policyTable()));

            assertThat(m.resolveMetric("  PREMIUM  ").column()).isEqualTo("premium");
        }

        @Test
        @DisplayName("alias 与 col 相同时不会被误判为歧义")
        void aliasEqualToColumnIsNotAmbiguous() {
            // 这是配置里最常见的写法（alias 直接照抄列名），
            // 索引若不去重会把同一列加两次，从而误报歧义
            RegisteredTable t = new RegisteredTable("tbl-x", "t", "T", RegisteredTable.Mode.TABLE, null, true,
                    List.of(new ColumnDef("premium", "decimal", Aggregation.SUM, "premium", true)));

            SemanticModel m = new SemanticModel(domain(t));

            assertThat(m.resolveMetric("premium").column()).isEqualTo("premium");
        }
    }

    @Nested
    @DisplayName("歧义 —— 拒绝而不是猜")
    class Ambiguity {

        @Test
        @DisplayName("★ 同一业务名在多张表上都有 → 拒绝")
        void rejectsAmbiguousAcrossTables() {
            SemanticModel m = new SemanticModel(domain(policyTable(), customerTable()));

            assertThatThrownBy(() -> m.resolveDimension("区域"))
                    .as("两张表都有「区域」，任选一张都会让查询打到错误的表上，返回看起来合理但错误的数字")
                    .isInstanceOf(SemanticException.class)
                    .satisfies(e -> assertThat(((SemanticException) e).reason())
                            .isEqualTo(SemanticException.Reason.FIELD_AMBIGUOUS))
                    .hasMessageContaining("区域");
        }

        @Test
        @DisplayName("★ 同一张表内两列撞名 → 同样是歧义，不能静默取第一个")
        void rejectsAmbiguousWithinOneTable() {
            RegisteredTable t = new RegisteredTable("tbl-x", "t", "T", RegisteredTable.Mode.TABLE, null, true,
                    List.of(
                            dim("region", "区域"),
                            dim("area", "区域")));   // 同一张表里两个列都叫「区域」

            SemanticModel m = new SemanticModel(domain(t));

            assertThatThrownBy(() -> m.resolveDimension("区域"))
                    .isInstanceOf(SemanticException.class)
                    .satisfies(e -> assertThat(((SemanticException) e).reason())
                            .isEqualTo(SemanticException.Reason.FIELD_AMBIGUOUS));
        }

        @Test
        @DisplayName("不撞名的字段照常解析 —— 歧义只影响撞名的那一个")
        void otherFieldsStillResolve() {
            SemanticModel m = new SemanticModel(domain(policyTable(), customerTable()));

            assertThat(m.resolveDimension("客户姓名").column()).isEqualTo("cust_name");
            assertThat(m.resolveMetric("保费").column()).isEqualTo("premium");
        }
    }

    @Nested
    @DisplayName("角色校验")
    class KindMismatch {

        @Test
        @DisplayName("无聚合方式的列不能当指标")
        void dimensionCannotBeMetric() {
            SemanticModel m = new SemanticModel(domain(policyTable()));

            assertThatThrownBy(() -> m.resolveMetric("区域"))
                    .isInstanceOf(SemanticException.class)
                    .satisfies(e -> assertThat(((SemanticException) e).reason())
                            .isEqualTo(SemanticException.Reason.FIELD_KIND_MISMATCH));
        }

        @Test
        @DisplayName("配了聚合方式的列不能当维度 —— 否则会被渲染进 GROUP BY 而漏掉聚合")
        void metricCannotBeDimension() {
            SemanticModel m = new SemanticModel(domain(policyTable()));

            assertThatThrownBy(() -> m.resolveDimension("保费"))
                    .isInstanceOf(SemanticException.class)
                    .satisfies(e -> assertThat(((SemanticException) e).reason())
                            .isEqualTo(SemanticException.Reason.FIELD_KIND_MISMATCH))
                    .hasMessageContaining("SUM");
        }
    }

    @Nested
    @DisplayName("可见性 —— 关闭的表与列不参与解析")
    class Visibility {

        @Test
        @DisplayName("关闭的表整个不可解析")
        void disabledTableIsInvisible() {
            RegisteredTable disabled = new RegisteredTable("tbl-policy", "ins_policy", "保单表",
                    RegisteredTable.Mode.TABLE, null, false, List.of(metric("premium", "保费")));

            SemanticModel m = new SemanticModel(domain(disabled));

            assertThatThrownBy(() -> m.resolveMetric("保费"))
                    .isInstanceOf(SemanticException.class)
                    .satisfies(e -> assertThat(((SemanticException) e).reason())
                            .isEqualTo(SemanticException.Reason.FIELD_NOT_FOUND));
            assertThat(m.visibleTables()).isEmpty();
        }

        @Test
        @DisplayName("关闭的列不可解析，但同表其它列照常")
        void disabledColumnIsInvisible() {
            RegisteredTable t = new RegisteredTable("tbl-policy", "ins_policy", "保单表",
                    RegisteredTable.Mode.TABLE, null, true, List.of(
                    metric("premium", "保费"),
                    new ColumnDef("cost", "decimal", Aggregation.SUM, "成本", false)));

            SemanticModel m = new SemanticModel(domain(t));

            assertThat(m.resolveMetric("保费").column()).isEqualTo("premium");
            assertThatThrownBy(() -> m.resolveMetric("成本"))
                    .isInstanceOf(SemanticException.class)
                    .satisfies(e -> assertThat(((SemanticException) e).reason())
                            .isEqualTo(SemanticException.Reason.FIELD_NOT_FOUND));
        }

        @Test
        @DisplayName("关闭的列不进 availableFieldNames —— 它不该出现在 prompt 里")
        void disabledColumnNotListed() {
            RegisteredTable t = new RegisteredTable("tbl-policy", "ins_policy", "保单表",
                    RegisteredTable.Mode.TABLE, null, true, List.of(
                    metric("premium", "保费"),
                    new ColumnDef("cost", "decimal", Aggregation.SUM, "成本", false)));

            assertThat(new SemanticModel(domain(t)).availableFieldNames())
                    .containsExactly("保费");
        }
    }

    @Nested
    @DisplayName("表别名 —— 权限谓词要靠它确定作用在哪张表上")
    class TableAlias {

        @Test
        @DisplayName("别名按注册表 id 排序分配，与传入顺序无关")
        void aliasIsDeterministic() {
            // 反序传入，别名仍应按 id 排：tbl-customer → t1，tbl-policy → t2
            SemanticModel a = new SemanticModel(domain(customerTable(), policyTable()));
            SemanticModel b = new SemanticModel(domain(policyTable(), customerTable()));

            assertThat(a.aliasOf("tbl-customer")).isEqualTo(b.aliasOf("tbl-customer"));
            assertThat(a.aliasOf("tbl-policy")).isEqualTo(b.aliasOf("tbl-policy"));
            assertThat(a.aliasOf("tbl-customer")).isEqualTo("t1");
            assertThat(a.aliasOf("tbl-policy")).isEqualTo("t2");
        }

        @Test
        @DisplayName("解析结果带上别名 —— 行规则谓词靠它写成 t1.region = ?")
        void fieldRefCarriesAlias() {
            SemanticModel m = new SemanticModel(domain(policyTable()));

            assertThat(m.resolveDimension("区域").tableAlias()).isEqualTo("t1");
        }

        @Test
        @DisplayName("取不可见表的别名会失败")
        void aliasOfInvisibleTableFails() {
            SemanticModel m = new SemanticModel(domain(policyTable()));

            assertThatThrownBy(() -> m.aliasOf("不存在的表"))
                    .isInstanceOf(SemanticException.class);
        }
    }

    @Nested
    @DisplayName("配置校验")
    class ConfigValidation {

        @Test
        @DisplayName("SQL 虚拟表没有定义时构造即失败")
        void sqlTableRequiresDefinition() {
            assertThatThrownBy(() -> new RegisteredTable("t", "v", "V",
                    RegisteredTable.Mode.SQL, null, true, List.of()))
                    .isInstanceOf(SemanticException.class)
                    .hasMessageContaining("SQL");
        }

        @Test
        @DisplayName("未知聚合方式抛异常，不回落 NONE")
        void unknownAggregationFails() {
            assertThatThrownBy(() -> Aggregation.parse("MEDIAN"))
                    .as("回落 NONE 会把本该聚合的指标当维度渲染进 GROUP BY，查询能跑但结果是错的")
                    .isInstanceOf(SemanticException.class);
        }

        @Test
        @DisplayName("空聚合方式视为 NONE —— 维度列本来就不写")
        void blankAggregationIsNone() {
            assertThat(Aggregation.parse(null)).isEqualTo(Aggregation.NONE);
            assertThat(Aggregation.parse("  ")).isEqualTo(Aggregation.NONE);
        }
    }
}

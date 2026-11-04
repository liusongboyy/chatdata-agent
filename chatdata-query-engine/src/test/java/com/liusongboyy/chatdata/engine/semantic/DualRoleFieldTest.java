package com.liusongboyy.chatdata.engine.semantic;

import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 同一字段既有指标定义、又有维度定义时的解析。
 *
 * <h2>这是刻意支持的建模手法，不是配置错误</h2>
 *
 * <p>「年龄」既要能求平均（{@code AVG(age)}），又要能写 {@code age > 50}；
 * 「评论数」既是 {@code COUNT(id)}，又要能按它过滤。管理员为此多登记一行
 * **不聚合**的列定义——这是对的，不是重复配置。
 *
 * <p>以前这里一律判歧义，于是「50 岁以上的客户有多少人」这类最常见的问题永远问不出来。
 * 现在的规则：**候选里恰好有一个符合期望角色时用它**；一个都不符合或多个都符合，
 * 仍然拒绝——那才是真需要人介入的情况。
 */
class DualRoleFieldTest {

    /**
     * 真正的冲突：两条**同角色**的同名列。
     *
     * <p>它们必须撞在同一个索引键上，否则这个用例测不到歧义分支——
     * 一开始我把其中一条写成「年龄合计」，名字不同，于是它走的是"找不到指标"，
     * 而不是"有多处匹配"。
     */
    private static SemanticModel model() {
        RegisteredTable customers = new RegisteredTable("t1", "ins_customer", "客户表",
                RegisteredTable.Mode.TABLE, null, true, List.of(
                new ColumnDef("age", "int", Aggregation.AVG, "年龄", true),
                new ColumnDef("age", "int", Aggregation.SUM, "年龄", true),
                new ColumnDef("name", "varchar", Aggregation.NONE, "姓名", true)));

        Domain d = new Domain("d1", "客户域", "ds1", "MYSQL5.7", true, List.of(customers),
                List.of(), DomainPermission.from(null), List.of(), List.of());
        return new SemanticModel(d);
    }

    /** 只有「平均年龄」(指标) 与「年龄」(维度) 两个候选，不含上面那条真正的冲突。 */
    private static SemanticModel twoRoles() {
        RegisteredTable t = new RegisteredTable("t1", "ins_customer", "客户表",
                RegisteredTable.Mode.TABLE, null, true, List.of(
                new ColumnDef("age", "int", Aggregation.AVG, "年龄", true),
                new ColumnDef("age", "int", Aggregation.NONE, "年龄", true, null)));
        // 两条同名叫「年龄」：一个是指标一个是维度（按 agg 区分）
        Domain d = new Domain("d1", "客户域", "ds1", "MYSQL5.7", true, List.of(t),
                List.of(), DomainPermission.from(null), List.of(), List.of());
        return new SemanticModel(d);
    }

    @Test
    @DisplayName("★ 同名两行、角色不同 → 按调用方期望的角色挑选，不再判歧义")
    void picksByRole() {
        SemanticModel m = twoRoles();

        assertThat(m.resolveDimension("年龄").isMetric()).isFalse();
        assertThat(m.resolveMetric("年龄").isMetric()).isTrue();
    }

    @Test
    @DisplayName("★ 同名两行、角色不同 → 指标与维度各自拿到正确的聚合方式")
    void rolesCarryTheirOwnAggregation() {
        SemanticModel m = twoRoles();

        assertThat(m.resolveMetric("年龄").agg()).isEqualTo(Aggregation.AVG);
        assertThat(m.resolveDimension("年龄").agg()).isEqualTo(Aggregation.NONE);
    }

    @Test
    @DisplayName("多个候选都符合期望角色 → 仍然判歧义（真冲突不猜）")
    void stillAmbiguousWhenSeveralFit() {
        SemanticModel m = model();

        assertThatThrownBy(() -> m.resolveMetric("年龄"))
                .isInstanceOf(SemanticException.class)
                .hasMessageContaining("多处");
    }

    @Test
    @DisplayName("★ 权限字段定位：同表内双登记时选维度那条")
    void roleAgnosticPicksDimensionForRowRules() {
        // 行级过滤在语义上**一定作用于维度**——「年龄 > 50」筛的是行，
        // 不是「平均年龄 > 50」。
        //
        // 早先这里一律判歧义，理由是"谓词注错表比查询失败危险得多"。
        // 那个担心在**跨表**时成立，但同表内的维度/指标双登记是刻意的建模手法：
        // 不解开它，配了双登记的域就再也配不了行规则（实测过）。
        var ref = twoRoles().resolveAny("年龄");

        assertThat(ref).isNotNull();
        assertThat(ref.isMetric()).as("行过滤要用维度").isFalse();
    }

    @Test
    @DisplayName("★ 但仍然只在同一张表内才敢选：跨表多个维度要人介入")
    void stillStrictAcrossTables() {
        // 两张表都有「区域」维度时，谓词注到哪张表上是人的决定，不能猜
        RegisteredTable t1 = new RegisteredTable("t1", "ins_policy", "保单表",
                RegisteredTable.Mode.TABLE, null, true, List.of(
                new ColumnDef("region", "varchar", Aggregation.NONE, "区域", true, null)));
        RegisteredTable t2 = new RegisteredTable("t2", "ins_claim", "理赔表",
                RegisteredTable.Mode.TABLE, null, true, List.of(
                new ColumnDef("region", "varchar", Aggregation.NONE, "区域", true, null)));
        Domain d = new Domain("d1", "保险域", "ds1", "MYSQL5.7", true, List.of(t1, t2),
                List.of(), DomainPermission.from(null), List.of(), List.of());
        SemanticModel m = new SemanticModel(d);

        assertThatThrownBy(() -> m.resolveAny("区域"))
                .as("跨表的同名列必须人介入——谓词注错表比查询失败危险得多")
                .isInstanceOf(SemanticException.class)
                .hasMessageContaining("多处");
    }
}

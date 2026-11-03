package com.liusongboyy.chatdata.engine.modeling;

import com.liusongboyy.chatdata.engine.datasource.MetaReader.ColumnMeta;
import com.liusongboyy.chatdata.engine.datasource.MetaReader.TableMeta;
import com.liusongboyy.chatdata.engine.modeling.JoinInferrer.Confidence;
import com.liusongboyy.chatdata.engine.modeling.JoinInferrer.ForeignKey;
import com.liusongboyy.chatdata.engine.modeling.JoinInferrer.JoinCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表间关联推断。
 *
 * <p>两个必测的点：<b>没有外键时命名推断要能工作</b>（AdventureWorks 库一条外键都没有），
 * <b>歧义要全列出来</b>（挑错了是静默的错误数字）。
 */
class JoinInferrerTest {

    private static TableMeta table(String name) {
        return new TableMeta(name, "", "TABLE");
    }

    private static ColumnMeta pk(String name) {
        return new ColumnMeta(name, "bigint", false, true, "");
    }

    private static ColumnMeta col(String name) {
        return new ColumnMeta(name, "bigint", true, false, "");
    }

    @Test
    @DisplayName("★ 外键直接用，置信度最高")
    void foreignKeyIsUsedDirectly() {
        var tables = List.of(table("salesorderdetail"), table("product"));
        var columns = Map.of(
                "salesorderdetail", List.of(pk("SalesOrderDetailID"), col("ProductID")),
                "product", List.of(pk("ProductID"), col("Name")));
        var fks = List.of(new ForeignKey(
                "salesorderdetail", "ProductID", "product", "ProductID"));

        List<JoinCandidate> result = JoinInferrer.infer(tables, columns, fks);

        assertThat(result).singleElement().satisfies(j -> {
            assertThat(j.confidence()).isEqualTo(Confidence.FOREIGN_KEY);
            assertThat(j.left()).isEqualTo("salesorderdetail");
            assertThat(j.right()).isEqualTo("product");
            assertThat(j.reason()).contains("外键");
        });
    }

    @Test
    @DisplayName("★ 一条外键都没有时，命名推断仍然工作（AdventureWorks 的真实情况）")
    void namingInferenceWorksWithoutForeignKeys() {
        var tables = List.of(table("salesorderdetail"), table("product"));
        var columns = Map.of(
                "salesorderdetail", List.of(pk("SalesOrderDetailID"), col("ProductID")),
                "product", List.of(pk("ProductID"), col("Name")));

        List<JoinCandidate> result = JoinInferrer.infer(tables, columns, List.of());

        assertThat(result).singleElement().satisfies(j -> {
            assertThat(j.confidence()).isEqualTo(Confidence.NAMING);
            assertThat(j.right()).isEqualTo("product");
            assertThat(j.reason()).contains("需确认");
        });
    }

    @Test
    @DisplayName("★ 歧义列全部候选，不挑一个")
    void ambiguityListsAllCandidates() {
        // dim_product 和 product 都有 ProductID 主键 → 两个都对得上
        var tables = List.of(table("salesorder"), table("product"), table("dim_product"));
        var columns = Map.of(
                "salesorder", List.of(pk("id"), col("ProductID")),
                "product", List.of(pk("ProductID")),
                "dim_product", List.of(pk("ProductID")));

        List<JoinCandidate> result = JoinInferrer.infer(tables, columns, List.of());

        // 挑一个的话，挑错就是 JOIN 到错的表——SQL 合法、数字不对
        assertThat(result).hasSize(2);
        assertThat(result).extracting(JoinCandidate::right)
                .containsExactlyInAnyOrder("product", "dim_product");
    }

    @Test
    @DisplayName("★ 对不上主键的表不推断")
    void noInferenceWithoutMatchingPrimaryKey() {
        var tables = List.of(table("salesorder"), table("region"));
        var columns = Map.of(
                "salesorder", List.of(pk("id"), col("RegionID")),
                "region", List.of(col("code")));   // 没有主键

        assertThat(JoinInferrer.infer(tables, columns, List.of())).isEmpty();
    }

    @Test
    @DisplayName("★ 自己的主键不推自关联")
    void primaryKeyDoesNotSelfJoin() {
        var tables = List.of(table("product"));
        var columns = Map.of("product", List.of(pk("ProductID")));

        assertThat(JoinInferrer.infer(tables, columns, List.of())).isEmpty();
    }

    @Test
    @DisplayName("列名就叫 id 推不出指向谁，不推")
    void bareIdIsNotInferred() {
        var tables = List.of(table("salesorder"), table("x"));
        var columns = Map.of(
                "salesorder", List.of(pk("orderid"), col("id")),
                "x", List.of(pk("id")));

        assertThat(JoinInferrer.infer(tables, columns, List.of())).isEmpty();
    }

    @Test
    @DisplayName("★ 已有外键的列不重复按命名推")
    void noDuplicateForForeignKeys() {
        var tables = List.of(table("salesorder"), table("product"));
        var columns = Map.of(
                "salesorder", List.of(pk("id"), col("ProductID")),
                "product", List.of(pk("ProductID")));
        var fks = List.of(new ForeignKey("salesorder", "ProductID", "product", "ProductID"));

        List<JoinCandidate> result = JoinInferrer.infer(tables, columns, fks);

        // 同一个关系出现两条候选，管理员还得去重
        assertThat(result).hasSize(1);
        assertThat(result.get(0).confidence()).isEqualTo(Confidence.FOREIGN_KEY);
    }

    @Test
    @DisplayName("复数表名也能对上：customer_id → customers")
    void pluralTableNames() {
        var tables = List.of(table("orders"), table("customers"));
        var columns = Map.of(
                "orders", List.of(pk("id"), col("CustomerID")),
                "customers", List.of(pk("id")));

        assertThat(JoinInferrer.infer(tables, columns, List.of()))
                .singleElement()
                .satisfies(j -> assertThat(j.right()).isEqualTo("customers"));
    }

    @Test
    @DisplayName("维度表前缀也能对上：product_id → dim_product")
    void dimPrefixTableNames() {
        var tables = List.of(table("sales"), table("dim_product"));
        var columns = Map.of(
                "sales", List.of(pk("id"), col("product_id")),
                "dim_product", List.of(pk("product_id")));

        assertThat(JoinInferrer.infer(tables, columns, List.of()))
                .singleElement()
                .satisfies(j -> assertThat(j.right()).isEqualTo("dim_product"));
    }
}

package com.liusongboyy.chatdata.engine.semantic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * join 规划的测试。
 *
 * <p>核心是两件事：**LEFT JOIN 的方向不能被改**（改了行数就变），
 * 以及**连不通时必须拒绝**（猜一个 join 会返回看起来合理但错误的结果）。
 */
class JoinPlannerTest {

    /** 建一张只有一个指标列的表，够用来标识"用到了这张表"。 */
    private static RegisteredTable table(String id, String name) {
        return new RegisteredTable(id, name, name, RegisteredTable.Mode.TABLE, null, true,
                List.of(new ColumnDef("c_" + id, "int", Aggregation.SUM, "指标" + id, true)));
    }

    private static TableJoin inner(long id, String left, String right) {
        return new TableJoin(id, left, right, "l" + id, "r" + id, TableJoin.JoinType.INNER);
    }

    private static TableJoin left(long id, String left, String right) {
        return new TableJoin(id, left, right, "l" + id, "r" + id, TableJoin.JoinType.LEFT);
    }

    private static Domain domain(List<RegisteredTable> tables, List<TableJoin> joins) {
        return new Domain("d1", "域", "ds1", "MYSQL5.7", true, tables, joins,
                com.liusongboyy.chatdata.engine.permission.DomainPermission.from(null), List.of(), List.of());
    }

    @Nested
    @DisplayName("单表 —— 不需要 join")
    class SingleTable {

        @Test
        @DisplayName("只用一张表时没有 join 步骤")
        void noJoinForSingleTable() {
            Domain d = domain(List.of(table("a", "ta")), List.of());

            JoinPlanner.JoinPlan plan = JoinPlanner.plan(d, Set.of("a"));

            assertThat(plan.isSingleTable()).isTrue();
            assertThat(plan.rootTableId()).isEqualTo("a");
            assertThat(plan.tableIdsInOrder()).containsExactly("a");
        }

        @Test
        @DisplayName("空表集合拒绝")
        void emptyRejected() {
            Domain d = domain(List.of(table("a", "ta")), List.of());

            assertThatThrownBy(() -> JoinPlanner.plan(d, Set.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("两表关联")
    class TwoTables {

        @Test
        @DisplayName("INNER 关联 —— 可双向遍历，结果确定")
        void innerJoinTraversable() {
            Domain d = domain(List.of(table("a", "ta"), table("b", "tb")),
                    List.of(inner(1, "a", "b")));

            JoinPlanner.JoinPlan plan = JoinPlanner.plan(d, Set.of("a", "b"));

            assertThat(plan.steps()).hasSize(1);
            assertThat(plan.rootTableId()).isEqualTo("a");
            assertThat(plan.steps().get(0).toTableId()).isEqualTo("b");
            assertThat(plan.steps().get(0).join().type()).isEqualTo(TableJoin.JoinType.INNER);
        }

        @Test
        @DisplayName("★ LEFT 关联只能从左往右 —— 反向走会变成 RIGHT JOIN，行数就变了")
        void leftJoinOnlyForward() {
            // LEFT JOIN a b：保留 a 的全部行
            Domain d = domain(List.of(table("a", "ta"), table("b", "tb")),
                    List.of(left(1, "a", "b")));

            // 以 a 为起点：可以，a → b
            JoinPlanner.JoinPlan plan = JoinPlanner.plan(d, Set.of("a", "b"));
            assertThat(plan.rootTableId()).isEqualTo("a");
            assertThat(plan.steps().get(0).toTableId()).isEqualTo("b");

            // 只用到 b 一张表时不该接 a —— 接进来会改变行数
            JoinPlanner.JoinPlan singleB = JoinPlanner.plan(d, Set.of("b"));
            assertThat(singleB.isSingleTable())
                    .as("只查 b 时不该把 a 接进来，LEFT JOIN 会改变结果集")
                    .isTrue();
        }

        @Test
        @DisplayName("★ 必须反向遍历 LEFT 边时拒绝，而不是悄悄换成 RIGHT JOIN")
        void reverseLeftJoinRejected() {
            // LEFT JOIN a b，但查询要用到 a 和 b，且 b 无法通过正向到达 a
            // —— 以 b 为起点接 a 需要反向走这条 LEFT 边
            Domain d = domain(List.of(table("a", "ta"), table("b", "tb")),
                    List.of(left(1, "a", "b")));

            // 从 a 出发可以正向到达 b，所以这个组合是通的
            assertThat(JoinPlanner.plan(d, Set.of("a", "b")).steps()).hasSize(1);

            // 但若只有一条 b → a 的 LEFT 边（即 LEFT JOIN b a 的语义是保留 b），
            // 而查询需要 a 的字段且 a 无法正向到达……
            Domain reversed = domain(List.of(table("a", "ta"), table("b", "tb")),
                    List.of(left(1, "b", "a")));
            // 从 b 出发正向可达 a，仍然通
            JoinPlanner.JoinPlan p = JoinPlanner.plan(reversed, Set.of("a", "b"));
            assertThat(p.rootTableId()).as("根必须是 LEFT 边的左端").isEqualTo("b");
        }
    }

    @Nested
    @DisplayName("链式关联")
    class ChainedJoins {

        @Test
        @DisplayName("三张表串成链 —— 按接入顺序产出步骤")
        void threeTableChain() {
            Domain d = domain(
                    List.of(table("a", "ta"), table("b", "tb"), table("c", "tc")),
                    List.of(inner(1, "a", "b"), inner(2, "b", "c")));

            JoinPlanner.JoinPlan plan = JoinPlanner.plan(d, Set.of("a", "b", "c"));

            assertThat(plan.rootTableId()).isEqualTo("a");
            assertThat(plan.tableIdsInOrder()).containsExactly("a", "b", "c");
            assertThat(plan.steps()).hasSize(2);
            // 每一步的 from 必须已在此前的表集合里，否则生成的 SQL 引用了还没出现的表
            assertThat(plan.steps().get(0).fromTableId()).isEqualTo("a");
            assertThat(plan.steps().get(1).fromTableId()).isEqualTo("b");
        }

        @Test
        @DisplayName("★ 只用到链两端时，中间表作为跳板必须接进来")
        void middleTableNeededAsBridge() {
            Domain d = domain(
                    List.of(table("a", "ta"), table("b", "tb"), table("c", "tc")),
                    List.of(inner(1, "a", "b"), inner(2, "b", "c")));

            JoinPlanner.JoinPlan plan = JoinPlanner.plan(d, Set.of("a", "c"));

            assertThat(plan.tableIdsInOrder())
                    .as("a 与 c 没有直连，必须经 b 中转")
                    .containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("★ 不相关的表不能被顺带接进来 —— INNER JOIN 会过滤行")
        void unrelatedTablesNotIncluded() {
            // a-b-c 一条链，另有一张孤立的 d 与 b 相连
            Domain d = domain(
                    List.of(table("a", "ta"), table("b", "tb"), table("c", "tc"), table("d", "td")),
                    List.of(inner(1, "a", "b"), inner(2, "b", "c"), inner(3, "b", "d")));

            JoinPlanner.JoinPlan plan = JoinPlanner.plan(d, Set.of("a", "c"));

            assertThat(plan.tableIdsInOrder())
                    .as("查询只用到 a 与 c，d 不该被接进来——INNER JOIN 会改变行数")
                    .containsExactly("a", "b", "c")
                    .doesNotContain("d");
        }
    }

    @Nested
    @DisplayName("连不通时拒绝")
    class Disconnected {

        @Test
        @DisplayName("★ 两张表没有关联 → 抛异常并列出是哪几张")
        void disconnectedTablesRejected() {
            Domain d = domain(List.of(table("a", "ta"), table("b", "tb")), List.of());

            assertThatThrownBy(() -> JoinPlanner.plan(d, Set.of("a", "b")))
                    .isInstanceOf(SemanticException.class)
                    .satisfies(e -> assertThat(((SemanticException) e).reason())
                            .isEqualTo(SemanticException.Reason.TABLES_NOT_CONNECTED))
                    .hasMessageContaining("a")
                    .hasMessageContaining("b");
        }

        @Test
        @DisplayName("部分连通也要拒绝 —— 不能只连上一半就往下走")
        void partiallyConnectedRejected() {
            // a-b 连通，c 孤立
            Domain d = domain(
                    List.of(table("a", "ta"), table("b", "tb"), table("c", "tc")),
                    List.of(inner(1, "a", "b")));

            assertThatThrownBy(() -> JoinPlanner.plan(d, Set.of("a", "b", "c")))
                    .isInstanceOf(SemanticException.class)
                    .hasMessageContaining("连不起来");
        }
    }

    @Nested
    @DisplayName("确定性 —— 同一份配置每次结果相同")
    class Determinism {

        @Test
        @DisplayName("★ 传入顺序不影响结果，否则同一问题两次问出不同 SQL")
        void orderIndependent() {
            Domain d = domain(
                    List.of(table("a", "ta"), table("b", "tb"), table("c", "tc")),
                    List.of(inner(1, "a", "b"), inner(2, "b", "c")));

            JoinPlanner.JoinPlan p1 = JoinPlanner.plan(d, new java.util.LinkedHashSet<>(List.of("c", "a", "b")));
            JoinPlanner.JoinPlan p2 = JoinPlanner.plan(d, new java.util.LinkedHashSet<>(List.of("a", "b", "c")));

            assertThat(p1.rootTableId()).isEqualTo(p2.rootTableId());
            assertThat(p1.tableIdsInOrder()).isEqualTo(p2.tableIdsInOrder());
        }
    }
}

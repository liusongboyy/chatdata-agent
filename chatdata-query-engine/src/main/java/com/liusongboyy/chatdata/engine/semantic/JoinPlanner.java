package com.liusongboyy.chatdata.engine.semantic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 给定用到的几张表，算出把它们连起来的 join 路径。
 *
 * <h2>LEFT JOIN 不能反向遍历</h2>
 *
 * <p>{@code LEFT JOIN a b} 的语义是「保留 a 的全部行」。若为了连通而反向走
 * （从 b 出发去接 a），语义就变成了 {@code RIGHT JOIN}——**行数会变**，
 * 数字跟着变，而且不报错。
 *
 * <p>故遍历规则是：
 * <ul>
 *   <li>{@link TableJoin.JoinType#INNER}——方向无关，可双向遍历</li>
 *   <li>{@link TableJoin.JoinType#LEFT}——**只能从左往右**</li>
 * </ul>
 *
 * <p>反向遍历 LEFT 边会被明确拒绝，而不是悄悄换个 join 类型。
 *
 * <h2>连通不了就拒绝，不猜</h2>
 *
 * <p>域里没配关联的两张表无法一起查。此时抛异常并列出是哪几张表——
 * 猜一个 join 条件会返回一个**看起来合理但错误**的结果。
 */
public final class JoinPlanner {

    private JoinPlanner() {
    }

    /**
     * 一条 join 步骤。
     *
     * @param fromTableId 已在前面的表中出现的表（join 的左操作数）
     * @param toTableId   本步骤要接入的表（join 的右操作数）
     * @param join        配置里的关联定义
     */
    public record JoinStep(String fromTableId, String toTableId, TableJoin join) {
    }

    /**
     * 规划结果。
     *
     * @param rootTableId FROM 子句里的第一张表
     * @param steps       按接入顺序排列的 join 步骤；每一步的 {@code fromTableId}
     *                    必然已在此前的表集合中
     */
    public record JoinPlan(String rootTableId, List<JoinStep> steps) {

        public JoinPlan {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }

        public boolean isSingleTable() {
            return steps.isEmpty();
        }

        /** 全部用到的表，root 在最前。 */
        public List<String> tableIdsInOrder() {
            Set<String> ids = new LinkedHashSet<>();
            ids.add(rootTableId);
            steps.forEach(s -> ids.add(s.toTableId()));
            return List.copyOf(ids);
        }
    }

    /**
     * 规划 join 路径。
     *
     * @param domain         语义域，提供表与关联定义
     * @param involvedTables 本次查询用到的表 id
     * @throws SemanticException 表之间连不通，或必须反向遍历 LEFT 边
     */
    public static JoinPlan plan(Domain domain, Set<String> involvedTables) {
        return plan(domain, involvedTables, null);
    }

    /**
     * 规划 join 路径，并优先以指定表为根。
     *
     * <p><strong>为什么需要 {@code preferredRoot}：</strong>对 INNER 关联而言根的选择
     * 不影响语义，但**影响生成的 SQL 读起来顺不顺**——而排查问题时人是真的要读它。
     * 以指标所在的表为根，会得到 {@code FROM 事实表 JOIN 维表} 这种自然的写法，
     * 而不是反过来。
     *
     * <p>它只是**偏好**：若以它为根连不通（例如它是某条 LEFT 边的右端），
     * 会退回到其它候选，不会因此失败。
     *
     * @param preferredRoot 期望的根表 id，可为 null
     */
    public static JoinPlan plan(Domain domain, Set<String> involvedTables, String preferredRoot) {
        if (involvedTables == null || involvedTables.isEmpty()) {
            throw new IllegalArgumentException("没有用到的表，无法规划 join");
        }
        if (involvedTables.size() == 1) {
            return new JoinPlan(involvedTables.iterator().next(), List.of());
        }

        // 候选顺序：偏好的根排在最前，其余按 id 排序。
        // 排序保证同一份配置每次得到同样的结果——否则同一问题两次问出不同 SQL，
        // 审计与回归测试都做不了。
        List<String> candidates = new ArrayList<>();
        if (preferredRoot != null && involvedTables.contains(preferredRoot)) {
            candidates.add(preferredRoot);
        }
        involvedTables.stream().sorted()
                .filter(t -> !t.equals(preferredRoot))
                .forEach(candidates::add);

        JoinPlan best = null;
        List<String> failures = new ArrayList<>();

        for (String root : candidates) {
            try {
                JoinPlan p = bfs(domain, root, involvedTables);
                best = p;
                break;
            } catch (SemanticException e) {
                failures.add(e.getMessage());
            }
        }

        if (best == null) {
            throw new SemanticException(SemanticException.Reason.TABLES_NOT_CONNECTED,
                    "这几张表在域内连不起来：" + involvedTables
                            + "。请在语义建模中配置它们之间的表关联。"
                            + "（已尝试以每张表为起点，" + failures.size() + " 种走法都不通）");
        }
        return best;
    }

    /** 从 root 出发做 BFS，把 involved 里的表全部接进来。 */
    private static JoinPlan bfs(Domain domain, String root, Set<String> involved) {
        Set<String> visited = new LinkedHashSet<>();
        visited.add(root);

        Deque<String> queue = new ArrayDeque<>();
        queue.add(root);

        List<JoinStep> steps = new ArrayList<>();

        while (!queue.isEmpty()) {
            String current = queue.poll();

            for (TableJoin join : domain.joins()) {
                String next = traversableNeighbor(join, current);
                if (next == null || visited.contains(next)) {
                    continue;
                }
                visited.add(next);
                steps.add(new JoinStep(current, next, join));
                queue.add(next);
            }
        }

        if (!visited.containsAll(involved)) {
            Set<String> missing = new LinkedHashSet<>(involved);
            missing.removeAll(visited);
            throw new SemanticException(SemanticException.Reason.TABLES_NOT_CONNECTED,
                    "从表 " + root + " 出发接不到：" + missing);
        }

        // BFS 可能接进了用不到的表（路径上的中间表是必需的，但 BFS 会多接一些）。
        // 只保留"接到 involved 里某张表"的步骤，以及通往它们的中间步骤。
        return new JoinPlan(root, prune(steps, root, involved));
    }

    /**
     * 从某张表出发，沿该关联能走到哪张表；走不到返回 null。
     *
     * <p>这就是 LEFT JOIN 方向约束的落点：LEFT 边只能从左往右。
     */
    private static String traversableNeighbor(TableJoin join, String from) {
        if (join.leftTableId().equals(from)) {
            return join.rightTableId();
        }
        if (join.rightTableId().equals(from) && join.type() == TableJoin.JoinType.INNER) {
            // INNER 方向无关，可反向走
            return join.leftTableId();
        }
        return null;
    }

    /**
     * 裁掉与本次查询无关的分支。
     *
     * <p>BFS 会把它能走到的一切都接进来，但用户只问了其中几张表。
     * 多接的表会改变结果集（尤其 INNER JOIN 会过滤行），必须裁掉。
     *
     * <p>做法：从 involved 里的表反向回溯到 root，保留路径上的步骤。
     */
    private static List<JoinStep> prune(List<JoinStep> steps, String root, Set<String> involved) {
        // 建"谁把我接进来的"索引
        java.util.Map<String, JoinStep> parentOf = new java.util.HashMap<>();
        for (JoinStep s : steps) {
            parentOf.putIfAbsent(s.toTableId(), s);
        }

        Set<JoinStep> needed = new LinkedHashSet<>();
        for (String t : involved) {
            String cursor = t;
            while (!cursor.equals(root)) {
                JoinStep s = parentOf.get(cursor);
                if (s == null) {
                    break;
                }
                needed.add(s);
                cursor = s.fromTableId();
            }
        }

        // 按原 BFS 顺序输出，保证 fromTableId 一定在前
        return steps.stream().filter(needed::contains).toList();
    }
}

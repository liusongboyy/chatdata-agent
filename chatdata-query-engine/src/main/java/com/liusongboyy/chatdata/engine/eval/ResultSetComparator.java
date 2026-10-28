package com.liusongboyy.chatdata.engine.eval;

import com.liusongboyy.chatdata.engine.datasource.QueryResult;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 结果集比对（EX，execution accuracy）。
 *
 * <h2>判据是"两份数据是不是同一个答案"，不是"SQL 像不像"</h2>
 *
 * <p>同一个答案可以写成无数种 SQL：别名不同、列序不同、用 CTE 还是子查询。
 * 按 SQL 文本比对会把它们判错；按结果集比对才与"用户看到的数据对不对"等价。
 *
 * <h2>三处刻意放宽</h2>
 *
 * <ol>
 *   <li><strong>数值归一</strong>：{@code 2}、{@code 2.0}、{@code 2.0000} 视为同一个数。
 *       JDBC 对 COUNT / AVG / DECIMAL 的返回类型不同，这是噪声不是差异</li>
 *   <li><strong>行序</strong>：默认无序比对（多重集）。只有用例显式声明
 *       {@code ordered=true} 才比行序——带 LIMIT 的"前 N 名"才需要</li>
 *   <li><strong>列序</strong>：默认允许重排。{@code (status, cnt)} 与 {@code (cnt, status)}
 *       是同一份答案</li>
 * </ol>
 *
 * <p><strong>不放宽的是列数与行数</strong>：多一列、少一行都算错。
 * 那通常意味着 SQL 的口径不同（多一个过滤条件、少一个分组），正是要抓的东西。
 *
 * <h2>已知局限</h2>
 *
 * <p>列序放宽后，理论上两个语义不同但取值恰好相同的列可能互换后"碰巧"匹配。
 * 需要严格列序的用例把 {@link EvalCase#columnOrder()} 设为
 * {@link EvalCase.ColumnOrder#STRICT} 即可。
 */
public final class ResultSetComparator {

    /** 行内列分隔符。用不可见字符，避免与正常文本撞上。 */
    private static final String SEP = "\u001f";
    /** 报告里最多列出几行差异，避免失败信息淹没报告。 */
    private static final int MAX_DIFF_ROWS = 3;
    /** 列重排的穷举上限。再多就只按原序比——阶乘增长，且这种宽表几乎不会出现在问数里。 */
    private static final int MAX_PERMUTATION_COLUMNS = 5;

    private ResultSetComparator() {
    }

    /**
     * 严格 EX：列数必须一致。
     */
    public static Verdict compare(QueryResult gold, QueryResult actual,
                                  boolean ordered, boolean anyColumnOrder) {
        return compare(gold, actual, ordered, anyColumnOrder, false);
    }

    /**
     * @param gold      gold SQL 的结果
     * @param actual    系统回答对应的结果
     * @param ordered   行序是否参与比对
     * @param anyColumnOrder 是否允许列重排
     * @param allowExtraColumns 是否允许实际结果多返回列（包含式 EX）。
     *        多返回的列不参与比对，但 gold 的每一列都必须能在实际结果里找到对应的一列。
     *        用途：把"答案对但话多"与"答案错"分开统计
     */
    public static Verdict compare(QueryResult gold, QueryResult actual,
                                  boolean ordered, boolean anyColumnOrder,
                                  boolean allowExtraColumns) {
        QueryResult g = gold == null ? QueryResult.empty() : gold;
        QueryResult a = actual == null ? QueryResult.empty() : actual;

        // 两边都没有数据行：答案都是"空"，列形状不参与判定。
        // 系统在"没生成 SQL"时返回的是空结果，这与"查了但没数据"在这一点上不可区分——
        // 但那种情况会被空结果率单独统计，不在这里制造假负例
        if (g.rows().isEmpty() && a.rows().isEmpty()) {
            return Verdict.pass("两边都是空结果");
        }

        int goldColumns = g.columns().size();
        int actualColumns = a.columns().size();

        if (goldColumns > actualColumns) {
            return Verdict.fail("列数不同：gold %d 列，实际 %d 列".formatted(goldColumns, actualColumns));
        }
        if (!allowExtraColumns && goldColumns != actualColumns) {
            return Verdict.fail("列数不同：gold %d 列，实际 %d 列".formatted(goldColumns, actualColumns));
        }

        // 列数一致时，"是否允许列重排"由用例决定；
        // 列数不一致（包含式）时只能搜映射——否则"多出来的列插在中间"会被误判
        List<List<Integer>> orders;
        if (goldColumns == actualColumns) {
            orders = anyColumnOrder ? permutationsFor(goldColumns) : List.of(identity(goldColumns));
        } else {
            orders = injections(actualColumns, goldColumns);
        }

        Verdict closest = null;
        for (List<Integer> order : orders) {
            Verdict v = compareInOrder(g, a, order, ordered);
            if (v.equal()) {
                if (goldColumns < actualColumns) {
                    return Verdict.pass("结果正确（多返回了 " + (actualColumns - goldColumns) + " 列）");
                }
                return v;
            }
            if (closest == null || v.diffRows() < closest.diffRows()) {
                closest = v;
            }
        }
        return closest == null ? Verdict.fail("无法比对") : closest;
    }

    private static List<List<Integer>> permutationsFor(int n) {
        return n <= MAX_PERMUTATION_COLUMNS ? permutations(n) : List.of(identity(n));
    }

    private static Verdict compareInOrder(QueryResult gold, QueryResult actual,
                                          List<Integer> columnOrder, boolean ordered) {
        List<String> expected = gold.rows().stream().map(ResultSetComparator::rowKey).toList();
        int maxIndex = columnOrder.stream().mapToInt(Integer::intValue).max().orElse(-1);
        List<String> got = new ArrayList<>(actual.rows().size());
        for (List<Object> row : actual.rows()) {
            if (row.size() <= maxIndex) {
                return Verdict.fail("实际结果有一行的列数（%d）与表头（%d）不一致"
                        .formatted(row.size(), actual.columns().size()));
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < columnOrder.size(); i++) {
                if (i > 0) {
                    sb.append(SEP);
                }
                sb.append(normalize(row.get(columnOrder.get(i))));
            }
            got.add(sb.toString());
        }

        List<String> e = new ArrayList<>(expected);
        List<String> r = new ArrayList<>(got);
        if (!ordered) {
            Collections.sort(e);
            Collections.sort(r);
        }

        if (e.equals(r)) {
            return Verdict.pass(ordered ? "结果一致（含行序）" : "结果一致（行序无关）");
        }

        int diff = countDiff(e, r);
        return Verdict.fail(describe(e, r, diff), diff);
    }

    /** 多行元素逐一对照的差异数——只用于在多个列序里挑"最接近"的那个来报告。 */
    private static int countDiff(List<String> a, List<String> b) {
        int max = Math.max(a.size(), b.size());
        int diff = Math.abs(a.size() - b.size());
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            if (!a.get(i).equals(b.get(i))) {
                diff++;
            }
        }
        // 行数差已经把长度算进去了，这里只补同位置不一致的数量
        return Math.min(diff, max);
    }

    private static String describe(List<String> expected, List<String> actual, int diff) {
        StringBuilder sb = new StringBuilder("结果不一致：gold %d 行，实际 %d 行，约 %d 行不同"
                .formatted(expected.size(), actual.size(), diff));
        int shown = 0;
        for (int i = 0; i < Math.min(expected.size(), actual.size()) && shown < MAX_DIFF_ROWS; i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                sb.append("\n  第 ").append(i + 1).append(" 行：期望 [")
                        .append(display(expected.get(i))).append("]，实际 [")
                        .append(display(actual.get(i))).append(']');
                shown++;
            }
        }
        if (expected.size() != actual.size() && shown < MAX_DIFF_ROWS) {
            sb.append("\n  行数不同是最常见的口径差异：检查 WHERE 条件与 GROUP BY");
        }
        return sb.toString();
    }

    private static String display(String key) {
        String s = key.replace(SEP, " | ");
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }

    /**
     * 浮点数保留的有效位数。
     *
     * <h2>为什么必须截断</h2>
     *
     * <p>实测：同一条聚合，「供应商采购金额」gold 算出 {@code 25793.748}，
     * 模型那条算出 {@code 25793.747999999984}。两者是**同一个数**，
     * 只是 double 求和的累加顺序不同。用全精度做字符串相等会把 37 行判成不同——
     * 那是评测的缺陷，不是产品的错。
     *
     * <p>保留 6 位有效数字：足以区分真实的计算差异（大到 1e-6 的相对误差
     * 已经不是"同一个数"了），又能容忍浮点累加顺序带来的末位噪音。
     */
    private static final int FLOAT_PRECISION = 6;

    /**
     * 单值归一。
     *
     * <p>数值统一走 {@link BigDecimal}：{@code 2} 与 {@code 2.0000} 是同一个数，
     * 而 JDBC 对 COUNT（Long）与 AVG（BigDecimal）返回的类型不同。
     *
     * <p><b>浮点要按有效位数截断</b>——见 {@link #FLOAT_PRECISION} 的说明。
     * 用全精度做相等，会把"求和顺序不同"判成答错。
     *
     * <p>日期时间统一到 ISO 形状，避免 {@code Timestamp} 与 {@code LocalDateTime} 的
     * {@code toString} 差异被当成数据差异。
     */
    static String normalize(Object value) {
        if (value == null) {
            return "\u0000NULL";
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return String.valueOf(d);
            }
            // round 到 6 位有效数字再去尾零：25793.747999999984 → 25793.75 → 25793.75
            return new BigDecimal(d)
                    .round(new java.math.MathContext(FLOAT_PRECISION,
                            java.math.RoundingMode.HALF_UP))
                    .stripTrailingZeros().toPlainString();
        }
        if (value instanceof Number n) {
            try {
                return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                // NaN / Infinity 之类，保持原样
                return n.toString();
            }
        }
        if (value instanceof byte[] bytes) {
            return "0x" + HexFormat.of().formatHex(bytes);
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().toString();
        }
        if (value instanceof Date d) {
            return d.toLocalDate().toString();
        }
        if (value instanceof Time t) {
            return t.toLocalTime().toString();
        }
        return value.toString();
    }

    private static String rowKey(List<Object> row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) {
                sb.append(SEP);
            }
            sb.append(normalize(row.get(i)));
        }
        return sb.toString();
    }

    private static List<Integer> identity(int n) {
        return IntStream.range(0, n).boxed().toList();
    }

    /**
     * 从 {@code m} 个实际列里选出 {@code n} 个，保持顺序、不重复。
     *
     * <p>用于包含式比对：gold 的每一列都要在实际结果里找到一列对应，多出来的列忽略。
     * 列数很小时穷举是安全的；过宽就放弃，避免组合爆炸——
     * 问数结果不会出现几十列，真出现了说明 SQL 写坏了，判失败即可。
     */
    private static List<List<Integer>> injections(int m, int n) {
        if (n > MAX_PERMUTATION_COLUMNS || m > 9 || n > m) {
            return List.of();
        }
        List<List<Integer>> out = new ArrayList<>();
        collectInjections(new ArrayList<>(), new boolean[m], m, n, out);
        return out;
    }

    private static void collectInjections(List<Integer> current, boolean[] used,
                                          int m, int n, List<List<Integer>> out) {
        if (current.size() == n) {
            out.add(List.copyOf(current));
            return;
        }
        for (int i = 0; i < m; i++) {
            if (used[i]) {
                continue;
            }
            used[i] = true;
            current.add(i);
            collectInjections(current, used, m, n, out);
            current.remove(current.size() - 1);
            used[i] = false;
        }
    }

    private static List<List<Integer>> permutations(int n) {
        List<List<Integer>> out = new ArrayList<>();
        permute(new ArrayList<>(identity(n)), 0, out);
        return out;
    }

    private static void permute(List<Integer> current, int index, List<List<Integer>> out) {
        if (index == current.size()) {
            out.add(List.copyOf(current));
            return;
        }
        for (int i = index; i < current.size(); i++) {
            Collections.swap(current, index, i);
            permute(current, index + 1, out);
            Collections.swap(current, index, i);
        }
    }

    /**
     * 比对结论。
     *
     * @param equal   是否一致
     * @param reason  人类可读的结论/差异说明，直接进报告
     * @param diffRows 差异行数，仅用于在多个列序中挑最接近的来报告
     */
    public record Verdict(boolean equal, String reason, int diffRows) {

        static Verdict pass(String reason) {
            return new Verdict(true, reason, 0);
        }

        static Verdict fail(String reason) {
            return new Verdict(false, reason, Integer.MAX_VALUE);
        }

        static Verdict fail(String reason, int diffRows) {
            return new Verdict(false, reason, diffRows);
        }
    }
}

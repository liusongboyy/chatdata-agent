package com.liusongboyy.chatdata.engine.analysis;

import com.liusongboyy.chatdata.engine.datasource.QueryResult;
import com.liusongboyy.chatdata.engine.nlsql.AskDsl;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从分析步骤的结果里算出**确定性事实**。
 *
 * <h2>为什么算在这里，而不是让模型看着表格说</h2>
 *
 * <p>模型看几十行数字再下结论，错了不会报错——这与前几轮反复处理的
 * 「静默语义错误」是同一类问题。所以：数字由 Java 算，模型只负责措辞，
 * 并且只允许引用这里产出的数字。
 *
 * <h2>只做四种形状</h2>
 *
 * <ul>
 *   <li><b>时间序列</b>（一个时间维度）→ 趋势 + 离群点</li>
 *   <li><b>分类构成</b>（一个普通维度）→ 占比与集中度</li>
 *   <li><b>标量</b>（无维度）→ 不产出事实，它本身就是一个数</li>
 *   <li>其余形状（多维度、找不到指标列）→ 不猜</li>
 * </ul>
 *
 * <p><strong>脱敏列不参与计算</strong>：在 {@code ***} 上算出来的结论是垃圾，
 * 还会泄露受限数字的量级。该步的唯一指标被脱敏 → 整步不产出事实。
 */
public final class InsightCalculator {

    /** 时间序列至少要 3 个点才谈得上"趋势"。 */
    private static final int MIN_SERIES_POINTS = 3;
    /** 离群点检测至少要 8 个点，否则均值与标准差本身不可靠。 */
    private static final int MIN_OUTLIER_POINTS = 8;
    /** 构成里给出"前 N 项"的 N。 */
    private static final int TOP_N = 3;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private InsightCalculator() {
    }

    public static List<Finding> compute(List<StepResult> steps, SemanticModel model) {
        List<Finding> out = new ArrayList<>();
        for (StepResult step : steps) {
            if (!step.isOk() || step.rows().isEmpty() || step.dsl() == null) {
                continue;
            }
            AskDsl dsl = step.dsl();
            if (dsl.metrics().isEmpty() || isPrimaryMetricMasked(step)) {
                continue;
            }
            String metric = dsl.metrics().get(0);
            QueryResult rows = step.rows();
            int metricIdx = rows.columns().indexOf(metric);
            if (metricIdx < 0) {
                continue;
            }

            if (dsl.compare() != null) {
                out.addAll(compare(step, metric, metricIdx));
            }
            // 没有维度且只有一行：这次查询的答案就是一个数。
            // 它必须变成一条事实，否则结论区会空着——用户看到表格，
            // 却没有任何一句话说明这次算的是什么
            if (dsl.dimensions().isEmpty() && dsl.compare() == null && rows.rowCount() == 1) {
                out.addAll(scalar(step, metric, rows, metricIdx));
            }
            // 两个维度：交叉表。**这是最常见的分析形态之一**——
            // 实测「各大类里，线上和线下的销售表现有什么差别」拆出来全是
            // 「大类 + 线上/线下」两维，而当时只处理零维/一维，于是**一条事实都算不出**
            if (dsl.dimensions().size() >= 2) {
                // 多维交叉：自动挑一维当"系列"，其余合并成"行"。
                // 实测「各大类里线上和线下」拆出来是三维（大类 + 渠道 + 月份），
                // 只处理两维的话，这类最常见的分析会**一条事实都算不出**。
                out.addAll(crossTab(step, metric, rows, dsl.dimensions(), model));
                continue;
            }

            if (dsl.dimensions().size() == 1) {
                String dim = dsl.dimensions().get(0);
                int dimIdx = rows.columns().indexOf(dim);
                if (dimIdx < 0) {
                    continue;
                }
                if (isTemporal(dim, model)) {
                    List<Point> series = sortedSeries(rows, dimIdx, metricIdx);
                    out.addAll(trend(step, metric, series));
                    out.addAll(outliers(step, metric, series));
                } else {
                    out.addAll(contribution(step, metric, rows, dimIdx, metricIdx));
                }
            }
        }
        return out;
    }

    // ── 标量 ──

    private static List<Finding> scalar(StepResult step, String metric, QueryResult rows,
                                        int metricIdx) {
        BigDecimal value = decimal(cellRaw(rows.rows().get(0), metricIdx));
        if (value == null) {
            return List.of();
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("value", value);
        return List.of(new Finding(Finding.Kind.SCALAR, step.id(),
                "%s为 %s".formatted(metric, num(value)), ev));
    }

    // ── 时间序列 ──

    private record Point(String key, BigDecimal value) {
    }

    /** 按维度值排序。日期桶（yyyy / yyyy-MM / yyyy-MM-dd）的字符串序就是时间序。 */
    private static List<Point> sortedSeries(QueryResult rows, int dimIdx, int metricIdx) {
        List<Point> points = new ArrayList<>(rows.rowCount());
        for (List<Object> row : rows.rows()) {
            String key = cell(row, dimIdx);
            BigDecimal value = decimal(cellRaw(row, metricIdx));
            if (key != null && value != null) {
                points.add(new Point(key, value));
            }
        }
        points.sort(Comparator.comparing(Point::key));
        return points;
    }

    private static List<Finding> trend(StepResult step, String metric, List<Point> series) {
        List<Finding> out = new ArrayList<>(2);
        if (series.size() < MIN_SERIES_POINTS) {
            return out;
        }
        Point first = series.get(0);
        Point last = series.get(series.size() - 1);
        BigDecimal change = last.value().subtract(first.value());
        BigDecimal changePct = percentOf(change, first.value());

        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("firstPeriod", first.key());
        ev.put("firstValue", first.value());
        ev.put("lastPeriod", last.key());
        ev.put("lastValue", last.value());
        ev.put("change", change);
        if (changePct != null) {
            ev.put("changePct", changePct);
        }
        out.add(new Finding(Finding.Kind.TREND, step.id(),
                "「%s」到「%s」，%s 从 %s %s 到 %s%s".formatted(
                        first.key(), last.key(), metric,
                        num(first.value()), change.signum() >= 0 ? "升" : "降",
                        num(last.value()),
                        changePct == null ? ""
                                : "（" + (changePct.signum() >= 0 ? "+" : "") + pct(changePct) + "%）"),
                ev));

        // 最大单期变化：回答"哪一期掉的"比"整体降了多少"更接近用户的问题。
        // **只找与整体方向一致的那一期**——整体在涨时，"涨得最猛的一期"
        // 才解释得了涨；拿一个反向的波动当结论会误导。
        int maxAt = -1;
        BigDecimal maxPct = null;
        int direction = change.signum();
        for (int i = 1; i < series.size(); i++) {
            BigDecimal prev = series.get(i - 1).value();
            BigDecimal p = percentOf(series.get(i).value().subtract(prev), prev);
            if (p == null || (direction != 0 && p.signum() != direction)) {
                continue;
            }
            if (maxPct == null || p.abs().compareTo(maxPct.abs()) > 0) {
                maxPct = p;
                maxAt = i;
            }
        }
        if (maxAt < 0) {
            // 没有与整体同向的单期变化（例如一路锯齿但没有净变化）：退回绝对值最大的一期
            for (int i = 1; i < series.size(); i++) {
                BigDecimal prev = series.get(i - 1).value();
                BigDecimal p = percentOf(series.get(i).value().subtract(prev), prev);
                if (p != null && (maxPct == null || p.abs().compareTo(maxPct.abs()) > 0)) {
                    maxPct = p;
                    maxAt = i;
                }
            }
        }
        if (maxAt > 0 && maxPct != null) {
            Point prev = series.get(maxAt - 1);
            Point cur = series.get(maxAt);
            Map<String, Object> ev2 = new LinkedHashMap<>();
            ev2.put("period", cur.key());
            ev2.put("previousValue", prev.value());
            ev2.put("value", cur.value());
            ev2.put("changePct", maxPct);
            out.add(new Finding(Finding.Kind.TREND, step.id(),
                    "变化最大的是「%s」：较「%s」%s %s%%（%s → %s）".formatted(
                            cur.key(), prev.key(),
                            maxPct.signum() >= 0 ? "升" : "降",
                            pct(maxPct.abs()), num(prev.value()), num(cur.value())),
                    ev2));
        }
        return out;
    }

    private static List<Finding> outliers(StepResult step, String metric, List<Point> series) {
        List<Finding> out = new ArrayList<>(1);
        if (series.size() < MIN_OUTLIER_POINTS) {
            return out;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Point p : series) {
            sum = sum.add(p.value());
        }
        BigDecimal mean = sum.divide(BigDecimal.valueOf(series.size()), 6, RoundingMode.HALF_UP);
        BigDecimal variance = BigDecimal.ZERO;
        for (Point p : series) {
            BigDecimal d = p.value().subtract(mean);
            variance = variance.add(d.multiply(d));
        }
        BigDecimal sd = variance.divide(BigDecimal.valueOf(series.size()), 6, RoundingMode.HALF_UP)
                .sqrt(new java.math.MathContext(6, RoundingMode.HALF_UP));
        if (sd.signum() == 0) {
            return out;
        }
        BigDecimal threshold = sd.multiply(BigDecimal.valueOf(2));
        Point worst = null;
        BigDecimal worstDeviation = BigDecimal.ZERO;
        for (Point p : series) {
            BigDecimal deviation = p.value().subtract(mean).abs();
            if (deviation.compareTo(threshold) > 0 && deviation.compareTo(worstDeviation) > 0) {
                worst = p;
                worstDeviation = deviation;
            }
        }
        if (worst == null) {
            return out;
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("period", worst.key());
        ev.put("value", worst.value());
        ev.put("mean", mean);
        ev.put("stdDev", sd);
        out.add(new Finding(Finding.Kind.OUTLIER, step.id(),
                "「%s」的%s %s 明显偏离序列（均值 %s，标准差 %s，超出 2 倍标准差）".formatted(
                        worst.key(), metric, num(worst.value()), num(mean), num(sd)),
                ev));
        return out;
    }

    // ── 交叉表（两个维度）──

    /** 交叉表里当作"系列"的那一维，取值数不能太多——太多了读不出对比。 */
    private static final int MAX_SERIES_VALUES = 6;

    /**
     * 多维交叉：挑一维当"系列"，其余维度合并成"行"。
     *
     * <p>只产出两条事实，且都是能直接说给用户听的：
     * <ol>
     *   <li>整体上各系列的占比（「线上占 62.1%」）</li>
     *   <li>偏离整体格局最大的那一组（「Bikes 里线上占 81%，明显高于整体」）</li>
     * </ol>
     *
     * <p>不做"每组都报一遍"——几十行 × 多个系列会产出几十条事实，结论里塞不下，
     * 而且用户自己看表更快。
     *
     * <h3>系列的挑法</h3>
     *
     * <p>取**取值数最少且不超过 {@value #MAX_SERIES_VALUES}** 的那一维，且它不能是时间维。
     * 理由：系列的语义就是"可以并排比较的少数几类"（线上/线下、状态、等级），
     * 取值太多读不出对比；时间维应该按走势看，不是按占比看。
     *
     * <p>实测反例：「按日期 × 区域」被当成交叉表，算出了
     * 「2025-02 里华南占 100%」——因为每个「日期+区域」组合本来就只有一行。
     */
    private static List<Finding> crossTab(StepResult step, String metric, QueryResult rows,
                                          List<String> dims, SemanticModel model) {
        if (rows.columns().indexOf(metric) < 0) {
            return List.of();
        }
        // 统计每维的取值数
        Map<String, Integer> cardinality = new LinkedHashMap<>();
        for (String d : dims) {
            int idx = rows.columns().indexOf(d);
            if (idx < 0) {
                continue;
            }
            Set<String> vals = new LinkedHashSet<>();
            for (List<Object> r : rows.rows()) {
                String v = cell(r, idx);
                if (v != null) {
                    vals.add(v);
                }
            }
            if (!isTemporal(d, model) && vals.size() >= 2 && vals.size() <= MAX_SERIES_VALUES) {
                cardinality.put(d, vals.size());
            }
        }
        if (cardinality.isEmpty()) {
            return List.of();
        }
        // 基数并列时取**靠后**的那一维：系列的惯例是"列"（线上/线下这类），
        // 而"行"通常是主分类。取第一维会算出「车占 50%」这种废话
        String seriesDim = dims.stream()
                .filter(cardinality::containsKey)
                .reduce((a, b) -> cardinality.get(b) <= cardinality.get(a) ? b : a)
                .orElseThrow();
        List<String> rowDims = dims.stream().filter(d -> !d.equals(seriesDim)).toList();
        if (rowDims.isEmpty()) {
            return List.of();
        }

        int seriesIdx = rows.columns().indexOf(seriesDim);
        List<Integer> rowIdx = rowDims.stream().map(rows.columns()::indexOf)
                .filter(i -> i >= 0).toList();
        int metricIdx = rows.columns().indexOf(metric);
        if (rowIdx.isEmpty()) {
            return List.of();
        }

        Map<String, java.util.LinkedHashMap<String, BigDecimal>> grid = new LinkedHashMap<>();
        for (List<Object> row : rows.rows()) {
            String series = cell(row, seriesIdx);
            BigDecimal v = decimal(cellRaw(row, metricIdx));
            if (series == null || v == null) {
                continue;
            }
            String key = rowIdx.stream().map(i -> String.valueOf(cell(row, i)))
                    .reduce((a, b) -> a + " / " + b).orElse("");
            grid.computeIfAbsent(key, k -> new java.util.LinkedHashMap<>()).merge(series, v, BigDecimal::add);
        }
        if (grid.isEmpty()) {
            return List.of();
        }
        // **稀疏交叉不产出。** 若多数"行组"里只有一个系列，说明这两维其实是
        // 一一对应的（如「日期 × 区域」，每个日期只有一行），算出来的占比
        // 只会是 100% 或 0%，毫无信息量。实测这里曾产出
        // 「2025-02 里华南占 100%」——技术上没错，但读起来是废话
        long multiSeriesGroups = grid.values().stream().filter(m -> m.size() >= 2).count();
        if (multiSeriesGroups * 2 < grid.size()) {
            return List.of();
        }

        Map<String, BigDecimal> bySeries = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (var rowEntry : grid.values()) {
            for (var e : rowEntry.entrySet()) {
                bySeries.merge(e.getKey(), e.getValue(), BigDecimal::add);
                total = total.add(e.getValue());
            }
        }
        if (total.signum() <= 0) {
            return List.of();
        }

        List<Finding> out = new ArrayList<>(2);
        var top = bySeries.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
        if (top == null) {
            return out;
        }
        BigDecimal overallShare = percentOf(top.getValue(), total);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("series", seriesDim);
        ev.put("key", top.getKey());
        ev.put("value", top.getValue());
        ev.put("total", total);
        if (overallShare != null) {
            ev.put("sharePct", overallShare);
        }
        out.add(new Finding(Finding.Kind.CONTRIBUTION, step.id(),
                "整体上「%s」的%s占 %s%%（%s / %s）".formatted(
                        top.getKey(), metric,
                        overallShare == null ? "?" : pct(overallShare),
                        num(top.getValue()), num(total)), ev));

        if (overallShare == null) {
            return out;
        }
        // 偏离整体格局最大的那一组
        String worstKey = null;
        BigDecimal worstShare = null;
        BigDecimal worstValue = null;
        BigDecimal worstDiff = null;
        for (var e : grid.entrySet()) {
            BigDecimal rowTotal = e.getValue().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal v = e.getValue().get(top.getKey());
            if (v == null || rowTotal.signum() <= 0) {
                continue;
            }
            BigDecimal share = percentOf(v, rowTotal);
            if (share == null) {
                continue;
            }
            BigDecimal diff = share.subtract(overallShare).abs();
            if (worstDiff == null || diff.compareTo(worstDiff) > 0) {
                worstDiff = diff;
                worstShare = share;
                worstKey = e.getKey();
                worstValue = v;
            }
        }
        if (worstKey != null) {
            Map<String, Object> ev2 = new LinkedHashMap<>();
            ev2.put("series", seriesDim);
            ev2.put("key", worstKey);
            ev2.put("value", worstValue);
            ev2.put("sharePct", worstShare);
            ev2.put("overallSharePct", overallShare);
            out.add(new Finding(Finding.Kind.CONTRIBUTION, step.id(),
                    "「%s」里「%s」占 %s%%，整体是 %s%%——这一组偏离最大".formatted(
                            worstKey, top.getKey(), pct(worstShare), pct(overallShare)), ev2));
        }
        return out;
    }

    // ── 分类构成 ──

    private static List<Finding> contribution(StepResult step, String metric, QueryResult rows,
                                              int dimIdx, int metricIdx) {
        List<Finding> out = new ArrayList<>(2);
        if (rows.rowCount() < 2) {
            return out;
        }
        Map<String, BigDecimal> byKey = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (List<Object> row : rows.rows()) {
            String key = cell(row, dimIdx);
            BigDecimal value = decimal(cellRaw(row, metricIdx));
            if (key == null || value == null) {
                continue;
            }
            byKey.merge(key, value, BigDecimal::add);
            total = total.add(value);
        }
        // 合计不为正时占比没有意义（负数构成），不猜
        if (total.signum() <= 0 || byKey.isEmpty()) {
            return out;
        }

        List<Map.Entry<String, BigDecimal>> sorted = new ArrayList<>(byKey.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        Map.Entry<String, BigDecimal> top = sorted.get(0);
        BigDecimal topShare = percentOf(top.getValue(), total);
        if (topShare != null) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("key", top.getKey());
            ev.put("value", top.getValue());
            ev.put("total", total);
            ev.put("sharePct", topShare);
            out.add(new Finding(Finding.Kind.CONTRIBUTION, step.id(),
                    "「%s」占%s的 %s%%（%s / %s）".formatted(
                            top.getKey(), metric, pct(topShare),
                            num(top.getValue()), num(total)),
                    ev));
        }

        if (sorted.size() > TOP_N) {
            BigDecimal topSum = BigDecimal.ZERO;
            List<String> names = new ArrayList<>(TOP_N);
            for (int i = 0; i < TOP_N; i++) {
                topSum = topSum.add(sorted.get(i).getValue());
                names.add(sorted.get(i).getKey());
            }
            BigDecimal share = percentOf(topSum, total);
            if (share != null) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("keys", List.copyOf(names));
                ev.put("topSum", topSum);
                ev.put("total", total);
                ev.put("sharePct", share);
                out.add(new Finding(Finding.Kind.CONTRIBUTION, step.id(),
                        "前 %d 项（%s）合计占 %s%%".formatted(
                                TOP_N, String.join("、", names), pct(share)),
                        ev));
            }
        }
        return out;
    }

    // ── 同环比 ──

    private static List<Finding> compare(StepResult step, String metric, int metricIdx) {
        List<Finding> out = new ArrayList<>(1);
        AskDsl dsl = step.dsl();
        QueryResult rows = step.rows();
        int growthIdx = rows.columns().indexOf(metric + dsl.compare().label());
        if (growthIdx < 0) {
            return out;
        }
        int prevIdx = rows.columns().indexOf(metric + "上期");

        // 无维度：一行就是全部
        if (dsl.dimensions().isEmpty()) {
            if (rows.rowCount() != 1) {
                return out;
            }
            List<Object> row = rows.rows().get(0);
            BigDecimal growth = decimal(cellRaw(row, growthIdx));
            if (growth == null) {
                return out;
            }
            BigDecimal cur = decimal(cellRaw(row, metricIdx));
            BigDecimal prev = prevIdx < 0 ? null : decimal(cellRaw(row, prevIdx));
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("growthPct", growth.multiply(HUNDRED));
            if (cur != null) {
                ev.put("current", cur);
            }
            if (prev != null) {
                ev.put("previous", prev);
            }
            out.add(new Finding(Finding.Kind.COMPARE, step.id(),
                    "%s%s %s %s%%%s".formatted(
                            metric, dsl.compare().label(),
                            growth.signum() >= 0 ? "增长" : "下降",
                            pct(growth.multiply(HUNDRED)),
                            cur == null ? "" : "（本期 " + num(cur)
                                    + (prev == null ? "" : "，上期 " + num(prev)) + "）"),
                    ev));
            return out;
        }

        // 有维度：找变化最大的一项
        int dimIdx = rows.columns().indexOf(dsl.dimensions().get(0));
        if (dimIdx < 0) {
            return out;
        }
        String worstKey = null;
        BigDecimal worstGrowth = null;
        BigDecimal worstCur = null;
        for (List<Object> row : rows.rows()) {
            BigDecimal growth = decimal(cellRaw(row, growthIdx));
            String key = cell(row, dimIdx);
            if (growth == null || key == null) {
                continue;
            }
            if (worstGrowth == null || growth.abs().compareTo(worstGrowth.abs()) > 0) {
                worstGrowth = growth;
                worstKey = key;
                worstCur = decimal(cellRaw(row, metricIdx));
            }
        }
        if (worstKey != null && worstGrowth != null) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("key", worstKey);
            ev.put("growthPct", worstGrowth.multiply(HUNDRED));
            if (worstCur != null) {
                ev.put("current", worstCur);
            }
            out.add(new Finding(Finding.Kind.COMPARE, step.id(),
                    "「%s」的%s%s %s %s%%%s，是变化最大的一项".formatted(
                            worstKey, metric, dsl.compare().label(),
                            worstGrowth.signum() >= 0 ? "增长" : "下降",
                            pct(worstGrowth.abs().multiply(HUNDRED)),
                            worstCur == null ? "" : "（本期 " + num(worstCur) + "）"),
                    ev));
        }
        return out;
    }

    // ── 工具 ──

    private static boolean isPrimaryMetricMasked(StepResult step) {
        AskDsl dsl = step.dsl();
        return !dsl.metrics().isEmpty() && step.maskedColumns().contains(dsl.metrics().get(0));
    }

    private static boolean isTemporal(String dimension, SemanticModel model) {
        if (model == null) {
            return false;
        }
        try {
            return model.isTemporal(model.resolveDimension(dimension));
        } catch (RuntimeException e) {
            // 解析不了就别猜——把分类维度当成时间序列会得出无意义的"趋势"
            return false;
        }
    }

    private static Object cellRaw(List<Object> row, int idx) {
        return idx < row.size() ? row.get(idx) : null;
    }

    private static String cell(List<Object> row, int idx) {
        Object v = cellRaw(row, idx);
        return v == null ? null : v.toString();
    }

    static BigDecimal decimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        String s = v.toString().trim();
        if (s.isEmpty() || "***".equals(s)) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 百分比（0–100）。分母为 0 时返回 null——不能给一个"无穷增长"。 */
    private static BigDecimal percentOf(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return null;
        }
        return part.multiply(HUNDRED).divide(whole, 6, RoundingMode.HALF_UP);
    }

    /** 数值的展示形式：去掉多余的零，但不做千分位（评测要按数字抽取比对）。 */
    private static String num(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() <= 0 ? s.toPlainString() : s.toPlainString();
    }

    private static String pct(BigDecimal v) {
        return v.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }
}

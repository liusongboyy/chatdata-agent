package com.liusongboyy.chatdata.engine.report;

import java.util.List;

/**
 * 一次查询的**口径**：查了什么指标、按什么维度、什么时间、什么筛选。
 *
 * <h2>为什么单独一个类型</h2>
 *
 * <p>报告要把多轮问答拼在一起，而读者看到两张图时**默认它们可比**。
 * 如果一张是「今年全部区域」、另一张是「上个月华东」，把它们并排放，
 * 读者会得出一个不存在的对比结论。
 *
 * <p>所以口径必须能被**比较**——两个 {@code ReportScope} 相等还是不相等，
 * 决定了报告要不要在顶部提示「口径不一致」。
 *
 * @param metrics    指标，如 {@code ["保费", "保单数"]}
 * @param dimensions 维度，如 {@code ["区域"]}
 * @param time       时间口径，如 {@code "今年"}；无时间限制时为 null
 * @param filters    筛选条件，如 {@code ["区域 = 华东"]}
 */
public record ReportScope(
        List<String> metrics,
        List<String> dimensions,
        String time,
        List<String> filters
) {

    public ReportScope {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        filters = filters == null ? List.of() : List.copyOf(filters);
        time = (time == null || time.isBlank()) ? null : time.trim();
    }

    /**
     * 两组口径**能不能放在一起比**。
     *
     * <h2>只比时间与筛选，不比指标与维度</h2>
     *
     * <p>「今年各险种构成」与「今年各区域趋势」——指标维度不同，但**范围相同**，
     * 放在一份报告里完全合理，那是分析的不同侧面。
     *
     * <p>而「今年」与「上个月」、「华东」与「全部区域」——范围不同，
     * 并排放会让读者误以为可比。这才是要提示的。
     *
     * <p>这个判断写错的方向很危险：判得太宽，用户看到互相矛盾的图却没人提醒；
     * 判得太严，几乎每份报告都弹提示，用户学会无视它。
     */
    public boolean comparableWith(ReportScope other) {
        if (other == null) {
            return true;
        }
        return java.util.Objects.equals(time, other.time)
                && sameFilters(other.filters);
    }

    /**
     * 筛选条件按集合比，不比顺序。
     *
     * <p>{@code ["区域 = 华东", "状态 = 生效"]} 与 {@code ["状态 = 生效", "区域 = 华东"]}
     * 是同一组条件——顺序不同而已。按 List 比会判成"不同"，
     * 于是同一份口径的报告莫名其妙地提示冲突。
     */
    private boolean sameFilters(List<String> other) {
        return new java.util.HashSet<>(filters).equals(new java.util.HashSet<>(other));
    }

    /** 给人看的一句话，用于报告里「数据口径」那一节。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(filters.isEmpty() ? "全部范围" : String.join("、", filters));
        sb.append(" · ");
        sb.append(time == null ? "不限时间" : time);
        return sb.toString();
    }
}

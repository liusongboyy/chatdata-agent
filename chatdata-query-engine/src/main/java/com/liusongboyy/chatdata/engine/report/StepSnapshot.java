package com.liusongboyy.chatdata.engine.report;

import com.liusongboyy.chatdata.engine.chat.ChartSpec;

import java.util.List;

/**
 * 一步查询的快照。
 *
 * <h2>为什么是快照，不是引用</h2>
 *
 * <p>报告是**某一刻**的产物。三个月后有人打开它，看到的必须是当时算出来的数，
 * 而不是"现在重新跑一遍"的结果——后者与报告标题里写的时间点对不上。
 *
 * <p>所以这里把数据完整抄一份，包括 SQL 原文。代价是存储冗余，
 * 换来的是**报告永不变化**——那正是"报告"这个东西的意义。
 *
 * @param label      这一步的原始描述，来自 {@code StepResult.label}
 * @param purpose    为什么查这一步
 * @param columns    列名
 * @param rows       数据行
 * @param chart      图表规格（复用问数页的 {@link ChartSpec}）
 * @param sqlDisplay **展示用** SQL，参数已内联成字面量。
 *                   附录里给这个，而不是带 {@code ?} 的模板——
 *                   读者要能一眼看懂查了什么
 * @param scope      这一步的口径
 * @param rowCount   总行数。可能大于 {@code rows.size()}（快照会截断）
 */
public record StepSnapshot(
        String label,
        String purpose,
        List<String> columns,
        List<List<Object>> rows,
        ChartSpec chart,
        String sqlDisplay,
        ReportScope scope,
        int rowCount
) {

    /**
     * 每步最多保留多少行。
     *
     * <p>十轮问答 × 每轮上千行 = 报告体积失控。而读者不需要看全量——
     * 他要的是"长什么样"。完整数据在审计表里，附录给下载入口。
     *
     * <p>50 行足够看出分布与量级；超过就该用聚合重新问了。
     */
    public static final int MAX_ROWS = 50;

    public StepSnapshot {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
        scope = scope == null ? new ReportScope(List.of(), List.of(), null, List.of()) : scope;
        if (rowCount <= 0) {
            rowCount = rows.size();
        }
    }

    /** 快照是否被截断。附录据此说明「仅显示前 N 行」。 */
    public boolean truncated() {
        return rows.size() < rowCount;
    }

    /** 按 {@link #MAX_ROWS} 截断。 */
    public StepSnapshot capped() {
        if (rows.size() <= MAX_ROWS) {
            return this;
        }
        return new StepSnapshot(label, purpose, columns,
                List.copyOf(rows.subList(0, MAX_ROWS)), chart, sqlDisplay, scope, rowCount);
    }
}

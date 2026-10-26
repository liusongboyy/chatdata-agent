package com.liusongboyy.chatdata.engine.semantic;

import java.util.List;

/**
 * 一列的真实取值画像。
 *
 * <h2>为什么先画像、再让模型写描述</h2>
 *
 * <p>只给模型一个列名「status」，它只能猜；给它
 * {@code 取值：OPEN(214)、CLOSED(1013)、RESOLVED(375)}，它就能写出
 * "工单状态，取值 OPEN=待处理 / CLOSED=已关闭 / RESOLVED=已解决" 这种可直接进 prompt 的说明。
 *
 * <p>BIRD 企业级实测：无列注释 66.5% → 有注释 73.8%，且**自动生成的比人工写的更好**
 * （自动 72.2% vs 人工 71.0%）。画像就是这个"自动生成"的输入。
 *
 * @param column      列名（物理名）
 * @param type        数据库类型
 * @param distinctCount 去重后的取值个数。**判"是不是枚举列"的依据**——
 *                    取值为 3 列是状态，取值为 3 万列是金额
 * @param nullCount   空值个数
 * @param totalRows   采样行数
 * @param samples     取值样例；枚举列给全部取值，其余列给前几个
 */
public record ColumnProfile(
        String column,
        String type,
        long distinctCount,
        long nullCount,
        long totalRows,
        List<String> samples
) {

    /** 取值不多的列——状态、类型、渠道这类，值得把所有取值都列出来。 */
    private static final int ENUM_MAX_DISTINCT = 12;
    /** 最多给几个样例。再多会把 prompt 撑长，而对理解语义没有额外帮助。 */
    private static final int MAX_SAMPLES = 8;

    public ColumnProfile {
        samples = samples == null ? List.of() : List.copyOf(samples);
    }

    /** 是否像枚举列。 */
    public boolean isCategorical() {
        return distinctCount > 0 && distinctCount <= ENUM_MAX_DISTINCT && distinctCount < totalRows;
    }

    public static int maxSamples() {
        return MAX_SAMPLES;
    }

    public static int enumMaxDistinct() {
        return ENUM_MAX_DISTINCT;
    }

    /**
     * 画个像给模型看的一行摘要。
     *
     * <p>枚举列把取值全列出来（模型最需要的就是这些字面量）；
     * 其余列只给几个样例 + 基数，够模型判断"这是数值还是文本"。
     */
    public String toPromptLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(isCategorical() ? "取值" : "样例").append("：");
        sb.append(String.join("、", samples));
        if (distinctCount > 0) {
            sb.append("（共 ").append(distinctCount).append(" 种）");
        }
        return sb.toString();
    }
}

package com.liusongboyy.chatdata.engine.semantic;

import java.util.List;

/**
 * 一个指标引用：普通列指标、带统计条件的指标，或派生指标。
 *
 * <h2>为什么要分成这几种</h2>
 *
 * <p>普通指标对应**一列 + 一个聚合**（{@code SUM(premium)}）；
 * 带条件的指标把聚合限制在满足条件的行上（{@code COUNT(CASE WHEN 状态 = ? THEN 工单号 END)}）；
 * 派生指标对应**若干指标之间的运算**（{@code SUM(a)/SUM(b)}）。
 * 后两者都没有单一物理列，用 {@link FieldRef} 表达不了。
 *
 * <p>但三者在 DSL 里的写法完全一样——{@code {"metrics":["赔付率"]}}。
 * 差别只在渲染阶段展开，模型感知不到。
 *
 * @see DerivedMetric
 * @see MetricCondition
 */
public sealed interface MetricRef {

    /**
     * 普通列指标，可带统计条件。
     *
     * @param ref       列 + 聚合方式
     * @param condition 统计条件；{@code null} 表示不过滤
     */
    record Column(FieldRef ref, Condition condition) implements MetricRef {

        /** 不带条件的普通指标。 */
        public Column(FieldRef ref) {
            this(ref, null);
        }
    }

    /**
     * 已解析的统计条件。
     *
     * <p>字段在这里已经解析成 {@link FieldRef}，渲染器才知道它该写在哪张表的别名上——
     * 条件字段可能是**跨表**的（星型模型里状态在维表上很常见）。
     */
    record Condition(FieldRef field, String op, String value) {
    }

    /**
     * 派生指标。
     *
     * @param spec   定义（比值 / 差值 / 时长）
     * @param inputs 已解析的输入。比值/差值是两个指标（各自可能带条件），
     *               时长是两个时间列。**顺序与 spec 的语义一致**，渲染器按下标取用
     */
    record Derived(DerivedMetric spec, List<MetricRef> inputs) implements MetricRef {

        public Derived {
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
        }
    }
}

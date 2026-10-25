package com.liusongboyy.chatdata.engine.semantic;

import java.util.List;

/**
 * 一个指标引用：普通列指标，或派生指标。
 *
 * <h2>为什么要分成两种</h2>
 *
 * <p>普通指标对应**一列 + 一个聚合**（{@code SUM(premium)}），
 * 而派生指标对应**若干列之间的运算**（{@code SUM(a)/SUM(b)}）。
 * 后者没有单一物理列，用 {@link FieldRef} 表达不了。
 *
 * <p>但两者在 DSL 里的写法完全一样——{@code {"metrics":["保费","赔付率"]}}。
 * 差别只在渲染阶段展开，模型感知不到。
 *
 * @see DerivedMetric
 */
public sealed interface MetricRef {

    /** 普通列指标：一列加一个聚合方式。 */
    record Column(FieldRef ref) implements MetricRef {
    }

    /**
     * 派生指标。
     *
     * @param spec   定义（比值 / 差值 / 时长）
     * @param inputs 已解析的输入列。比值与差值有 2 个（左、右），时长有 2 个（起、止）。
     *               **顺序与 spec 的语义一致**，渲染器按下标取用
     */
    record Derived(DerivedMetric spec, List<FieldRef> inputs) implements MetricRef {

        public Derived {
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
        }
    }
}

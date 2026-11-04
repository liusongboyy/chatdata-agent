package com.liusongboyy.chatdata.engine.agent;

/**
 * 模型产出 + 这次产出花掉的 token。
 *
 * <h2>为什么必须绑在一起返回</h2>
 *
 * <p>早先 {@code DslSource.generate} 只返回 DSL，{@code Msg} 里的 usage 被直接丢掉。
 * 事后补采是不可能的——那次调用的消耗只在那个 {@code Msg} 上存在过一次。
 *
 * <p>做成<b>返回值的一部分</b>而不是单例上的旁路字段：{@code DslSource} 是单例，
 * 旁路字段在并发提问下会被别的请求覆盖，而那种错是**静默**的——
 * 审计里的 token 记到了别人头上，只有对账时才发现。
 *
 * @param value 产出（DSL / 分析计划 / 结论文本）
 * @param usage 本次产出消耗的 token；实现方拿不到时为 {@link TokenUsage#UNKNOWN}
 */
public record Generated<T>(T value, TokenUsage usage) {

    public Generated {
        usage = usage == null ? TokenUsage.UNKNOWN : usage;
    }

    /** 没有 usage 时的便捷构造。**只该用在测试替身上**——真实实现拿不到 usage 说明采集坏了。 */
    public static <T> Generated<T> of(T value) {
        return new Generated<>(value, TokenUsage.UNKNOWN);
    }
}

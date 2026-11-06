package com.liusongboyy.chatdata.engine.agent;

/**
 * 一次问数里模型消耗的 token 快照。
 *
 * <h2>为什么自己定义而不是直接用 agentscope 的 {@code ChatUsage}</h2>
 *
 * <p>审计记录要能独立持久化，而"这次花了多少 token"是<b>业务事实</b>，
 * 不是"模型怎么调"的实现细节。把框架类型写进审计契约，等于让表结构跟着
 * agentscope 的版本走。转换只发生在 {@link UsageCollector} 一处。
 *
 * <h2>字段语义（实测于火山方舟，换供应商须重验）</h2>
 *
 * <ul>
 *   <li>{@code inputTokens} <b>已包含</b> {@code cachedTokens}——不能相加，
 *       否则成本高估一倍</li>
 *   <li>{@code outputTokens} <b>已包含</b> {@code reasoningTokens}——单独加会重复计费</li>
 * </ul>
 *
 * <p>详见 {@code com.liusongboyy.chatdata.engine.model.Cost} 与
 * {@code chatdata-agent-design/model-cost/spec.md} §3.2。
 *
 * @param inputTokens         输入总量（含缓存命中部分）
 * @param outputTokens        输出总量（含推理部分）
 * @param cachedTokens        缓存命中的输入，是 {@code inputTokens} 的子集
 * @param cacheCreationTokens 写缓存的消耗（部分供应商单独计费）
 * @param reasoningTokens     推理 token，是 {@code outputTokens} 的子集
 */
public record TokenUsage(
        int inputTokens,
        int outputTokens,
        int cachedTokens,
        int cacheCreationTokens,
        int reasoningTokens
) {

    /** 零消耗。用于累加的起点。 */
    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0, 0);

    /**
     * 没采到 usage。
     *
     * <p><b>与 {@link #ZERO} 不同，这个区别要一直带到数据库里</b>：
     * ZERO 是"确实没消耗"，UNKNOWN 是"不知道"。写进审计时前者是 0，
     * 后者必须是 NULL——成本看板靠这个区分「免费」与「没配」。
     *
     * <p>用全 -1 而不是 null 表示，是为了让 {@link #plus} 的类型是干净的
     * {@code TokenUsage}，不必到处判空。
     */
    public static final TokenUsage UNKNOWN = new TokenUsage(-1, -1, -1, -1, -1);

    /** 累加。多次模型调用（重问、重试）的消耗必须加起来。 */
    public TokenUsage plus(TokenUsage other) {
        if (other == null || !other.known()) {
            return this;
        }
        if (!known()) {
            return other;
        }
        return new TokenUsage(
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                cachedTokens + other.cachedTokens,
                cacheCreationTokens + other.cacheCreationTokens,
                reasoningTokens + other.reasoningTokens);
    }

    public boolean known() {
        return inputTokens >= 0;
    }

    /**
     * 缓存命中率（0–100，一位小数）。
     *
     * <p>没有输入 token 时返回 {@code null} 而不是 0——0% 和"没有数据"是两回事，
     * 混在一起会让看板上出现一排毫无意义的 0%。
     */
    public Double cacheHitRate() {
        if (!known() || inputTokens <= 0) {
            return null;
        }
        return Math.round(cachedTokens * 1000.0 / inputTokens) / 10.0;
    }
}

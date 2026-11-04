package com.liusongboyy.chatdata.engine.model;

import java.math.BigDecimal;

/**
 * 一次调用的成本。
 *
 * <h2>计价公式的两处反直觉</h2>
 *
 * <p>用真实调用实测过（2026-11-04，火山方舟），不是查文档得出的：
 *
 * <pre>
 * 第 1 次:  prompt=1032  cached=0      completion=42
 * 第 2 次:  prompt=1032  cached=1024   completion=49
 * </pre>
 *
 * <p><b>① {@code prompt_tokens} 已包含 {@code cached_tokens}。</b>
 * 两次 prompt 都是 1032，第二次缓存命中了 1024——如果 cached 是额外加的，
 * 第二次应该变成 2056。
 *
 * <p>所以未缓存部分要减掉：{@code (input - cached) × 全价 + cached × 缓存价}。
 * 写成 {@code input × 全价 + cached × 缓存价} 会<b>高估一倍</b>。
 *
 * <p><b>② {@code reasoning_tokens} 已含在 {@code completion_tokens} 里。</b>
 * 实测 completion=29 其中 reasoning=27，不是 56。单独加会重复计费。
 *
 * <h2>换供应商必须重跑这个测试</h2>
 *
 * <p>各家语义不同。这三条结论只对当前验证过的供应商成立。
 */
public record Cost(
        BigDecimal amount,
        long inputTokens,
        long cachedTokens,
        long outputTokens
) {

    /** 一亿（用于把"元/百万 token"换算成"元/token"）。 */
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    /**
     * 计算一次调用的费用。
     *
     * @return 费用；模型没配完整单价时返回 {@code null}——
     *         **不返回 0**，0 会让成本看起来不存在
     */
    public static Cost of(ModelDef model, long inputTokens, long cachedTokens,
                          long outputTokens) {
        if (!model.priced()) {
            return null;
        }
        // 缓存命中是 inputTokens 的子集，不能相加
        long uncached = Math.max(0, inputTokens - cachedTokens);
        BigDecimal cachedPrice = model.priceCached() == null
                ? model.priceInput()   // 没配缓存价就按全价算——宁可高估，不要凭空便宜
                : model.priceCached();

        BigDecimal amount = BigDecimal.valueOf(uncached)
                .multiply(model.priceInput()).divide(MILLION)
                .add(BigDecimal.valueOf(cachedTokens).multiply(cachedPrice).divide(MILLION))
                .add(BigDecimal.valueOf(outputTokens)
                        .multiply(model.priceOutput()).divide(MILLION));

        // 保留 6 位：单次调用常见是 0.00xxx 元，4 位会全被舍成 0
        return new Cost(amount.setScale(6, java.math.RoundingMode.HALF_UP),
                inputTokens, cachedTokens, outputTokens);
    }
}

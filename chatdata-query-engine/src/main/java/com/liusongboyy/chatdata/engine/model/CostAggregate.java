package com.liusongboyy.chatdata.engine.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 成本统计的聚合结果。
 *
 * <h2>为什么"费用"允许是 null</h2>
 *
 * <p>三个原因彼此独立，混在一起处理会把三件事说成一件：
 *
 * <ol>
 *   <li><b>模型没配单价</b>——能数 token，算不出钱。显示 0 元等于说"免费"</li>
 *   <li><b>老数据没采 token</b>——加列之前的记录 token 全是 NULL</li>
 *   <li><b>供应商没报 usage</b>——某些失败调用拿不到</li>
 * </ol>
 *
 * <p>所以这里把「有消耗的记录数」和「能算出钱的记录数」分开数
 * （{@code pricedAsks} vs {@code asks}）。管理员据此知道这个金额覆盖了多少比例——
 * 一个只覆盖 10% 请求的总额，不能拿来汇报。
 */
public final class CostAggregate {

    private CostAggregate() {
    }

    /**
     * 一个分组（按模型 / 按域 / 按用户）的统计。
     *
     * @param key        分组键（模型键 / 域 id / 用户名）
     * @param label      展示名。模型显示名、域显示名、用户名
     * @param asks       这个分组下的成功提问数
     * @param pricedAsks **能算出费用**的提问数。与 asks 的差就是"没配置单价或没采到 token"
     * @param amount     费用合计；一条都算不出时为 null（不是 0）
     * @param inputTokens  输入合计。**含缓存命中**，看板展示要单独标
     * @param cachedTokens 缓存命中合计
     * @param outputTokens 输出合计
     * @param cacheHitRate 缓存命中率（0–100）；没有输入 token 时 null
     * @param pricedAt     单价生效日期；未配单价时 null
     */
    public record Row(String key, String label, long asks, long pricedAsks,
                      BigDecimal amount,
                      long inputTokens, long cachedTokens, long outputTokens,
                      Double cacheHitRate,
                      java.time.LocalDateTime pricedAt) {

        /** 有没有算不出来的部分。前端据此显示「部分未计价」而不是假装金额完整。 */
        public boolean partial() {
            return pricedAsks < asks;
        }

        /** 完全没配单价（一条都算不出，且有提问）——与"部分未计价"要分开说。 */
        public boolean unpriced() {
            return asks > 0 && pricedAsks == 0;
        }
    }

    /**
     * 成本看板。
     *
     * @param days   时间窗口
     * @param total  总计
     * @param byModel 按模型；**包含零提问的模型**——那多半是配了没用上，
     *                省略会让管理员以为配置没生效
     * @param byDomain 按域
     * @param notice 性能提示；null 表示正常
     */
    public record Report(int days, Totals total, List<Row> byModel,
                         List<Row> byDomain, List<UserRow> byUser, String notice) {
    }

    /**
     * 总计。
     *
     * @param amount     总额；一条都算不出时为 null
     * @param pricedAsks 能算出费用的提问数
     * @param avgPerAsk  单次均价；pricedAsks 为 0 时 null
     */
    public record Totals(long asks, long pricedAsks, BigDecimal amount, BigDecimal avgPerAsk,
                         long inputTokens, long cachedTokens, long outputTokens,
                         Double cacheHitRate) {
    }

    /**
     * 按用户。
     *
     * <p><b>只在有管理权限时下发</b>——普通用户不该看到同事花多少钱。
     * 越权过滤在服务层做，不靠前端隐藏。
     *
     * @param topModelKey 这个人最常用的模型键
     */
    public record UserRow(String username, long asks, long pricedAsks, BigDecimal amount,
                          String topModelKey) {
    }
}

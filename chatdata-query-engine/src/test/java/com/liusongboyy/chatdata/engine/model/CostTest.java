package com.liusongboyy.chatdata.engine.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 计价。
 *
 * <p>这里测的两条反直觉结论来自<b>真实调用实测</b>（火山方舟，两次同样的 prompt）：
 *
 * <pre>
 * 第 1 次:  prompt=1032  cached=0      completion=42
 * 第 2 次:  prompt=1032  cached=1024   completion=49
 * </pre>
 *
 * <p>两次 prompt 都是 1032，第二次缓存命中了 1024。若 cached 是额外加的，
 * 第二次应变成 2056——它没有。所以 <b>input 已含 cached</b>。
 */
class CostTest {

    private static ModelDef model(String input, String cached, String output) {
        return new ModelDef("k", "k", null, "u", "m", List.of(),
                dec(input), dec(cached), dec(output), null, false, true);
    }

    private static BigDecimal dec(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    @Test
    @DisplayName("★ cached 是 input 的子集——不能相加，否则高估一倍")
    void cachedIsSubsetOfInput() {
        // 1000 输入其中 800 命中，200 输出
        // 未缓存 200 × 2元/MTok + 缓存 800 × 0.2元/MTok + 输出 200 × 8元/MTok
        var c = Cost.of(model("2", "0.2", "8"), 1000, 800, 200);

        // 200*2/1e6 = 0.0004；800*0.2/1e6 = 0.00016；200*8/1e6 = 0.0016
        assertThat(c.amount()).isEqualByComparingTo(new BigDecimal("0.00216"));
        // 如果错写成 input × 全价 + cached × 缓存价 = 0.002+0.00016+0.0016 = 0.00376
        assertThat(c.amount()).isLessThan(new BigDecimal("0.003"));
    }

    @Test
    @DisplayName("没有缓存时按全价算全部输入")
    void noCachePaysFullPrice() {
        var c = Cost.of(model("2", "0.2", "8"), 1000, 0, 0);

        assertThat(c.amount()).isEqualByComparingTo(new BigDecimal("0.002"));
    }

    @Test
    @DisplayName("★ 没配缓存单价 → 按全价算，宁可高估")
    void missingCachedPriceFallsBackToFullPrice() {
        // 凭空便宜比高估危险：前者会让管理员以为缓存很有用，实际没省那么多
        var c = Cost.of(model("2", null, "8"), 1000, 1000, 0);

        assertThat(c.amount()).isEqualByComparingTo(new BigDecimal("0.002"));
    }

    @Test
    @DisplayName("★ 没配单价 → null，不是 0")
    void unpricedReturnsNull() {
        var noPrice = new ModelDef("k", "k", null, "u", "m", List.of(),
                null, null, null, null, false, true);

        assertThat(Cost.of(noPrice, 1000, 0, 0))
                .as("返回 0 会让成本看板显示「免费」")
                .isNull();
    }

    @Test
    @DisplayName("模型为 null（历史记录里的模型已被删）→ null")
    void nullModelReturnsNull() {
        assertThat(Cost.of(null, 1000, 0, 0)).isNull();
    }

    @Test
    @DisplayName("cached > input 的脏数据不产生负数")
    void cachedLargerThanInputIsClamped() {
        var c = Cost.of(model("2", null, "8"), 100, 500, 0);

        assertThat(c.amount()).as("未缓存部分按 0 算，不会倒扣").isNotNegative();
    }
}

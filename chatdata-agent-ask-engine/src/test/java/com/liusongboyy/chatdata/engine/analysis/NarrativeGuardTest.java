package com.liusongboyy.chatdata.engine.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结论的数字白名单。
 *
 * <h2>它防的是哪种错</h2>
 *
 * <p>模型编一个数字时，句子通顺、量级合理，界面上完全可信。
 * 这一层的作用是把"无法溯源的数字"变成可检测的失败，并退回确定性文本。
 */
class NarrativeGuardTest {

    private static Finding finding(String text, String key, String value) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put(key, new BigDecimal(value));
        return new Finding(Finding.Kind.TREND, "s1", text, ev);
    }

    private static final List<Finding> FACTS = List.of(
            finding("华东占 60.0%", "sharePct", "60.000000"),
            finding("整体降了 25.0%", "changePct", "-25.000000"));

    @Test
    @DisplayName("★ 引用了 evidence 里的数字 → 通过")
    void acceptsKnownNumbers() {
        assertThat(NarrativeGuard.violation(
                "华东占 60%，整体下降了 25%。", FACTS)).isEmpty();
    }

    @Test
    @DisplayName("★ 编了一个新数字 → 拒绝，并指出是哪个")
    void rejectsUnknownNumber() {
        var v = NarrativeGuard.violation("华东占 73%，整体下降了 25%。", FACTS);

        assertThat(v).isPresent();
        assertThat(v.get()).contains("73");
    }

    @Test
    @DisplayName("允许舍入：62.34 → 62.3 / 62 都算命中")
    void allowsRounding() {
        List<Finding> facts = List.of(finding("x", "sharePct", "62.34"));

        assertThat(NarrativeGuard.violation("占比 62.3%。", facts)).isEmpty();
        assertThat(NarrativeGuard.violation("占比 62%。", facts)).as("相对差在 0.5% 内").isEmpty();
    }

    @Test
    @DisplayName("小数与百分数的换算：evidence 里 0.6 可写作 60%")
    void allowsPercentForm() {
        List<Finding> facts = List.of(finding("x", "share", "0.6"));

        assertThat(NarrativeGuard.violation("占比 60%。", facts)).isEmpty();
    }

    @Test
    @DisplayName("1–4 的整数算结构性数字（「3 个分类」），不当作业务数字")
    void structuralNumbersAllowed() {
        assertThat(NarrativeGuard.violation("这 3 个分类合计占 60%。", FACTS)).isEmpty();
    }

    @Test
    @DisplayName("5 以上的整数不豁免 —— 那多半是业务数字")
    void largeIntegerNotExempt() {
        assertThat(NarrativeGuard.violation("共 7 个分类。", FACTS)).isPresent();
    }

    @Test
    @DisplayName("★ 退回确定性文本：把 facts 连起来，每个数字都可溯源")
    void deterministicFallback() {
        assertThat(NarrativeGuard.deterministicNarrative(FACTS))
                .isEqualTo("华东占 60.0%；整体降了 25.0%。");
        assertThat(NarrativeGuard.deterministicNarrative(List.of())).isNull();
    }
}

package com.liusongboyy.chatdata.engine.analysis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条**由 Java 算出来**的事实。
 *
 * <h2>为什么它必须带 evidence</h2>
 *
 * <p>结论区会写"产品A贡献了 62% 的降幅"。这个 62% 必须能对回原始的分子分母——
 * 否则用户只能选择相信或不信。evidence 里放参与计算的数字，
 * 审计与界面都能核对。
 *
 * <p>模型在叙述阶段**只允许引用这里出现的数字**（见叙述合成的数字白名单）。
 *
 * @param kind     事实类型
 * @param stepId   来自哪个步骤
 * @param text     人话描述，数字已经是可读格式
 * @param evidence 计算依据：键名稳定（first/last/change/…），值一律 BigDecimal 或字符串
 */
public record Finding(Kind kind, String stepId, String text, Map<String, Object> evidence) {

    public enum Kind {
        /** 时间序列的变化。 */
        TREND,
        /** 分类构成：占比与集中度。 */
        CONTRIBUTION,
        /** 同环比 / 自定义对比的结果。 */
        COMPARE,
        /** 偏离均值的点。 */
        OUTLIER,
        /**
         * 单值事实：没有维度、只有一行。
         *
         * <p>它是"这次查询的答案本身"。没有它的话，「保费总额是多少」这类问题
         * 会**一条事实都算不出来**，结论区随之空白——用户只看到一张表，
         * 却没有任何一句话说明这次算的是什么。
         */
        SCALAR
    }

    public Finding {
        if (kind == null || stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("事实必须有类型和来源步骤");
        }
        text = text == null ? "" : text.trim();
        // 保持插入顺序：同样的输入必须产出同样的文本与顺序，否则评测不可复现
        evidence = evidence == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
    }
}

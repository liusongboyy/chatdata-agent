package com.liusongboyy.chatdata.engine.analysis;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结论的数字白名单。
 *
 * <h2>为什么必须有这一层</h2>
 *
 * <p>模型写结论时最容易出的错不是语法，而是**编一个看起来合理的数字**。
 * 这类错误在界面上完全可信：句子通顺、量级合理，用户没有别的途径核对。
 *
 * <p>所以：结论里出现的每一个数字，都必须能在 {@link Finding} 的 evidence 里找到。
 * 找不到就**退回确定性文本**（把 findings 用分号连起来）——宁可读起来生硬，
 * 也不给出一个无法溯源的数字。
 *
 * <h2>允许的误差</h2>
 *
 * <p>模型常把 62.34% 写成 62.3% 或 62%。故比较时按**数值**而不是字符串：
 * 绝对差 ≤ 0.05，或相对差 ≤ 0.5% 视为同一个数。
 */
public final class NarrativeGuard {

    /** 数字（含负号与小数）。不匹配年份之外的孤立数字也会被抓到，这正是目的。 */
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    /** 允许的绝对误差：覆盖 62.34 → 62.3 的舍入。 */
    private static final BigDecimal ABS_TOLERANCE = new BigDecimal("0.05");
    /** 允许的相对误差：覆盖大数上的舍入（如 1,234,567 → 123.4 万）。 */
    private static final BigDecimal REL_TOLERANCE = new BigDecimal("0.01");

    private NarrativeGuard() {
    }

    /**
     * 校验结论。
     *
     * @return 违规说明；空表示通过
     */
    public static Optional<String> violation(String narrative, List<Finding> findings) {
        return violation(narrative, findings, Set.of());
    }

    /**
     * @param structural 额外的"结构性数字"——不是业务数据，但确实来自本次结果。
     *                   典型是**结果集规模**：「7 个大类」里的 7 来自步骤行数。
     *                   不放行的话，模型说"这 7 个分类"就会被白名单拦下，
     *                   而那明明是它亲眼看到的事实
     */
    public static Optional<String> violation(String narrative, List<Finding> findings,
                                             Set<BigDecimal> structural) {
        if (narrative == null || narrative.isBlank()) {
            return Optional.empty();
        }
        Set<BigDecimal> allowed = allowedNumbers(findings);
        allowed.addAll(structural);
        List<String> unknown = new ArrayList<>();

        Matcher m = NUMBER.matcher(narrative);
        while (m.find()) {
            BigDecimal n;
            try {
                n = new BigDecimal(m.group());
            } catch (NumberFormatException e) {
                continue;
            }
            // 步骤编号（s1/s2）里的数字、以及"3 步"这类结构性数字不在 evidence 里，
            // 但它们无害：只校验"看起来像业务数字"的（>= 1 且不是 1–4 的纯整数）
            if (isStructural(n)) {
                continue;
            }
            if (!allowed(allowed, n)) {
                unknown.add(m.group());
            }
        }
        if (unknown.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("结论里出现了未在计算事实中的数字：" + String.join("、", unknown));
    }

    /**
     * 结构性数字：1–4 的整数。它们通常是"3 个分类""2 步"这类叙述，
     * 不进 evidence 也无害。**不适用于 5 以上**——那多半是业务数字。
     */
    private static boolean isStructural(BigDecimal n) {
        return n.stripTrailingZeros().scale() <= 0
                && n.abs().compareTo(BigDecimal.ONE) >= 0
                && n.abs().compareTo(new BigDecimal("4")) <= 0;
    }

    private static boolean allowed(Set<BigDecimal> allowed, BigDecimal n) {
        for (BigDecimal a : allowed) {
            // **按绝对值比**：evidence 里存的是 -0.25（下降），而结论会写成
            // 「下降了 25%」（正数）。符号由文字承担，数值本身没变
            if (closeEnough(a, n) || closeEnough(a.abs(), n.abs())) {
                return true;
            }
        }
        return false;
    }

    private static boolean closeEnough(BigDecimal a, BigDecimal b) {
        BigDecimal diff = a.subtract(b).abs();
        if (diff.compareTo(ABS_TOLERANCE) <= 0) {
            return true;
        }
        return a.signum() != 0
                && diff.divide(a.abs(), 6, java.math.RoundingMode.HALF_UP)
                .compareTo(REL_TOLERANCE) <= 0;
    }

    /** evidence 里所有数值，加上百分比形态（0.1 → 10）。 */
    private static Set<BigDecimal> allowedNumbers(List<Finding> findings) {
        Set<BigDecimal> out = new LinkedHashSet<>();
        if (findings == null) {
            return out;
        }
        for (Finding f : findings) {
            for (Object v : f.evidence().values()) {
                collect(v, out);
            }
        }
        return out;
    }

    private static void collect(Object v, Set<BigDecimal> out) {
        if (v instanceof BigDecimal b) {
            out.add(b);
            out.add(b.multiply(BigDecimal.valueOf(100)));
        } else if (v instanceof Number n) {
            BigDecimal b = new BigDecimal(n.toString());
            out.add(b);
            out.add(b.multiply(BigDecimal.valueOf(100)));
        }
    }

    /**
     * 退回确定性文本：把 findings 用分号连起来。
     *
     * <p>它读起来不如模型写的顺，但每个数字都能追溯到 evidence。
     */
    public static String deterministicNarrative(List<Finding> findings) {
        if (findings == null || findings.isEmpty()) {
            return null;
        }
        return String.join("；", findings.stream().map(Finding::text).toList()) + "。";
    }
}

package com.liusongboyy.chatdata.engine.report;

import java.util.regex.Pattern;

/**
 * 标题的数字校验。
 *
 * <h2>为什么标题里一个数字都不许有</h2>
 *
 * <p>报告正文里的数字全部来自 findings 与查询结果，能溯源。
 * 而**标题里的数字没法校验**——我们不知道它是从 facts 里抄的，
 * 还是模型自己算的。
 *
 * <p>「保费下滑 15%」这种标题一旦写错，读者第一眼就看到错的信息，
 * 而正文可能完全正确——这种报告比没有报告更糟。
 *
 * <h2>为什么是"一个都不许"而不是"必须能在 facts 里找到"</h2>
 *
 * <p>后者需要把 facts 的数字传给模型做匹配，而给了数字模型就会用。
 * 直接禁止是**结构上更简单、也更安全**的做法：
 *
 * <ul>
 *   <li>模型拿不到数字 → 想编也没有素材</li>
 *   <li>代码里一刀切校验 → 不依赖模型的理解</li>
 *   <li>标题本来就不该有数字 → 没有表达力的损失</li>
 * </ul>
 */
final class TitleGuard {

    /**
     * 数字模式。
     *
     * <p>覆盖：
     * <ul>
     *   <li>阿拉伯数字：{@code 15}、{@code 46.7}</li>
     *   <li>带百分号/货币：{@code 15%}、{@code ¥8.21}</li>
     *   <li>中文数字：{@code 三}、{@code 十五}——「下滑三成」同样是编的</li>
     * </ul>
     *
     * <p>中文数字只匹配单独出现的（{@code 一}、{@code 三} 等），
     * 不匹配作为词素的（{@code 统一}、{@code 第一}）——后者太容易误伤，
     * 而误伤会让好标题也被退回。
     */
    private static final Pattern NUMBER = Pattern.compile(
            // 阿拉伯数字（含小数、千分位）
            "[0-9]+(?:[,.][0-9]+)*"
            // 中文数字词：仅当它后面跟「成/倍/个点/分之」这类量词时才算数字
            + "|[一二三四五六七八九十百千万]+(?:成|倍|个点|分之[一二三四五六七八九十百千万]+)"
    );

    private TitleGuard() {
    }

    /** 标题里有没有数字。 */
    static boolean hasNumber(String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        return NUMBER.matcher(title).find();
    }
}

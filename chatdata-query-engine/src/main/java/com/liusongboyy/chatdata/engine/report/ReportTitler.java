package com.liusongboyy.chatdata.engine.report;

import com.liusongboyy.chatdata.engine.agent.Generated;
import com.liusongboyy.chatdata.engine.agent.TokenUsage;

import java.util.List;

/**
 * 给报告写标题与章节名。
 *
 * <h2>它只产文字标签，不产数字</h2>
 *
 * <p>这是整个报告功能里**唯一调模型**的地方，也是最危险的地方——
 * 一个错的数字（「下滑 15%」而实际 46.7%）会让整份报告不可信。
 *
 * <p>所以：
 * <ul>
 *   <li>模型只收到「意图 + 各节的第一步 label」，**不收到任何数字**</li>
 *   <li>生成结果过 {@link TitleGuard} 校验——出现数字就整条拒绝</li>
 *   <li>拒绝时降级用原始 label / intent，**不重试**——
 *       重试只会多烧一次调用，而模型仍会编</li>
 * </ul>
 *
 * <h2>为什么连数字都不给</h2>
 *
 * <p>给了数字，模型就有素材把它们写进标题。而标题里的数字**没法被校验**——
 * 我们不知道它是从 facts 里抄的，还是自己算的。不给，是最简单的保证。
 *
 * <h2>失败方向</h2>
 *
 * <p>任何失败（模型没配、超时、返回空、含数字）都退回降级标题。
 * 报告**必须能生成**，标题难看一点可以接受，生成不出来不行。
 */
public final class ReportTitler {

    /**
     * 系统提示。
     *
     * <p><b>明确禁止数字</b>——不是"尽量不要"，是"绝对不要"。
     * 模型对"不要"的理解不稳定，所以代码层还有一道 {@link TitleGuard}。
     */
    private static final String SYS = """
            你给一份数据分析报告起标题。规则：
            1. 标题不超过 20 个字
            2. **绝对不要出现任何数字、百分比、金额**——你不知道真实数据
            3. 用「名词短语」而不是问句。例：「保费下滑归因」而不是「保费为什么下滑」
            4. 只输出标题本身，不要引号、不要解释
            """;

    private ReportTitler() {
    }

    /**
     * 生成标题。
     *
     * @param source 标题来源：模型；可为 null 表示没配模型（直接降级）
     * @param intent 用户原始问题或分析意图
     * @param sectionLabels 各节的 label，帮模型判断报告讲了什么
     * @param userId 会话隔离
     * @param sessionId 会话隔离
     * @return 标题 + token 消耗。**永远返回非空标题**（降级兜底）
     */
    public static Generated<String> title(TitleSource source, String intent,
                                          List<String> sectionLabels,
                                          String userId, String sessionId) {
        String fallback = fallbackTitle(intent);
        if (source == null) {
            return new Generated<>(fallback, TokenUsage.UNKNOWN);
        }

        String prompt = buildPrompt(intent, sectionLabels);
        Generated<String> produced;
        try {
            produced = source.title(prompt, SYS, userId, sessionId);
        } catch (RuntimeException e) {
            // 模型不可用不该让报告生成失败——标题难看可以接受，生成不出来不行
            return new Generated<>(fallback, TokenUsage.UNKNOWN);
        }

        String raw = produced.value();
        String cleaned = clean(raw);
        if (cleaned == null) {
            return new Generated<>(fallback, produced.usage());
        }
        // **含数字就整条拒绝，不重试**——重试只会多烧一次，而模型仍会编
        if (TitleGuard.hasNumber(cleaned)) {
            return new Generated<>(fallback, produced.usage());
        }
        return new Generated<>(cleaned, produced.usage());
    }

    /**
     * 给一节起标题。
     *
     * <p>与报告标题同一个约束，但输入只有「这节的第一步 label + 意图」。
     * 失败时退回 label（它本来就是简短描述）。
     */
    public static Generated<String> sectionTitle(TitleSource source, String intent,
                                                 String label,
                                                 String userId, String sessionId) {
        String fallback = (label == null || label.isBlank()) ? fallbackTitle(intent) : label;
        if (source == null) {
            return new Generated<>(fallback, TokenUsage.UNKNOWN);
        }

        String prompt = """
                报告主题：%s
                这一节查的是：%s

                给这一节起一个标题（不超过 15 字），说明**这一节讲了什么**，
                不是"查了什么"。例如「各区域保费占比」而不是「查各区域保费」。
                """.formatted(nz(intent), nz(label));

        Generated<String> produced;
        try {
            produced = source.title(prompt, SYS, userId, sessionId);
        } catch (RuntimeException e) {
            return new Generated<>(fallback, TokenUsage.UNKNOWN);
        }

        String cleaned = clean(produced.value());
        if (cleaned == null || TitleGuard.hasNumber(cleaned)) {
            return new Generated<>(fallback, produced.usage());
        }
        return new Generated<>(cleaned, produced.usage());
    }

    /**
     * 清洗模型输出。
     *
     * <p>模型常见的小动作：加引号、加「标题：」前缀、换行。
     * 这些不影响正确性但影响观感，而且会让 {@link TitleGuard} 的判断变复杂。
     *
     * @return 清洗后的标题；空或无效返回 null
     */
    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        // 只取第一行：模型有时会输出标题 + 解释
        int nl = t.indexOf('\n');
        if (nl > 0) {
            t = t.substring(0, nl).trim();
        }
        // 去掉常见前缀
        for (String prefix : new String[]{"标题：", "标题:", "报告标题：", "报告标题:"}) {
            if (t.startsWith(prefix)) {
                t = t.substring(prefix.length()).trim();
            }
        }
        // 去掉包裹的引号
        if (t.length() >= 2) {
            char first = t.charAt(0);
            char last = t.charAt(t.length() - 1);
            if ((first == '"' && last == '"') || (first == '「' && last == '」')
                    || (first == '《' && last == '》')) {
                t = t.substring(1, t.length() - 1).trim();
            }
        }
        if (t.isBlank() || t.length() > 60) {
            // 超过 60 字说明模型没照规则写，退回降级更安全
            return null;
        }
        return t;
    }

    private static String buildPrompt(String intent, List<String> labels) {
        StringBuilder sb = new StringBuilder();
        sb.append("报告主题：").append(nz(intent)).append('\n');
        if (labels != null && !labels.isEmpty()) {
            sb.append("报告包含这些部分：\n");
            for (String l : labels) {
                if (l != null && !l.isBlank()) {
                    sb.append("- ").append(l).append('\n');
                }
            }
        }
        sb.append("\n给这份报告起一个标题。");
        return sb.toString();
    }

    /**
     * 降级标题。
     *
     * <p>用意图截断。与 {@code Report} 的紧凑构造器同一个规则——
     * 两处保持一致，否则"有模型"和"没模型"生成的报告标题风格会不同。
     */
    private static String fallbackTitle(String intent) {
        if (intent == null || intent.isBlank()) {
            return "分析报告";
        }
        String t = intent.trim();
        return t.length() <= 40 ? t : t.substring(0, 40) + "…";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 标题来源。
     *
     * <p>与 {@code NarrativeSource} 分开：那个产出的是「结论」，
     * 这个是「标签」。两者的 prompt 与约束完全不同，
     * 合成一个接口会让实现类里出现 {@code if (isTitle)} 这种分支。
     */
    @FunctionalInterface
    public interface TitleSource {
        /**
         * @param prompt 用户提示（含主题与各节）
         * @param sys    系统提示（含"不要数字"的硬约束）
         * @return 标题文本 + token 消耗
         */
        Generated<String> title(String prompt, String sys, String userId, String sessionId);
    }
}

package com.liusongboyy.chatdata.ask.health;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一次问数归入「问题类型」。
 *
 * <h2>为什么按 DSL 结构而不是问题文本</h2>
 *
 * <p>文本匹配看不出区别：「客户总数是多少」和「按状态统计数量」都含"数"，
 * 但前者的失败意味着指标没配、后者意味着维度解析出错。
 *
 * <p>而审计表里存了 DSL，它<b>就是问题的真实结构</b>：
 *
 * <pre>{@code {"metric":"保费合计","dimension":"大区","time":"全部","filter":""}}</pre>
 *
 * <p>所以有 DSL 时一律按结构分，没有时才退回关键词——那 1811 条没 DSL 的
 * 绝大多数是失败，恰恰最该被看见。
 *
 * <h2>规则从真实数据里提炼</h2>
 *
 * <p>判据与分布（最近 30 天 6698 行）：
 *
 * <table>
 *   <tr><th>类型</th><th>条数</th><th>失败</th></tr>
 *   <tr><td>分组统计</td><td>2620</td><td>13</td></tr>
 *   <tr><td>单值汇总</td><td>2267</td><td>27</td></tr>
 *   <tr><td>未分类</td><td>1203</td><td>200</td></tr>
 *   <tr><td>分组（文本兜底）</td><td>534</td><td>27</td></tr>
 * </table>
 *
 * <p><b>「未分类」里 200 条失败</b>，占全部失败的六成——那正是要看见的东西。
 * 这个桶持续变大说明有新问法模式没被规则覆盖。
 */
public final class QuestionTypeClassifier {

    private QuestionTypeClassifier() {
    }

    /** 问题类型。**键值稳定**，前端据此配色与排序。 */
    public enum Type {
        /** 有分组维度：「按大区统计」「各月走势」 */
        GROUPED("GROUPED", "分组统计"),
        /** 有筛选条件、没有分组：「华东的保费是多少」 */
        FILTERED("FILTERED", "带筛选单值"),
        /** 无筛选无分组：「客户总数是多少」 */
        SCALAR("SCALAR", "单值汇总"),
        /** 归因分析：「为什么下降」「什么原因」 */
        ATTRIBUTION("ATTRIBUTION", "归因分析"),
        /** 趋势：「走势」「趋势」 */
        TREND("TREND", "趋势"),
        /** 对比：「同比」「环比」 */
        COMPARE("COMPARE", "对比"),
        /** 认不出来的。**它是信号不是垃圾桶**——持续变大就该提炼新规则 */
        UNKNOWN("UNKNOWN", "未分类");

        private final String key;
        private final String label;

        Type(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 分类。
     *
     * @param dslJson  审计表里的 {@code dsl_json}，可能为 null 或空
     * @param question 原始问题，DSL 缺失时用它兜底
     * @return 类型。**永不返回 null**，认不出归 UNKNOWN
     */
    public static Type classify(String dslJson, String question) {
        Type byDsl = fromDsl(dslJson);
        if (byDsl != null) {
            return byDsl;
        }
        return fromText(question);
    }

    // ── DSL 结构 ──

    /** 简化 DSL 里的字段名。 */
    private static final Pattern DIMENSION_ONE =
            Pattern.compile("\"dimension\"\\s*:\\s*\"([^\"]*)\"");
    /** 完整 DSL 里的数组形式，取第一个元素。 */
    private static final Pattern DIMENSION_MANY =
            Pattern.compile("\"dimensions\"\\s*:\\s*\\[\\s*\"([^\"]*)\"");
    private static final Pattern FILTER_ONE =
            Pattern.compile("\"filter\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern FILTER_MANY =
            Pattern.compile("\"filters\"\\s*:\\s*\\[\\s*\\{");

    /**
     * 按 DSL 结构判定。
     *
     * <p><b>必须取字段值判断，不能用 LIKE。</b>实测踩过：
     * {@code dslJson LIKE '%"dimension":"%'} 会把空串 {@code "dimension":""}
     * 也算成"有维度"，于是 4878 条全被误归成「分组统计」。
     *
     * @return DSL 存在且能判定时返回类型；DSL 缺失或无法解析时返回 null 交给文本兜底
     */
    private static Type fromDsl(String dslJson) {
        if (dslJson == null || dslJson.isBlank()) {
            return null;
        }
        String json = dslJson.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) {
            // 半个 JSON（截断、写了一半）说明这条 DSL 本身有问题。
            // **不假装认出来**——归到 SCALAR 会让这类行混进正常的单值统计里，
            // 而它们恰恰是"DSL 生成到一半"的异常样本，交回文本兜底更诚实
            return null;
        }
        // 至少要能认出这里是个键值结构。一个什么字段都没有的 {} 不携带信息
        if (!json.contains(":")) {
            return null;
        }

        // ① 有分组维度 → 分组统计。**优先于筛选**：
        //    「华东各门店的保费」既有筛选又有分组，但它的问题是
        //    "能不能按门店分组"，与纯筛选的问法不同
        String dim = firstGroup(DIMENSION_ONE, json);
        if (dim == null) {
            dim = firstGroup(DIMENSION_MANY, json);
        }
        if (dim != null && !dim.isBlank()) {
            return Type.GROUPED;
        }

        // ② 有筛选条件 → 带筛选单值
        String filter = firstGroup(FILTER_ONE, json);
        boolean hasFilters = (filter != null && !filter.isBlank())
                || FILTER_MANY.matcher(json).find();
        if (hasFilters) {
            return Type.FILTERED;
        }

        // ③ 其余：DSL 存在但既没维度也没筛选 → 单值汇总
        return Type.SCALAR;
    }

    private static String firstGroup(Pattern p, String json) {
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    // ── 文本兜底 ──

    /**
     * 按问题文本粗分。
     *
     * <p>只用于没有 DSL 的行。**不追求精确**——它们多是失败，
     * 归到哪一类都比丢掉好；而丢掉就看不到"哪类问题连 DSL 都生成不出来"。
     */
    private static Type fromText(String question) {
        if (question == null || question.isBlank()) {
            return Type.UNKNOWN;
        }
        String q = question.toLowerCase(Locale.ROOT);

        if (q.contains("为什么") || q.contains("原因") || q.contains("归因")) {
            return Type.ATTRIBUTION;
        }
        if (q.contains("走势") || q.contains("趋势")) {
            return Type.TREND;
        }
        if (q.contains("同比") || q.contains("环比") || q.contains("对比")) {
            return Type.COMPARE;
        }
        if (q.contains("按") || q.contains("各") || q.contains("每")) {
            return Type.GROUPED;
        }
        return Type.UNKNOWN;
    }
}

package com.liusongboyy.chatdata.engine.nlsql;

import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import com.liusongboyy.chatdata.engine.semantic.Term;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * **语义完整性校验**：问题里说的约束，是不是都落进了 DSL。
 *
 * <h2>它挡的是什么</h2>
 *
 * <p>PRD §8 与 spike 反复记录的那一类失败：DSL 结构合法、解析成功、无异常，
 * 但问题里的关键约束**凭空消失**。实测两例：
 *
 * <ul>
 *   <li>「上个月<strong>华东</strong>的<strong>有效</strong>保单保费合计」→ {@code filters: null}</li>
 *   <li>「<strong>上个月</strong>华东的保费合计」→ {@code timeRange: null}（今天真撞到的一次）</li>
 * </ul>
 *
 * <p>两者都退化成"全量查询"，**答案错误且全程静默**——监控看不到、审计里 DSL 合法。
 * 这一层是这类失败**唯一**的自动化拦截点（另一个是界面上给用户看的「我理解成了」）。
 *
 * <h2>只做确定性可判的部分，不猜</h2>
 *
 * <table>
 *   <tr><th>检查</th><th>为什么可判</th></tr>
 *   <tr><td>时间口径</td><td>口径词表与解析器共用一份（{@link TimeRangeResolver#KIND_WORDS}），
 *       且能把"问题说的"和"DSL 里的"都解析成具体日期区间来比</td></tr>
 *   <tr><td>业务术语</td><td>术语及其近义词是管理员显式登记的，问题里出现即可判定</td></tr>
 * </table>
 *
 * <p><strong>刻意不查的两类：</strong>
 *
 * <ol>
 *   <li><b>字段别名</b>——同一个词可能被用户当取值说（「有效<em>状态</em>」里的「状态」），
 *       也可能由时间范围覆盖。按别名报会把正常查询误判成漏约束，而误报的代价是一次
 *       多余的模型调用，且可能把本来正确的 DSL 改坏。</li>
 *   <li><b>取值实体</b>（「华东」「有效」这类）——判定它们需要一份取值词表，
 *       而当前语义层没有登记样例值。**这一类仍只能靠界面回显让用户自己发现。**</li>
 * </ol>
 *
 * <p>换句话说：这一层能挡住"时间和术语被丢"，挡不住"取值被丢"。
 * 如实写在这里，免得将来有人以为它覆盖了全部。
 */
public final class ConstraintCheck {

    /** 在自由文本里找「近 N 天」。与 {@link TimeRangeResolver} 认的前缀词保持一致。 */
    private static final java.util.regex.Pattern RECENT_IN_TEXT =
            java.util.regex.Pattern.compile("(?:近|最近|过去)\\s*\\d+\\s*天");

    /** 在自由文本里找「近 N 个月」，数字支持中文与阿拉伯数字。 */
    private static final java.util.regex.Pattern RECENT_MONTHS_IN_TEXT =
            java.util.regex.Pattern.compile("(?:近|最近|过去)\\s*(?:\\d+|[一二两三四五六七八九十]+)\\s*个?月");

    private ConstraintCheck() {
    }

    /**
     * 找出问题里提到、但 DSL 没有体现的约束。
     *
     * @param question 用户问题原文
     * @param dsl      模型产出的 DSL
     * @param model    语义模型（取术语与近义词）
     * @param today    参考日期，与渲染用同一个值
     * @return 缺失约束的**人类可读描述**，可直接拼进纠正提示；空列表表示完整
     */
    public static List<String> findMissing(String question, AskDsl dsl, SemanticModel model,
                                           LocalDate today) {
        List<String> missing = new ArrayList<>();
        if (question == null || question.isBlank() || dsl == null) {
            return missing;
        }

        checkTime(question, dsl, today, missing);
        checkTerms(question, dsl, model, missing);

        return missing;
    }

    /**
     * 时间口径。
     *
     * <p>两件事都查：**有没有**（问题说了时间、DSL 里没有）与**对不对**
     * （都说了，但解析出来的区间不是同一个）。
     *
     * <p>"对不对"这条同样重要：模型把「上个月」写成「本月」时，结构上是完整的，
     * 只有比日期区间才发现得了。
     */
    private static void checkTime(String question, AskDsl dsl, LocalDate today,
                                  List<String> missing) {
        String mentioned = mentionedTimeExpression(question);
        if (mentioned == null) {
            // 问题里没提时间，就不能因为 DSL 没有时间条件而报——那会把
            // "本来就该查全时段"的正常问题误判成漏约束
            return;
        }

        if (dsl.timeRange() == null) {
            missing.add("问题里提到了时间「" + mentioned + "」，但 DSL 的 timeRange 是空的");
            return;
        }

        try {
            TimeRangeResolver.ResolvedRange expected =
                    TimeRangeResolver.resolve(mentioned, null, null, today);
            TimeRangeResolver.ResolvedRange actual = TimeRangeResolver.resolve(
                    dsl.timeRange().kind(), dsl.timeRange().start(), dsl.timeRange().end(), today);

            if (expected != null && actual != null && !expected.equals(actual)) {
                missing.add("问题里的时间是「%s」(%s ~ %s)，而 DSL 里是「%s」(%s ~ %s)"
                        .formatted(mentioned, expected.startInclusive(), expected.endExclusive(),
                                dsl.timeRange().kind(), actual.startInclusive(), actual.endExclusive()));
            }
        } catch (TimeRangeResolver.TimeRangeException e) {
            // DSL 里的时间本来就解析不了 —— 那是渲染阶段要报的错，这里不重复
        }
    }

    /**
     * 从问题里找出被提到的**已支持**时间口径。
     *
     * <p>取最长的匹配：问题同时出现「上个月」与「上月」时应当报「上个月」（用户的原话）。
     *
     * @return 匹配到的口径词；没有则 null
     */
    static String mentionedTimeExpression(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        String best = null;
        for (String word : TimeRangeResolver.KIND_WORDS) {
            if (q.contains(word.toLowerCase(Locale.ROOT))
                    && (best == null || word.length() > best.length())) {
                best = word;
            }
        }
        if (best != null) {
            return best;
        }

        // 「近 N 天」是模式而不是固定词。RECENT_DAYS 是整串锚定的（用于解析），
        // 这里要在自由文本里**找**，故用同一个前缀词但放宽边界
        Matcher loose = RECENT_IN_TEXT.matcher(question);
        if (loose.find()) {
            return loose.group().replaceAll("\\s+", "");
        }
        Matcher months = RECENT_MONTHS_IN_TEXT.matcher(question);
        if (months.find()) {
            return months.group().replaceAll("\\s+", "");
        }
        return null;
    }

    /**
     * 业务术语：问题里用了某个术语的说法，而 DSL 里**任何地方**都找不到它。
     *
     * <p>判据看的是整个 DSL 的字符串形态，不是只看 metrics——术语可能落进筛选条件、
     * 指标名或时间口径里。宁可宽一点：**误报会多花一次模型调用，漏报会给出一个错答案**，
     * 但两者都不是零成本，所以只查"完全没出现"这种最强信号。
     */
    private static void checkTerms(String question, AskDsl dsl, SemanticModel model,
                                   List<String> missing) {
        if (model == null) {
            return;
        }
        String dslText = dslText(dsl);
        Set<String> reported = new LinkedHashSet<>();

        for (Term term : model.domain().terms()) {
            for (String phrasing : term.allPhrasings()) {
                if (phrasing == null || phrasing.isBlank() || !question.contains(phrasing)) {
                    continue;
                }
                if (!dslText.contains(phrasing) && !reported.contains(term.name())) {
                    reported.add(term.name());
                    missing.add("问题里用了术语「%s」（含义：%s），但 DSL 里完全没有体现"
                            .formatted(phrasing, term.description() == null ? term.name() : term.description()));
                }
                break;   // 一个术语只报一次
            }
        }
    }

    /** DSL 的可比对形态：把所有字段摊平成一串。 */
    private static String dslText(AskDsl dsl) {
        StringBuilder sb = new StringBuilder();
        dsl.metrics().forEach(sb::append);
        dsl.dimensions().forEach(sb::append);
        for (AskDsl.Filter f : dsl.filters()) {
            sb.append(f.field()).append(f.value());
        }
        if (dsl.timeRange() != null) {
            sb.append(dsl.timeRange().kind())
                    .append(dsl.timeRange().start())
                    .append(dsl.timeRange().end());
        }
        return sb.toString();
    }
}

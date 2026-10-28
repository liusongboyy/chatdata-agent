package com.liusongboyy.chatdata.engine.raw;

import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.Term;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从范例库里挑出与本次提问最像的几条。
 *
 * <h2>为什么不用向量检索</h2>
 *
 * <p>范例库的规模是**几十到几百条**（人工维护，不可能上万）。这个量级下，
 * 一次全量比对的开销可以忽略，而向量检索会引入三个新问题：要多存一份 embedding、
 * 要一个向量库、以及**漏召回**——本来能匹配上的范例因为相似度阈值没被捞出来。
 *
 * <p>等范例上千条、或跨域检索时再换。
 *
 * <h2>相似度怎么算：字面 + 关键词，取两者较大</h2>
 *
 * <p><b>只有字面是不够的，这是实测出来的结论。</b>同一条「保费分档」范例：
 *
 * <pre>
 *   与「华东的大额保单和中额保单各有多少张」  字面 0.096   ← 该匹配
 *   与「各区域的保费合计是多少」              字面 0.057   ← 不该匹配
 * </pre>
 *
 * <p>该匹配的只高出一点点，<b>调阈值分不开</b>。原因是真正的判据是**业务概念**
 * （都涉及"分档"），而中文里表达同一个概念的说法字面上可以毫不相干。
 *
 * <p>所以管理员可以给范例打**关键词**：命中一个即视为强相关，直接给到阈值以上。
 * 人工维护的范例库只有几十条，标签比模糊匹配可靠，而且**可解释**——
 * 「为什么这条被选中」有明确答案，不是一堆小数。
 *
 * <p>字面相似度仍然保留：对同构问题（「各区域保费」→「按区域看保费合计」，
 * 实测 0.57）它区分度很好，而管理员不一定给每条都打标签。
 *
 * <p>字面那一路用**二元组 cosine** 而不是 Jaccard：范例的问题通常比用户问的长，
 * Jaccard 会被长句稀释，cosine 对此不敏感。
 *
 * <h2>宁缺勿滥</h2>
 *
 * <p>低于阈值的一条都不给。**给一条不相关的范例比不给更糟**：模型会照着它的形状写，
 * 而那个形状与本问题无关。所以这里不做"至少给一条"的兜底。
 */
public final class SqlExampleSelector {

    /**
     * 字面相似度下限。
     *
     * <p>0.07 是按实测选的：该匹配的 0.096、不该匹配的 0.057，取中间偏下。
     * 样本只有几组，所以**关键词命中是更可靠的信号**，别只依赖这个数。
     */
    static final double MIN_LITERAL = 0.07;
    /** 命中一个关键词即给到这个分，足以越过阈值。 */
    static final double KEYWORD_SCORE = 0.5;
    /** 最多给几条。范例越多 prompt 越长，收益递减得很快。 */
    public static final int DEFAULT_LIMIT = 3;
    /** 单条 SQL 进 prompt 前的截断长度——多层 CTE 可能有几千字。 */
    private static final int MAX_SQL_CHARS = 600;

    private SqlExampleSelector() {
    }

    public static List<SqlExample> select(List<SqlExample> examples, String question, Domain domain) {
        return select(examples, question, domain, DEFAULT_LIMIT);
    }

    /**
     * @param examples 候选（应已按 domain 过滤、只含 enabled）
     * @return 按相似度降序，最多 {@code limit} 条；可能为空
     */
    public static List<SqlExample> select(List<SqlExample> examples, String question, Domain domain,
                                          int limit) {
        if (examples == null || examples.isEmpty() || question == null || question.isBlank()) {
            return List.of();
        }
        Set<String> probe = bigrams(normalize(question, domain));

        record Scored(SqlExample example, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (SqlExample e : examples) {
            if (!e.enabled() || e.sql() == null || e.sql().isBlank()) {
                continue;
            }
            double score = Math.max(
                    cosine(probe, bigrams(normalize(e.question(), domain))),
                    keywordScore(e, question));
            if (score >= MIN_LITERAL) {
                scored.add(new Scored(e, score));
            }
        }

        return scored.stream()
                // 同分时按 id 升序：结果必须可复现，否则同一个问题两次给出不同 prompt，
                // 排查时无法对照
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(s -> s.example().id()))
                .limit(Math.max(0, limit))
                .map(Scored::example)
                .toList();
    }

    /** SQL 进 prompt 前的截断。 */
    public static String truncateSql(String sql) {
        if (sql == null) {
            return "";
        }
        String s = sql.trim();
        return s.length() <= MAX_SQL_CHARS ? s : s.substring(0, MAX_SQL_CHARS) + "\n…（已截断）";
    }

    /**
     * 术语归一：把问题里的各种说法追加成术语名。
     *
     * <p>只**追加**不替换——保留原话，这样即使归一有偏差，字面相似度仍在。
     */
    static String normalize(String question, Domain domain) {
        if (domain == null) {
            return question;
        }
        Set<String> canonical = new LinkedHashSet<>();
        for (Term t : domain.terms()) {
            for (String phrasing : t.allPhrasings()) {
                if (phrasing != null && !phrasing.isBlank() && question.contains(phrasing)) {
                    canonical.add(t.name());
                    break;
                }
            }
        }
        // 列别名同理：用户说「保费」，范例可能写 premium
        for (RegisteredTable t : domain.visibleTables()) {
            for (ColumnDef c : t.columns()) {
                String alias = c.displayName();
                if (alias != null && !alias.isBlank() && question.contains(alias)) {
                    canonical.add(alias);
                    canonical.add(c.col());
                }
            }
        }
        return canonical.isEmpty() ? question
                : question + " " + String.join(" ", canonical);
    }

    /** 二元组集合（跳过空白）。 */
    static Set<String> bigrams(String s) {
        String t = s.replaceAll("\\s+", "");
        Set<String> out = new LinkedHashSet<>();
        if (t.length() == 1) {
            out.add(t);
        }
        for (int i = 0; i + 1 < t.length(); i++) {
            out.add(t.substring(i, i + 2));
        }
        return out;
    }

    /**
     * 关键词命中分。
     *
     * <p>命中一个就给满 {@link #KEYWORD_SCORE}：这些词是管理员**特意写下来**
     * 用来指认"这条范例讲的是什么"的，命中一个已经足够说明相关。
     * 命中多个不额外加分——那只会让标签写得多的范例长期霸占名额。
     */
    static double keywordScore(SqlExample example, String question) {
        for (String kw : example.keywordList()) {
            if (question.contains(kw)) {
                return KEYWORD_SCORE;
            }
        }
        return 0;
    }

    /** 二元组余弦。对长度差异不敏感，胜过 Jaccard。 */
    static double cosine(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> inter = new LinkedHashSet<>(a);
        inter.retainAll(b);
        return inter.size() / Math.sqrt((double) a.size() * b.size());
    }
}

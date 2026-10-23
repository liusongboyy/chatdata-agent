package com.liusongboyy.chatdata.engine.raw;

import com.liusongboyy.chatdata.engine.agent.PromptAssembler;
import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;

import java.util.ArrayList;
import java.util.List;

/**
 * 直连模式的提示词。
 *
 * <h2>它比建模模式的提示词承担更多</h2>
 *
 * <p>建模模式里，模型只需要产出 DSL——字段存在性、聚合合法性、权限都由下游代码兜着。
 * 直连模式里模型直接写 SQL，**没有任何下游兜底**（只有表级白名单）。
 * 所以"业务口径"这块知识在这里的价值比在建模模式里更高，而不是更低。
 *
 * <h2>为什么要喂术语与别名</h2>
 *
 * <p>不给这些，模型只能靠猜：
 *
 * <ul>
 *   <li>用户说「保费」，而物理列叫 {@code premium}——靠采样值也许能猜对，也许不能</li>
 *   <li>用户说「有效保单」，而取值是 {@code status='有效'}——不知道就会写 {@code status=1} 或漏掉</li>
 *   <li>「上个月」到底按哪个日期列算——{@code effect_date} 还是 {@code sign_date}，
 *       猜错就得到一组看着正常但错的数</li>
 * </ul>
 *
 * <p>这些正是 PRD §12 说的"领域知识"，也是语义层之所以是准确率杠杆的原因。
 * 直连模式不要求做完整建模，但**把这几条显式写下来**是性价比最高的一步。
 *
 * <h2>与建模模式共用术语渲染</h2>
 *
 * <p>调 {@link PromptAssembler#businessTerms}，不另写一份——同一条术语在两条链路里
 * 必须说同样的话，否则排查时对不上。
 */
public final class RawPromptAssembler {

    private RawPromptAssembler() {
    }

    /**
     * @param domain    直连域（表即白名单；术语与列别名可选）
     * @param tables    白名单表名
     * @param schemaText {@link RawSchemaBuilder} 生成的表结构与采样值
     */
    public static String assemble(Domain domain, List<String> tables, String schemaText) {
        return assemble(domain, tables, schemaText, List.of());
    }

    /**
     * @param examples 与本次提问相似的范例（已由 {@code SqlExampleSelector} 挑过）
     */
    public static String assemble(Domain domain, List<String> tables, String schemaText,
                                  List<SqlExample> examples) {
        return """
                你是数据问答助手。用户用中文提问，你写 SQL 查数据，然后给出结论。

                可访问的表（**只能查这些**）：%s
                %s%s%s
                规则：
                1. 只能写一条 SELECT（可以用 WITH）。不许写 INSERT / UPDATE / DELETE / DDL。
                2. **表名直接写，不要加库名前缀**——加了会被拒绝。
                3. 需要先了解表结构时，用 describe_table 看字段；不确定取值怎么写时看下面的"样例取值"。
                4. 用户提到的每一个限定条件（时间、地区、状态等）都必须体现在 WHERE 里。
                   时间范围要换算成具体的日期条件，不要原样写「上个月」。
                5. 拿到结果后，用一两句中文说清关键数字，不要罗列整张表。
                6. **如果用户问的是"这里能分析哪些数据 / 有哪些指标 / 支持查什么"这类能力问题，
                   不要写 SQL。**直接根据上面的表结构，用中文说清有哪些可分析的对象与字段
                   （按业务含义归类，一两句即可）。为这类问题硬写一条 SQL 只会失败。

                表结构与样例取值：
                %s
                """.formatted(
                String.join("、", tables),
                businessTerms(domain),
                aliasMapping(domain),
                similarExamples(examples),
                schemaText);
    }

    /**
     * 相似问题的正确写法（few-shot）。
     *
     * <p><strong>必须点明"照结构、别照抄取值"</strong>：范例里往往带着具体的日期、
     * 具体的地区。模型很容易把 {@code sign_date >= '2026-09-01'} 原样搬过来——
     * 那是上一条范例的答案，不是本次的。
     */
    private static String similarExamples(List<SqlExample> examples) {
        if (examples == null || examples.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n相似问题的正确写法")
                .append("（**照它们的结构与口径，不要照抄 SQL 里的具体取值**——")
                .append("那些取值属于那条范例的问题）：\n");
        for (SqlExample e : examples) {
            sb.append("  问：").append(e.question()).append('\n');
            sb.append("  SQL：").append(SqlExampleSelector.truncateSql(e.sql())).append('\n');
            if (e.note() != null && !e.note().isBlank()) {
                sb.append("  要点：").append(e.note()).append('\n');
            }
        }
        return sb.toString();
    }

    /** 业务术语。没有登记就什么都不加（不留一个空标题）。 */
    private static String businessTerms(Domain domain) {
        return PromptAssembler.businessTerms(domain.terms());
    }

    /**
     * 业务名 ↔ 物理列的对照。
     *
     * <p>只有**登记过且别名与物理列不同**的才列：全都一样时列出来是纯噪音。
     * 直连域常常一张列都没登记（那就只靠库里的真实列名与采样值）。
     */
    private static String aliasMapping(Domain domain) {
        List<String> lines = new ArrayList<>();
        for (RegisteredTable t : domain.visibleTables()) {
            List<String> pairs = new ArrayList<>();
            for (ColumnDef c : t.columns()) {
                String alias = c.displayName();
                if (alias != null && !alias.isBlank() && !alias.equals(c.col())) {
                    pairs.add(alias + "=" + c.col());
                }
            }
            if (!pairs.isEmpty()) {
                lines.add("  " + t.referenceName() + ": " + String.join("、", pairs));
            }
        }
        if (lines.isEmpty()) {
            return "";
        }
        return "\n业务名与物理列的对照（用户可能用业务名说字段，请映射到物理列）：\n"
                + String.join("\n", lines) + "\n";
    }
}

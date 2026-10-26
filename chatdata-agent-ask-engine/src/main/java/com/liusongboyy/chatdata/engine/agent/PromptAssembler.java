package com.liusongboyy.chatdata.engine.agent;

import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import com.liusongboyy.chatdata.engine.semantic.Term;
import com.liusongboyy.chatdata.engine.semantic.Topic;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 把语义模型装配成给 LLM 看的系统提示。
 *
 * <h2>只放该放的</h2>
 *
 * <p>提示里出现什么，模型就可能去用什么。所以：
 * <ul>
 *   <li>只列 {@code enabled=1} 的表与列——关掉的列不进 prompt，也就问不出来</li>
 *   <li>**不出现物理表名与物理列名**。模型只该看到业务名（「保费」「区域」），
 *       物理名的映射由我们自己做——否则模型可能绕过语义层直接引用列，
 *       而权限规则是按业务字段配的</li>
 *   <li>不出现关联关系与 SQL。多表 join 由渲染器规划，不需要模型操心</li>
 * </ul>
 *
 * <p>这条「只给业务名」是有意的：它让模型没有机会拼出一个绕过语义层的引用。
 */
public final class PromptAssembler {

    private PromptAssembler() {
    }

    /** 装配系统提示。 */
    public static String assemble(SemanticModel model) {
        StringBuilder sb = new StringBuilder();

        sb.append("你是「").append(model.domain().displayName()).append("」的数据分析助手。\n");
        sb.append("你的任务是把用户的中文问题翻译成**结构化的查询意图**，不要生成 SQL。\n\n");

        sb.append("可用的指标与维度：\n");
        for (RegisteredTable t : model.visibleTables()) {
            appendTable(sb, t);
        }

        appendTerms(sb, model);
        appendTopics(sb, model);

        sb.append("""

                规则：
                1. 用户没提到的项不要臆造。没要求分组就不填维度；没提时间就不填时间范围；没提筛选就不填筛选条件。
                2. 只能使用上面列出的名字。不要编造字段。
                3. 指标与维度要分清：带聚合说明的只能当指标，其余只能当维度。
                4. 时间范围一律写进 timeRange 字段，kind 用中文口径（如「上个月」「本月」「近10天」「今年」），
                   **不要写进 filters**。filters 是逐行筛选条件；给日期列配值时必须写具体日期 yyyy-MM-dd。
                5. 筛选条件要写清楚字段、运算符与取值。问题里出现的每一个限定词都必须体现出来，不能省略。
                6. 用户可能用**业务术语的近义词**来指代某个字段。遇到时请翻译成术语名对应的登记字段，
                   不要照抄用户的说法。
                7. 用户说「按年份」「按年」「每年」时，granularity 填 year；
                   说「按月份」「按月」「每月」时 granularity 填 month；
                   没说就不填（或填 day），**不要自己猜一个粒度**。
                   这一点必须做对：粒度填空会把日期列按**天**分组，用户要的是"每年的保费"，
                   拿到的却是几百行逐日数据——数字都对，但不是他要的东西。
                8. 用户说「最高/最多/最少/前 N 名/排名/Top」时用 sort 排序，
                   field 写指标名或维度名，**direction 必须写 asc 或 desc**（漏写按 desc 处理）。
                   说「前 N」时另填 limit=N。没有排名要求时不要填 sort，也不要填 limit。
                9. 用户说「占比/比重/构成/各占多少」时，把要算占比的指标名填进 shareOf，
                   **不要新造一个"占比"指标**。占比是相对本次结果总和而言的。
                10. 用户要求对比时填 compare，并且必须同时给出 timeRange：
                    - 说「同比 / 比去年同期」→ mode 填 yoy；说「环比 / 比上月」→ mode 填 mom。
                      做同比时 granularity 必须是 month 或 year。
                    - 点名了要对比的**具体时间段**（如「比 2023 年」「对比 2019 年全年」）→
                      mode 填 custom，并给出该时间段的 start 与 end（yyyy-MM-dd）。
                    没说就不填——系统只在有明确对比意图时才算，猜一个对比口径会给出错误的增长率。
                """);

        return sb.toString();
    }

    /**
     * 列出可用的指标与维度。
     *
     * <p><strong>名字必须是"干净"的——不能带任何附加说明。</strong>
     * 最初写成 {@code 保费（SUM）}，模型直接把整串当成了字段名，
     * 产出 {@code "metrics": ["保费（SUM）"]}，于是解析时找不到这个字段。
     *
     * <p>这不是模型的问题：它在模仿 prompt 的写法。要表达"哪些是指标"，
     * 就得把说明**放到名字之外**——这里分成两组来列。
     */
    private static void appendTable(StringBuilder sb, RegisteredTable table) {
        sb.append("\n【").append(table.displayName()).append("】\n");

        String metrics = table.columns().stream()
                .filter(ColumnDef::enabled)
                .filter(ColumnDef::isMetric)
                .map(ColumnDef::displayName)
                .collect(Collectors.joining("、"));
        String dimensions = table.columns().stream()
                .filter(ColumnDef::enabled)
                .filter(c -> !c.isMetric())
                .map(ColumnDef::displayName)
                .collect(Collectors.joining("、"));

        if (!metrics.isEmpty()) {
            sb.append("  只能作为指标的字段：").append(metrics).append('\n');
        }
        if (!dimensions.isEmpty()) {
            sb.append("  只能作为维度的字段：").append(dimensions).append('\n');
        }
    }

    /**
     * 术语表。
     *
     * <p><strong>这一段是解决实测问题的地方。</strong>问「保费<strong>合计</strong>是多少」时，
     * 模型产出 {@code metrics: ["保费合计"]}——它跟着用户的措辞走，而登记的别名是「保费」。
     * 当时的补救是渲染失败后带纠正提示重试一次；术语让模型**一开始**就知道
     * 「保费合计」指的是「保费」，不必等失败。
     *
     * <p>只列已发布主题之外的**全部术语**——术语是"同一个东西的不同说法"，
     * 不像主题那样按场景裁剪。
     */
    private static void appendTerms(StringBuilder sb, SemanticModel model) {
        sb.append(businessTerms(model.domain().terms()));
    }

    /**
     * 业务术语那一段。**两种模式共用同一份渲染**（{@link #appendTerms} 与直连模式都调它）。
     *
     * <p>共用是为了不让同一条术语在两条链路里说出两种话——那样排查时对不上，
     * 而"对不上"正是这类系统最难查的问题。
     *
     * @return 没有术语时返回空串（调用方直接 append，不会多出一个空标题）
     */
    public static String businessTerms(List<Term> terms) {
        if (terms == null || terms.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n业务术语（用户可能这样说，请翻译成对应的登记字段）：\n");
        for (Term t : terms) {
            sb.append("  - ").append(t.name());
            List<String> others = t.allPhrasings().stream()
                    .filter(p -> !p.equals(t.name()))
                    .toList();
            if (!others.isEmpty()) {
                sb.append("（用户也可能说：").append(String.join("、", others)).append("）");
            }
            if (t.description() != null && !t.description().isBlank()) {
                sb.append(" —— ").append(t.description());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 查询主题。
     *
     * <p>只列**已发布**的：草稿是还在编的东西，进 prompt 只会干扰。
     *
     * <p>它的作用是收敛范围——域内表多时，告诉模型"这个问题通常只看这几个字段"，
     * prompt 更短、选错表的概率更低。**不是限制**：模型仍可用主题之外的字段。
     */
    private static void appendTopics(StringBuilder sb, SemanticModel model) {
        var topics = model.domain().publishedTopics();
        if (topics.isEmpty()) {
            return;
        }
        sb.append("\n常见查询主题（仅供参考，不限于这些字段）：\n");
        for (Topic t : topics) {
            sb.append("  - ").append(t.name());
            if (t.description() != null && !t.description().isBlank()) {
                sb.append("：").append(t.description());
            }
            if (!t.fields().isEmpty()) {
                sb.append("（常用字段：").append(String.join("、", t.fields())).append("）");
            }
            sb.append('\n');
        }
    }
}

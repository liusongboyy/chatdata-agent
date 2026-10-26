package com.liusongboyy.chatdata.engine.eval;

import java.util.List;

/**
 * 一条评测用例：一个中文问题 + 一条**已确认正确**的 gold SQL。
 *
 * <h2>为什么 gold SQL 是必需品，而不是"参考答案"</h2>
 *
 * <p>问数的正确性只能由**结果集**判定（EX，execution accuracy），不能由文本相似度判定：
 * 同一份正确结果可以被写成无数种 SQL（别名、列序、CTE、子查询……），
 * 而一个看着很像的 SQL 也可能多一个条件、少一个 GROUP BY。
 * 这条判断在 spec §12 与 BIRD 的评测实践里都成立。
 *
 * <h2>为什么分难度</h2>
 *
 * <p>总分会被简单题淹没。spec §12.3 要求按「单表聚合 / 多表 join + 时间窗 / 需要业务口径」
 * 分档报告——短板通常出现在最后一档，而它恰好是真实业务里最常问的那类。
 *
 * @param id        用例标识，在同一评测集内唯一
 * @param difficulty 难度档
 * @param question  用户会怎么问（自然中文，不要写成 SQL 的口语版）
 * @param goldSql   正确 SQL。**必须是只读 SELECT/WITH**
 * @param ordered   结果行序是否参与比对。带 LIMIT 且顺序即语义时为 true
 * @param columnOrder 列序是否参与比对。默认 {@link ColumnOrder#ANY}——
 *                    同一份答案写成 {@code (status, cnt)} 还是 {@code (cnt, status)}
 *                    与正确性无关，按列序判会制造假负例
 * @param tags      自由标签，供报告分组（如 "join" / "time" / "business"）
 * @param note      给人看的备注：口径说明、已知歧义。不参与判定
 * @param analyze   是否以**分析编排**的方式提问。见 {@link AnalysisCase}
 * @param analysis  分析模式的判定标准；{@code analyze=true} 时必填
 */
public record EvalCase(
        String id,
        Difficulty difficulty,
        String question,
        String goldSql,
        boolean ordered,
        ColumnOrder columnOrder,
        List<String> tags,
        String note,
        Boolean analyze,
        AnalysisCase analysis
) {

    /**
     * 分析编排用例的判定标准。
     *
     * <h2>为什么普通用例的 EX 口径不够用</h2>
     *
     * <p>分析模式返回的是「若干步骤的结果 + 一组确定性事实 + 一句结论」，
     * 而不是一张表。用结果集比对比不出来：表对不对只说明第一步查得对，
     * 却完全不检查"结论里的数字是不是算出来的那些"——而后者恰恰是编排最容易出错的地方。
     *
     * <p>故这里分三项各自判定，**任一项不过就算这条用例失败**：
     * <ul>
     *   <li>{@code expectedFindings}：期望出现的事实（按关键字匹配），用来验证
     *       "该发现的确实发现了"</li>
     *   <li>{@code forbiddenNarrativeNumbers}：结论里**不应该出现**的数字。
     *       用来抓"模型编了一个数"</li>
     *   <li>{@code expectNarrative}：结论是否必须存在</li>
     * </ul>
     *
     * @param expectedFindings        每条形如 {@code "CONTRIBUTION:险种"}，
     *                                格式是「事实类型:必须在文本里出现的子串」
     * @param maxStepFailures         允许失败的步骤数。默认 0——分析的价值在于完整，
     *                                缺一步的结论比单查询更容易误导
     * @param expectNarrative         是否要求有结论文本
     * @param forbiddenNarrativeNumbers 结论里禁止出现的数字（如编造的百分比）
     */
    public record AnalysisCase(
            List<String> expectedFindings,
            int maxStepFailures,
            boolean expectNarrative,
            List<String> forbiddenNarrativeNumbers
    ) {

        public AnalysisCase {
            expectedFindings = expectedFindings == null ? List.of() : List.copyOf(expectedFindings);
            forbiddenNarrativeNumbers = forbiddenNarrativeNumbers == null
                    ? List.of() : List.copyOf(forbiddenNarrativeNumbers);
            if (maxStepFailures < 0) {
                throw new IllegalArgumentException("maxStepFailures 不能为负");
            }
        }

        /** 只要求有结论、步骤全部成功。最常用的一种。 */
        public static AnalysisCase of(String... expectedFindings) {
            return new AnalysisCase(List.of(expectedFindings), 0, true, List.of());
        }
    }

    public enum Difficulty {
        /** 单表聚合。 */
        SIMPLE,
        /** 多表 join、时间窗、分组。 */
        MEDIUM,
        /** 需要业务口径（如"平均解决时长"按哪个时间差算）。 */
        HARD
    }

    public enum ColumnOrder {
        /** 列序必须一致（位置比对）。 */
        STRICT,
        /** 列序无关：允许列重排后再比。默认。 */
        ANY
    }

    public EvalCase {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("评测用例缺少 id");
        }
        id = id.trim();
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("用例 " + id + " 缺少 question");
        }
        if (goldSql == null || goldSql.isBlank()) {
            throw new IllegalArgumentException("用例 " + id + " 缺少 goldSql");
        }
        question = question.trim();
        goldSql = goldSql.trim();
        difficulty = difficulty == null ? Difficulty.MEDIUM : difficulty;
        columnOrder = columnOrder == null ? ColumnOrder.ANY : columnOrder;
        tags = tags == null ? List.of() : List.copyOf(tags);
        analyze = analyze != null && analyze;
        if (analyze && analysis == null) {
            // 声明了分析模式却没给判定标准，会让这条用例永远"通过"——那是最糟的假绿
            throw new IllegalArgumentException(
                    "用例 " + id + " 声明了 analyze，但缺少 analysis 判定标准");
        }
        if (!analyze) {
            analysis = null;
        }
    }

    /**
     * 兼容构造器：普通（单查询）用例。
     *
     * <p>绝大多数用例走这条；保留它是为了让既有评测集文件与调用点不必改。
     */
    public EvalCase(String id, Difficulty difficulty, String question, String goldSql,
                    boolean ordered, ColumnOrder columnOrder, List<String> tags, String note) {
        this(id, difficulty, question, goldSql, ordered, columnOrder, tags, note, false, null);
    }
}

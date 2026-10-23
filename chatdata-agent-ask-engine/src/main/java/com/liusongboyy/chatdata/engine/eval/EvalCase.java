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
 */
public record EvalCase(
        String id,
        Difficulty difficulty,
        String question,
        String goldSql,
        boolean ordered,
        ColumnOrder columnOrder,
        List<String> tags,
        String note
) {

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
    }
}

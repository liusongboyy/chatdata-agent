package com.liusongboyy.chatdata.engine.analysis;

import java.util.List;

/**
 * 一次分析的计划：把一个问题拆成若干次取数。
 *
 * <h2>为什么要有硬上限</h2>
 *
 * <p>模型很容易把一个宽泛的问题拆成十几步（"从各个角度看看"），
 * 每一步都是一次真实查询与一次模型调用。上限不是保守，是成本与延迟的闸门。
 * 超限的计划**整条拒绝**并回退单查询——半份计划得出的结论比单查询更可疑。
 *
 * @param intent 用户想解决的问题，原话改写；用于结论区的开场
 * @param steps  步骤，1..{@value #MAX_STEPS}
 */
public record AnalysisPlan(String intent, List<PlanStep> steps) {

    /** 单次分析的步骤上限。 */
    public static final int MAX_STEPS = 4;

    public AnalysisPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("分析计划至少要有一个步骤");
        }
        if (steps.size() > MAX_STEPS) {
            throw new IllegalArgumentException(
                    "分析计划最多 " + MAX_STEPS + " 步，实际 " + steps.size()
                            + " 步。请只保留回答这个问题必需的步骤");
        }
        intent = intent == null ? "" : intent.trim();
    }
}

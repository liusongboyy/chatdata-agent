package com.liusongboyy.chatdata.engine.analysis;

import com.liusongboyy.chatdata.engine.nlsql.AskDsl;

import java.util.List;

/**
 * 编排用到的两段提示词：拆解与叙述。
 *
 * <p>两者都**不重复域描述**——域描述由 {@code PromptAssembler} 装配，
 * 这里只追加"这一次任务额外要遵守的规则"。
 */
public final class AnalysisPrompts {

    private AnalysisPrompts() {
    }

    /**
     * 拆解规则。追加在域描述之后。
     *
     * <p>要点：每步只回答一个小问题、不要重复取数、最多 4 步、
     * **不要在某一步里塞多个问题**——那是把"拆解"退化成"一条大查询"。
     */
    public static String planRules() {
        return """

                ── 额外任务：把这个问题拆成若干次取数 ──
                现在不是直接回答，而是先给出一个**分析计划**，由系统逐步执行后再汇总。

                规则：
                1. 每一步只回答一个小问题。例如「为什么下降」可拆成：
                   s1 两期总额对比（带 compare）、s2 按某个维度拆解、s3 按另一个维度拆解。
                2. 步骤之间**不要重复取数**。同一个查询出现两次会被直接拒绝。
                3. 最多 4 步。只想清楚必要的两三步，不要"从各个角度都看看"。
                4. 每一步都必须有明确的 purpose（这一步想验证什么），它是后面写结论的依据。
                5. 不要在某一步里塞多个问题——那不是拆解，是把一条大查询塞进一个步骤。
                6. 指定了维度就要遵循上面的建模约定；没有把握的维度不要猜。
                """;
    }

    /**
     * 叙述提示词。
     *
     * <p>把**已经算好的事实**与每步的摘要给模型，要求它只做措辞。
     * 数字白名单在 {@link NarrativeGuard} 里强制，不依赖模型自觉。
     */
    public static String narrative(String intent, AnalysisResult result) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("你是数据分析助手。下面这些问题已经由系统查过并算好了数字，\n")
                .append("请用 1–3 句中文写出结论。\n\n");

        if (intent != null && !intent.isBlank()) {
            sb.append("用户想解决的问题：").append(intent).append("\n\n");
        }

        sb.append("已算出的事实（**只能使用这些数字**）：\n");
        if (result.findings().isEmpty()) {
            sb.append("（没有可用的计算结果）\n");
        } else {
            for (Finding f : result.findings()) {
                sb.append("- [").append(f.stepId()).append("] ").append(f.text()).append('\n');
            }
        }

        sb.append("\n各步骤：\n");
        for (StepResult step : result.steps()) {
            sb.append("- ").append(step.id()).append("「").append(step.label()).append("」");
            if (!step.isOk()) {
                sb.append("：**失败**，原因：").append(step.error());
            } else {
                sb.append("：").append(step.rows().rowCount()).append(" 行");
            }
            sb.append('\n');
        }

        sb.append("""

                规则：
                1. 只允许引用上面「已算出的事实」里出现过的数字。不要自己算、不要编。
                2. 有步骤失败时必须明确说出来（哪一步没查出来），不要假装数据完整。
                3. 不要罗列全部步骤，只讲对用户问题最有用的 2–3 点。
                4. 不要写 SQL，不要写"根据查询结果"这类套话。
                """);
        return sb.toString();
    }

    /** 给测试与排查用：把 DSL 压成一行摘要。 */
    static String dslDigest(AskDsl dsl) {
        return "metrics=" + dsl.metrics()
                + ", dims=" + dsl.dimensions()
                + (dsl.timeRange() == null ? "" : ", time=" + dsl.timeRange().kind())
                + (dsl.compare() == null ? "" : ", compare=" + dsl.compare().label());
    }

    /** 供调用方拼接：域描述 + 拆解规则。 */
    public static String withPlanRules(String domainPrompt) {
        return domainPrompt + planRules();
    }

    /** 无事实时的兜底说明。 */
    public static String noFindingsNote(List<StepResult> steps) {
        boolean anyFailed = steps.stream().anyMatch(s -> !s.isOk());
        return anyFailed
                ? "部分步骤没有查出来，暂时无法给出结论。"
                : "已查到数据，但没有识别出可归纳的变化或构成。";
    }
}

package com.liusongboyy.chatdata.engine.report;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 把多轮问答拼成一份报告。
 *
 * <h2>它是纯函数</h2>
 *
 * <p>输入（几轮问答）→ 输出（报告）。不查库、不调模型、不读时钟（除了生成时间）。
 * 这条性质很重要：合并逻辑是整个功能里**最容易错**的地方，
 * 而纯函数能把它测透——口径分组、章节切分、冲突检测，全都能用断言固定住。
 *
 * <h2>不做的事</h2>
 *
 * <ul>
 *   <li><b>不生成标题</b>——那是模型的事，失败时降级用 intent。
 *       这里只给一个合理的默认值</li>
 *   <li><b>不写结论</b>——结论来自各轮的 narrative，由上层汇总</li>
 *   <li><b>不重新查询</b>——报告是快照，数据全部来自 {@link ReportSource}</li>
 * </ul>
 */
public final class ReportBuilder {

    private ReportBuilder() {
    }

    /**
     * 拼装报告。
     *
     * @param conversationId 来源会话
     * @param username       生成人
     * @param sources        用户勾选的轮次，**按界面顺序**
     * @return 报告。标题是降级的（用第一轮的 intent），上层可再调模型改写
     * @throws IllegalArgumentException 没有任何可用的轮次
     */
    public static Report build(String conversationId, String username,
                               List<ReportSource> sources) {
        if (sources == null || sources.isEmpty()) {
            // 空报告没有意义，且会让前端渲染出一个"什么都没有"的页面。
            // 明确报错，让调用方在生成前就拦住
            throw new IllegalArgumentException("至少要选一条问答才能生成报告");
        }

        List<ReportSection> sections = new ArrayList<>();
        List<ReportSection> dataSections = new ArrayList<>();
        List<ReportScope> allScopes = new ArrayList<>();

        // 逐轮切章节：有分析的进「分析」，没有的进「数据」
        for (ReportSource src : sources) {
            if (src.hasAnalysis()) {
                ReportSource.AnalysisPayload a = src.analysis();
                List<StepSnapshot> steps = a.steps().stream()
                        .map(StepSnapshot::capped)
                        .toList();
                steps.forEach(s -> allScopes.add(s.scope()));

                sections.add(new ReportSection(
                        ReportSection.Kind.ANALYSIS,
                        // 降级标题：用 intent（"保费自 3 月起逐月下降，需要定位下降的原因"）。
                        // 模型生成标题时会被替换掉
                        a.intent(),
                        // purpose 取第一步的——它是"为什么开始查这个"，
                        // 比 intent 更具体
                        steps.isEmpty() ? null : steps.get(0).purpose(),
                        steps,
                        a.findings()));
            } else {
                StepSnapshot step = new StepSnapshot(
                        src.question(),
                        null,
                        src.columns(),
                        src.rows(),
                        src.chart(),
                        src.sqlDisplay(),
                        src.scope(),
                        src.rows().size()).capped();
                allScopes.add(src.scope());

                dataSections.add(new ReportSection(
                        ReportSection.Kind.DATA,
                        src.question(),
                        null,
                        List.of(step),
                        List.of()));
            }
        }

        // 口径分组与冲突检测
        List<ReportBody.ScopeGroup> groups = groupScopes(allScopes);
        boolean conflict = groups.size() > 1;

        // 汇总结论：优先用各轮 narrative，按顺序拼
        String conclusion = mergeNarratives(sources);

        ReportBody body = new ReportBody(conclusion, sections, dataSections, conflict, groups);

        String intent = firstIntent(sources);

        return new Report(
                UUID.randomUUID().toString().replace("-", ""),
                conversationId,
                username,
                // 降级标题：传 null 让 Report 的紧凑构造器从 intent 生成。
                // 上层拿到报告后再调模型改写
                null,
                intent,
                body,
                sources.stream().map(ReportSource::messageId).toList(),
                null,       // 还没调模型
                LocalDateTime.now());
    }

    /**
     * 口径分组。
     *
     * <p>把口径**相同**的步归到一组。相同与否由
     * {@link ReportScope#comparableWith} 判——只比时间与筛选。
     *
     * <p>用 {@code List} 而不是 {@code Map}：{@link ReportScope} 是 record，
     * 但它的 {@code filters} 是 List，作为 map key 时顺序不同会被当成不同 key。
     * 顺序无关的比较已经写在 {@code comparableWith} 里了。
     */
    private static List<ReportBody.ScopeGroup> groupScopes(List<ReportScope> scopes) {
        List<ReportBody.ScopeGroup> groups = new ArrayList<>();
        for (ReportScope scope : scopes) {
            int hit = -1;
            for (int i = 0; i < groups.size(); i++) {
                if (groups.get(i).scope().comparableWith(scope)) {
                    hit = i;
                    break;
                }
            }
            if (hit >= 0) {
                ReportBody.ScopeGroup g = groups.get(hit);
                List<String> labels = new ArrayList<>(g.labels());
                labels.add(scope.describe());
                groups.set(hit, new ReportBody.ScopeGroup(g.scope(), g.stepCount() + 1, labels));
            } else {
                groups.add(new ReportBody.ScopeGroup(scope, 1, List.of(scope.describe())));
            }
        }
        return groups;
    }

    /**
     * 汇总结论。
     *
     * <p>把各轮已经写好的 narrative 按顺序拼起来。**不重新让模型写**——
     * 那会引入新数字，而每条 narrative 都已经过了数字白名单校验。
     *
     * <p>没有 narrative 的轮次（单查询）直接跳过：它没有结论可汇总。
     */
    private static String mergeNarratives(List<ReportSource> sources) {
        List<String> parts = new ArrayList<>();
        for (ReportSource src : sources) {
            if (src.hasAnalysis() && src.analysis().narrative() != null) {
                parts.add(src.analysis().narrative());
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        return String.join("\n\n", parts);
    }

    /** 第一轮的意图。用作报告标题的降级值。 */
    private static String firstIntent(List<ReportSource> sources) {
        for (ReportSource src : sources) {
            if (src.hasAnalysis() && !src.analysis().intent().isBlank()) {
                return src.analysis().intent();
            }
        }
        // 全是单查询：用第一个问题
        return sources.get(0).question();
    }
}

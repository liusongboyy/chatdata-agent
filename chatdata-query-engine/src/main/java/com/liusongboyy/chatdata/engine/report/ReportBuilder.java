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
        //
        // **先去重。** 真实会话里用户会重复问同一个问题（刷新、重试、
        // 忘了问过）。实测一个 6 轮会话里有 4 轮是同一句「按客户等级看保费」，
        // 不去重的话报告里会有 4 张一模一样的表——那不是分析，是噪音。
        List<ReportSource> kept = dedup(sources);
        for (ReportSource src : kept) {
            if (src.hasAnalysis()) {
                ReportSource.AnalysisPayload a = src.analysis();
                List<StepSnapshot> steps = a.steps().stream()
                        .map(StepSnapshot::capped)
                        .toList();
                steps.forEach(s -> allScopes.add(s.scope()));

                sections.add(new ReportSection(
                        ReportSection.Kind.ANALYSIS,
                        // 降级标题：用**第一步的 label**，不是 intent。
                        //
                        // 实测：intent 常常是一整句分析目标——
                        // 「看除「西南」外其他区域里各客户等级的保费构成，
                        // 找出还有哪些区域偏离整体水平」——当章节名太长，
                        // 而且它说的是"要做什么"，不是"这节讲什么"。
                        //
                        // label 是查询的简短描述（「各区域中客户等级的保费占比」），
                        // 更接近章节名。模型生成标题时会被替换掉
                        sectionTitle(a),
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
        String conclusion = mergeNarratives(kept);

        // 跨节去重 findings。
        //
        // 实测：一个会话里两轮分析都在讲「普通客户占 33.9%」——那是同一个事实，
        // 因为两轮的基准数据相同。在报告里重复两遍，读者会以为是两件不同的事。
        //
        // **保留第一次出现的那条**：它属于先做的那一步分析。
        dedupFindings(sections);

        ReportBody body = new ReportBody(conclusion, sections, dataSections, conflict, groups);

        String intent = firstIntent(kept);

        return new Report(
                UUID.randomUUID().toString().replace("-", ""),
                conversationId,
                username,
                // 降级标题：传 null 让 Report 的紧凑构造器从 intent 生成。
                // 上层拿到报告后再调模型改写
                null,
                intent,
                body,
                // **记去重后的**：报告里只包含这些轮次，
                // 记全部会让"来源"与"内容"对不上
                kept.stream().map(ReportSource::messageId).toList(),
                null,       // 还没调模型
                LocalDateTime.now());
    }

    /**
     * 去掉内容重复的轮次。
     *
     * <h2>什么算重复</h2>
     *
     * <p>**问题文本相同 且 结果列相同**。只比问题不比结果是不够的：
     * 同一句话在不同时间问，数据可能不同（那是两次有价值的快照）。
     * 但两次结果连列都一样、行数也一样时，几乎必然是重复提问。
     *
     * <p>保守起见只比「问题 + 列 + 行数」——不比行内容。
     * 比内容需要逐行比较，而大结果集那样做的代价不值得；
     * 而"同一问题、同样的列、同样的行数"在真实会话里已经足够判重。
     *
     * <h2>保留第一个</h2>
     *
     * <p>保留最早的那次。报告是"分析过程"的记录，先问的那次更接近原始意图。
     */
    private static List<ReportSource> dedup(List<ReportSource> sources) {
        List<ReportSource> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ReportSource src : sources) {
            String key = dedupKey(src);
            if (seen.add(key)) {
                out.add(src);
            }
        }
        return out;
    }

    /**
     * 跨节去重 findings。
     *
     * <p>按 {@code text} 判重——那是给人看的那句话，两个事实文本相同就是同一个事实。
     * 不比 {@code evidence}：同一个事实在不同轮次里可能带不同的证据字段
     * （比如一轮有 changePct、另一轮只有 value），但读者看到的句子是一样的。
     *
     * <p>保留第一次出现的——它属于先做的那一步分析。
     */
    private static void dedupFindings(List<ReportSection> sections) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < sections.size(); i++) {
            ReportSection sec = sections.get(i);
            List<FindingView> kept = new ArrayList<>();
            for (FindingView f : sec.findings()) {
                String key = f.text() == null ? "" : f.text().trim();
                if (seen.add(key)) {
                    kept.add(f);
                }
            }
            if (kept.size() != sec.findings().size()) {
                sections.set(i, new ReportSection(sec.kind(), sec.title(), sec.purpose(),
                        sec.steps(), kept));
            }
        }
    }

    /**
     * 分析节的降级标题。
     *
     * <p>优先用第一步的 {@code label}——它是查询的简短描述。
     * 没有 label 才退回 {@code intent}。
     *
     * <p>为什么不是 intent：实测 intent 常常是一整句分析目标
     * （「看除西南外其他区域里各客户等级的保费构成，找出还有哪些区域偏离整体水平」），
     * 当章节名太长，且它说的是"要做什么"而不是"这节讲什么"。
     */
    private static String sectionTitle(ReportSource.AnalysisPayload a) {
        for (StepSnapshot step : a.steps()) {
            if (step.label() != null && !step.label().isBlank()) {
                return step.label();
            }
        }
        return a.intent();
    }

    /** 去重键：问题 + 结果列 + 行数。 */
    private static String dedupKey(ReportSource src) {
        String question = src.question() == null ? "" : src.question().trim();
        if (src.hasAnalysis()) {
            // 分析轮：用意图 + 步数 + 第一步的列
            ReportSource.AnalysisPayload a = src.analysis();
            String cols = a.steps().isEmpty() ? "" : String.join(",", a.steps().get(0).columns());
            return "A|" + a.intent() + "|" + a.steps().size() + "|" + cols;
        }
        return "S|" + question + "|" + String.join(",", src.columns()) + "|" + src.rows().size();
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

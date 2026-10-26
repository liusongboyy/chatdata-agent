package com.liusongboyy.chatdata.engine.eval;

import com.liusongboyy.chatdata.engine.analysis.AnalysisResult;
import com.liusongboyy.chatdata.engine.analysis.Finding;
import com.liusongboyy.chatdata.engine.analysis.StepResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 判定一条**分析编排**用例。
 *
 * <h2>为什么不复用结果集比对</h2>
 *
 * <p>分析模式的产出是「步骤数据 + 确定性事实 + 结论」，不是一张表。
 * 只看第一张表对不对，等于只检查了"第一步查得准不准"，
 * 而编排真正的风险在另外两处：**该发现的变化没发现**、**结论里出现了没算过的数字**。
 * 这两处都不会让 SQL 出错，也不会让流程失败。
 *
 * <h2>这里判的是什么</h2>
 *
 * <ol>
 *   <li>步骤失败数不超过上限（默认 0）——缺一步的结论比单查询更误导</li>
 *   <li>期望的事实都在（按「类型:子串」匹配）</li>
 *   <li>结论存在（若要求）</li>
 *   <li>结论里**没有**被禁的数字（抓编造）</li>
 * </ol>
 *
 * <p>前三项之外，叙述的数字白名单在运行时已经强制过一次（{@code NarrativeGuard}）；
 * 这里再查一遍 forbidden 数字，是为了覆盖"白名单的容差放过了一个近似但错误的数"。
 */
public final class AnalysisCaseChecker {

    private AnalysisCaseChecker() {
    }

    /**
     * @return 判定说明；{@code ok=true} 表示通过。失败时 reason 必须能直接指出哪一项没过。
     */
    public record Verdict(boolean ok, String reason) {
    }

    public static Verdict check(EvalCase.AnalysisCase spec, AnalysisResult result) {
        if (result == null) {
            return new Verdict(false, "没有返回分析结果");
        }

        long failures = result.steps().stream().filter(s -> !s.isOk()).count();
        if (failures > spec.maxStepFailures()) {
            List<String> failed = result.steps().stream()
                    .filter(s -> !s.isOk())
                    .map(s -> s.id() + "(" + s.error() + ")")
                    .toList();
            return new Verdict(false, "有 %d 个步骤失败，超出上限 %d：%s"
                    .formatted(failures, spec.maxStepFailures(), String.join("、", failed)));
        }

        List<String> missing = new ArrayList<>();
        for (String want : spec.expectedFindings()) {
            if (!hasFinding(result.findings(), want)) {
                missing.add(want);
            }
        }
        if (!missing.isEmpty()) {
            return new Verdict(false, "缺少期望的事实：%s。实际事实：%s".formatted(
                    String.join("、", missing), describe(result.findings())));
        }

        String narrative = result.narrative();
        if (spec.expectNarrative() && (narrative == null || narrative.isBlank())) {
            return new Verdict(false, "没有结论");
        }

        if (narrative != null) {
            for (String banned : spec.forbiddenNarrativeNumbers()) {
                if (narrative.contains(banned)) {
                    return new Verdict(false,
                            "结论里出现了被禁的数字「" + banned + "」：" + narrative);
                }
            }
        }
        return new Verdict(true, "步骤 %d/%d 成功，事实 %d 条，结论%s".formatted(
                result.steps().size() - failures, result.steps().size(),
                result.findings().size(), narrative == null ? "（无）" : "已生成"));
    }

    /** 规格串形如 {@code "CONTRIBUTION:险种"}；没写类型时只按子串匹配。 */
    private static boolean hasFinding(List<Finding> findings, String spec) {
        int colon = spec.indexOf(':');
        String kind = colon < 0 ? null : spec.substring(0, colon).trim().toUpperCase(Locale.ROOT);
        String needle = colon < 0 ? spec : spec.substring(colon + 1).trim();
        for (Finding f : findings) {
            if (kind != null && !f.kind().name().equals(kind)) {
                continue;
            }
            if (f.text().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(List<Finding> findings) {
        if (findings.isEmpty()) {
            return "（无）";
        }
        return findings.stream()
                .map(f -> f.kind().name() + ":" + f.text())
                .reduce((a, b) -> a + "；" + b)
                .orElse("（无）");
    }

    /** 供报告展示：把步骤与事实压成一段。 */
    public static String digest(AnalysisResult result) {
        if (result == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("步骤 ").append(result.queryCount()).append(" 次");
        for (StepResult s : result.steps()) {
            sb.append(" | ").append(s.id()).append(' ').append(s.label());
            sb.append(s.isOk() ? "（" + s.rows().rowCount() + " 行）" : "（失败：" + s.error() + "）");
        }
        return sb.toString();
    }
}

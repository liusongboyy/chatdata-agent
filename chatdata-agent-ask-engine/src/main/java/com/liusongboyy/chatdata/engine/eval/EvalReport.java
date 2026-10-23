package com.liusongboyy.chatdata.engine.eval;

import java.util.List;
import java.util.Map;

/**
 * 一个评测集的运行报告。
 *
 * <h2>指标口径</h2>
 *
 * <table>
 *   <tr><th>指标</th><th>定义</th><th>为什么单列</th></tr>
 *   <tr><td>严格通过率</td><td>PASS /（总数 - gold 错误）</td>
 *       <td>gold SQL 本身跑不起来是评测集的问题，不该算到被测系统头上</td></tr>
 *   <tr><td>包含式通过率</td><td>数据正确即可，多返回列不算错 / 同分母</td>
 *       <td>spec §12.4：真实场景最大杀手，且常被"总分"掩盖</td></tr>
 *   <tr><td>静默语义错误</td><td>执行成功、无异常，但结果不对（含空结果）。按**包含式口径**判定</td>
 *       <td>最危险的失败形态：不报错、数字看着正常。见 spec §12.4</td></tr>
 *   <tr><td>空结果</td><td>执行成功但返回 0 行，且 gold 不是 0 行</td></tr>
 *   <tr><td>分档通过率</td><td>按 SIMPLE / MEDIUM / HARD（严格口径）</td>
 *       <td>总分会被简单题淹没，短板通常在 HARD</td></tr>
 * </table>
 *
 * @param name        评测集名称
 * @param domainId    语义域 id
 * @param mode        本次实际走到的模式（RAW / MODELED）；没有成功用例时为空
 * @param generatedAt 报告生成时间（ISO-8601）
 * @param total       用例总数（含 gold 错误）
 * @param passed      严格 EX 通过数
 * @param containsPassed 包含式 EX 通过数（含严格通过的）
 * @param failed      未通过数 = 总数 - 通过 - gold 错误
 * @param empty       空结果数（已含在 failed 中）
 * @param errors      执行异常数（已含在 failed 中）
 * @param goldErrors  gold SQL 跑不起来/不合法的用例数
 */
public record EvalReport(
        String name,
        String domainId,
        String mode,
        String generatedAt,
        int total,
        int passed,
        /** 包含式 EX 通过数：多返回列不算错。 */
        int containsPassed,
        int failed,
        int empty,
        int errors,
        int goldErrors,
        double passRate,
        double containsRate,
        double emptyRate,
        double silentErrorRate,
        long avgDurationMs,
        long p50DurationMs,
        Map<String, DifficultyStat> byDifficulty,
        List<CaseResult> cases
) {

    /** 一个难度档的统计。 */
    public record DifficultyStat(int total, int passed) {
        public double passRate() {
            return total == 0 ? 0.0 : (double) passed / total;
        }
    }

    /** 一条用例的结果。 */
    public record CaseResult(
            String id,
            String difficulty,
            String question,
            Status status,
            /**
             * 包含式 EX：不看多出来的列，只看 gold 的列是否都能匹配上。
             * {@code status=PASS} 时必然为 true；两者不一致说明"答案对但多返回了列"。
             */
            boolean containsPassed,
            String reason,
            String goldSql,
            String generatedSql,
            String mode,
            int goldRows,
            int actualRows,
            long durationMs,
            List<String> tags
    ) {
        public CaseResult {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    public enum Status {
        /** 结果与 gold 一致。 */
        PASS,
        /** 执行成功，但结果不一致。 */
        FAIL,
        /** 执行成功，但返回 0 行而 gold 有数据。 */
        EMPTY,
        /** 问数链路抛异常（模型、权限、SQL 执行……）。 */
        ERROR,
        /** gold SQL 本身跑不起来——评测集有问题，不计入通过率分母。 */
        GOLD_ERROR
    }

    /** 人读摘要。JSON 报告给机器，这里给终端。 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 评测结果：").append(name).append("（域 ").append(domainId).append("）===\n");
        if (mode != null) {
            sb.append("模式：").append(mode).append('\n');
        }
        int denominator = total - goldErrors;
        sb.append("用例 ").append(total)
                .append(" | 严格通过 ").append(passed)
                .append("（").append(pct(passRate)).append("）")
                .append(" | 包含式通过 ").append(containsPassed)
                .append("（").append(pct(containsRate)).append("）")
                .append(" | 失败 ").append(failed)
                .append(" | 空结果 ").append(empty)
                .append(" | 执行错误 ").append(errors)
                .append(" | gold 错误 ").append(goldErrors)
                .append('\n');
        if (containsPassed > passed) {
            sb.append("其中 ").append(containsPassed - passed)
                    .append(" 条数据正确但多返回了列（严格 EX 判失败，包含式 EX 判通过）\n");
        }
        if (denominator != total) {
            sb.append("（通过率分母已扣除 gold 错误，实际计入 ").append(denominator).append(" 条）\n");
        }
        if (!byDifficulty.isEmpty()) {
            sb.append("分档：");
            byDifficulty.forEach((k, v) -> sb.append(k).append(' ')
                    .append(v.passed()).append('/').append(v.total())
                    .append("（").append(pct(v.passRate())).append("）  "));
            sb.append('\n');
        }
        sb.append("静默语义错误（包含式）：").append(Math.round(silentErrorRate * denominator))
                .append(" 条（").append(pct(silentErrorRate)).append("）")
                .append("  ← 执行成功但答案不对，监控看不到\n");
        sb.append("空结果率：").append(pct(emptyRate)).append('\n');
        sb.append("耗时：平均 ").append(avgDurationMs).append("ms，P50 ")
                .append(p50DurationMs).append("ms\n");

        List<CaseResult> bad = cases.stream()
                .filter(c -> c.status() != Status.PASS)
                .toList();
        if (!bad.isEmpty()) {
            sb.append("未通过明细：\n");
            for (CaseResult c : bad) {
                sb.append("  [").append(c.status()).append("] ").append(c.id())
                        .append(" — ").append(c.question()).append('\n');
                if (c.reason() != null && !c.reason().isBlank()) {
                    sb.append("      ").append(c.reason().replace("\n", "\n      ")).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String pct(double v) {
        return String.format("%.1f%%", v * 100);
    }
}

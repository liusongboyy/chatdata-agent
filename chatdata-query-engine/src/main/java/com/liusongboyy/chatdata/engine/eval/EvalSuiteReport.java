package com.liusongboyy.chatdata.engine.eval;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次评测运行的总报告：**多个数据集的结果放在一起**。
 *
 * <p>问数是通用的——同一个引擎会接很多数据源、很多数据集。准确率不能只有一个总数：
 * 保险域 90% 而工单域 40% 与反过来，是完全不同的产品状态。
 * 所以报告按数据集分组，同时给一个总览，但不让总数掩盖单集短板。
 *
 * @param generatedAt 生成时间（ISO-8601）
 * @param total       所有数据集的用例总数
 * @param passed      通过总数
 * @param failed      失败总数（不含 gold 错误）
 * @param empty       空结果总数
 * @param errors      执行异常总数
 * @param goldErrors  gold SQL 错误总数
 * @param passRate    总通过率（严格 EX，分母扣除 gold 错误）
 * @param primaryRate 总通过率（**按各数据集自己声明的主指标**汇总）
 * @param byDifficulty 跨数据集的分档汇总
 * @param reports     每个数据集一份
 */
public record EvalSuiteReport(
        String generatedAt,
        int total,
        int passed,
        int containsPassed,
        int failed,
        int empty,
        int errors,
        int goldErrors,
        double passRate,
        double containsRate,
        double primaryRate,
        Map<String, EvalReport.DifficultyStat> byDifficulty,
        List<EvalReport> reports
) {

    public static EvalSuiteReport of(List<EvalReport> reports) {
        int total = 0;
        int passed = 0;
        int containsPassed = 0;
        int failed = 0;
        int empty = 0;
        int errors = 0;
        int goldErrors = 0;

        Map<String, int[]> diff = new LinkedHashMap<>();
        for (EvalCase.Difficulty d : EvalCase.Difficulty.values()) {
            diff.put(d.name(), new int[2]);
        }

        for (EvalReport r : reports) {
            total += r.total();
            passed += r.passed();
            containsPassed += r.containsPassed();
            failed += r.failed();
            empty += r.empty();
            errors += r.errors();
            goldErrors += r.goldErrors();
            r.byDifficulty().forEach((k, v) -> {
                int[] acc = diff.computeIfAbsent(k, x -> new int[2]);
                acc[0] += v.total();
                acc[1] += v.passed();
            });
        }

        Map<String, EvalReport.DifficultyStat> byDifficulty = new LinkedHashMap<>();
        diff.forEach((k, v) -> {
            if (v[0] > 0) {
                byDifficulty.put(k, new EvalReport.DifficultyStat(v[0], v[1]));
            }
        });

        // 主指标按**每个数据集各自的声明**汇总：直连域用包含式、建模域用严格式，
        // 混在一起就只能是"平均了两个不同问题的答案"，那不是可验收的数字
        int primaryPassed = 0;
        for (EvalReport r : reports) {
            primaryPassed += r.primaryPassed();
        }

        int denominator = total - goldErrors;
        double rate = denominator == 0 ? 0.0 : (double) passed / denominator;
        double containsRate = denominator == 0 ? 0.0 : (double) containsPassed / denominator;
        double primaryRate = denominator == 0 ? 0.0 : (double) primaryPassed / denominator;

        return new EvalSuiteReport(
                OffsetDateTime.now(ZoneOffset.ofHours(8)).toString(),
                total, passed, containsPassed, failed, empty, errors, goldErrors,
                rate, containsRate, primaryRate, byDifficulty, List.copyOf(reports));
    }

    /** 终端摘要：总览 + 每个数据集单独一段。 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("========== 评测总览 ==========\n");
        sb.append("数据集 ").append(reports.size())
                .append(" | 用例 ").append(total)
                .append(" | 严格通过 ").append(passed)
                .append(" | 包含式通过 ").append(containsPassed)
                .append(" | 失败 ").append(failed)
                .append(" | 空结果 ").append(empty)
                .append(" | 执行错误 ").append(errors)
                .append(" | gold 错误 ").append(goldErrors).append('\n');
        sb.append("总通过率（严格 EX）：").append(String.format("%.1f%%", passRate * 100))
                .append(" | 包含式 EX：").append(String.format("%.1f%%", containsRate * 100))
                .append('\n');
        sb.append("总通过率（按各集主指标）：").append(String.format("%.1f%%", primaryRate * 100))
                .append("   ← CI 门禁与验收看这一行").append('\n');
        if (!byDifficulty.isEmpty()) {
            sb.append("分档：");
            byDifficulty.forEach((k, v) -> sb.append(k).append(' ')
                    .append(v.passed()).append('/').append(v.total())
                    .append("（").append(String.format("%.1f%%", v.passRate() * 100)).append("）  "));
            sb.append('\n');
        }
        sb.append('\n');
        for (EvalReport r : reports) {
            sb.append(r.summary()).append('\n');
        }
        return sb.toString();
    }
}

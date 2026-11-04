package com.liusongboyy.chatdata.ask.health;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 给一次失败下诊断：**能不能修、改哪里、或者为什么修不了**。
 *
 * <h2>为什么这一步比"给修复建议"更重要</h2>
 *
 * <p>实测三类真实失败，下一步动作完全不同：
 *
 * <table>
 *   <tr><th>失败</th><th>反查结果</th><th>该做什么</th></tr>
 *   <tr><td>「平均优先级是多少」22 次</td><td>域里有 AVG(int) 的「平均优先级」</td>
 *       <td><b>什么也做不了</b>——模型能力边界</td></tr>
 *   <tr><td>「客户总数是多少」20 次</td><td>域里有「客户数」</td>
 *       <td>配置已有，问题在模型选字段</td></tr>
 *   <tr><td>「各门店的订单金额合计」12 次</td><td>落在保险域，无门店字段</td>
 *       <td><b>问错了域</b></td></tr>
 * </table>
 *
 * <p>如果把"改配置"当万能解，管理员会在第一、三类上空转。
 * 所以诊断的第一步不是给建议，而是判断能不能修。
 *
 * <h2>纯函数</h2>
 *
 * <p>输入（问题、失败原因、域里有哪些字段）→ 输出（结论 + 定位）。
 * 不查库、不调模型，可单测。结论**实时计算**，配置改过之后立刻反映。
 */
public final class FailureDiagnosis {

    private FailureDiagnosis() {
    }

    /**
     * 诊断结论。
     *
     * @param kind       结论类型
     * @param summary    一句话结论，给人看
     * @param evidence   证据。**这是关键**——「检查过：已配 AVG」证明系统真的看了配置，
     *                   而不是甩一句"模型没理解"就走
     * @param fixTarget  能修时指向哪一列；不能修时为 null
     * @param confidence 匹配置信度
     */
    public record Diagnosis(Kind kind, String summary, String evidence,
                            FixTarget fixTarget, Confidence confidence) {
    }

    /** 结论类型。前端据此配色与决定给不给跳转。 */
    public enum Kind {
        /** 配置缺失，能修 */
        MISSING_CONFIG("配置缺失", true),
        /** 配置已经有了，问题在模型 */
        CONFIG_PRESENT("配置已有", false),
        /** 问错了域：这个域里没有任何相关字段 */
        WRONG_DOMAIN("可能问错了域", false),
        /** 环境问题 */
        ENVIRONMENT("环境问题", false),
        /** 判断不了——**不假装有答案** */
        UNKNOWN("无法判断", false);

        private final String label;
        private final boolean actionable;

        Kind(String label, boolean actionable) {
            this.label = label;
            this.actionable = actionable;
        }

        public String label() {
            return label;
        }

        /** 能不能靠改配置解决。 */
        public boolean actionable() {
            return actionable;
        }
    }

    /**
     * 匹配置信度。
     *
     * <p>反查用的是子串包含，一定会有误匹配（「今天上海的天气」会碰到含"上海"的字段）。
     * 所以结论必须分档，**「无法判断」时不假装有答案**。
     */
    public enum Confidence {
        /** 精确匹配到了字段别名 */
        EXACT,
        /** 部分匹配（含相同子串） */
        PARTIAL,
        /** 完全匹配不上 */
        NONE
    }

    /** 指向要改的那一列。 */
    public record FixTarget(String table, String column, String hint) {
    }

    /** 域里的一个字段。诊断只需要这三样。 */
    public record Field(String table, String alias) {
    }

    /**
     * 诊断一次失败。
     *
     * @param errorMessage 审计表里的 error_message
     * @param question     原始问题
     * @param fields       这个域里**对 LLM 可见**的字段清单（表名 + 业务名）
     */
    public static Diagnosis diagnose(String errorMessage, String question, List<Field> fields) {
        var c = FailureClassifier.classify(errorMessage);

        return switch (c.fixability()) {
            case ENVIRONMENT -> new Diagnosis(FailureDiagnosis.Kind.ENVIRONMENT,
                    "这是环境问题，不是建模配置的问题。",
                    "登录服务或数据库可能暂时不可用",
                    null, Confidence.NONE);

            case CONFIG -> configDiagnosis(c.key(), question, fields);

            case NOT_CONFIG -> notConfigDiagnosis(c.key(), question, fields);
        };
    }

    // ── 能修的：给定位 ──

    private static Diagnosis configDiagnosis(String key, String question, List<Field> fields) {
        return switch (key) {
            case "ROLE_CONFLICT" -> new Diagnosis(FailureDiagnosis.Kind.MISSING_CONFIG,
                    "这个字段只登记了一种身份，需要补一条。",
                    "同一个物理列要有「维度」和「指标」两条登记："
                            + "维度负责筛选/分组，指标负责聚合。只登记一种，另一种问法就永远问不出来。",
                    locate(question, fields, "补一条不聚合的维度登记"),
                    Confidence.PARTIAL);

            case "NO_TIME_COLUMN" -> new Diagnosis(FailureDiagnosis.Kind.MISSING_CONFIG,
                    "问题里有时间条件，但这个域没有可识别的日期列。",
                    "把日期那一列的别名改成「生效日期」「统计日期」这类含时间词的名称，"
                            + "引擎才能认出它是时间口径。",
                    null, Confidence.NONE);

            case "NO_OUTPUT_COLUMN" -> new Diagnosis(FailureDiagnosis.Kind.MISSING_CONFIG,
                    "查询能跑但没有任何可展示的列。",
                    "指标可能配了但没启用，或者指标的聚合方式为空。",
                    null, Confidence.NONE);

            case "UNKNOWN_COLUMN" -> new Diagnosis(FailureDiagnosis.Kind.MISSING_CONFIG,
                    "模型编了一个不存在的列。",
                    "通常是提问里用了一个域内没注册的说法。"
                            + "检查这个域的字段名是否覆盖了常见叫法，或补一条业务术语。",
                    locate(question, fields, "检查这个字段是否已注册"),
                    Confidence.PARTIAL);

            default -> new Diagnosis(FailureDiagnosis.Kind.UNKNOWN, "暂时给不出具体建议。",
                    null, null, Confidence.NONE);
        };
    }

    // ── 不能修的：说清为什么 ──

    /**
     * 改配置解决不了的失败。
     *
     * <p><b>这一类最需要"证据"。</b>用户看到 100% 失败会本能地去改配置，
     * 而这里要告诉他：已经查过了，配置是对的，别白费力气。
     */
    private static Diagnosis notConfigDiagnosis(String key, String question,
                                                List<Field> fields) {
        if (!"NOT_UNDERSTOOD".equals(key)) {
            return new Diagnosis(FailureDiagnosis.Kind.UNKNOWN,
                    "这类失败通常与建模配置无关。", null, null, Confidence.NONE);
        }

        // NOT_UNDERSTOOD：反查问题里的词能否对上已注册字段
        Match m = matchQuestion(question, fields);

        if (m.confidence == Confidence.EXACT) {
            return new Diagnosis(FailureDiagnosis.Kind.CONFIG_PRESENT,
                    "配置已经有了，问题在模型没能选到它。",
                    "检查过：域里有「" + m.field.alias() + "」这个字段。"
                            + "这是模型能力边界，改建模配置解决不了。"
                            + "可以试试换一种问法，或者在问题里用这个字段的准确名称。",
                    null, Confidence.EXACT);
        }
        if (m.confidence == Confidence.PARTIAL) {
            return new Diagnosis(FailureDiagnosis.Kind.CONFIG_PRESENT,
                    "域里有相近的字段，但模型没匹配上。",
                    "最接近的是「" + m.field.alias() + "」。"
                            + "如果这个字段就是问题想问的，可以把别名改成与常见问法一致，"
                            + "或给它加一条业务术语（同义词）。",
                    null, Confidence.PARTIAL);
        }
        // 一个都对不上 —— 很可能问错了域
        return new Diagnosis(FailureDiagnosis.Kind.WRONG_DOMAIN,
                "这个域里没有与问题相关的字段。",
                "问题里的关键词在本域内一个都没匹配到。"
                        + "如果这个问题本来就该走别的数据域，换一个助理再问一次。",
                null, Confidence.NONE);
    }

    // ── 反查 ──

    private record Match(Field field, Confidence confidence) {
    }

    /** 问题里的有效片段长度下限。太短的片段会有大量误匹配。 */
    private static final int MIN_TOKEN = 2;

    /**
     * 把问题拆成片段，跟字段别名比对。
     *
     * <p><b>必须模糊匹配</b>：实测「客户总数」精确匹配不到「客户数」，
     * 两者只差一个字。用子串包含才能对上。
     *
     * <p>但模糊一定会有误匹配（「今天上海的天气」会碰到含"上海"的字段），
     * 所以优先找精确的，找不到才退到包含关系。
     */
    private static Match matchQuestion(String question, List<Field> fields) {
        if (question == null || question.isBlank() || fields == null || fields.isEmpty()) {
            return new Match(null, Confidence.NONE);
        }
        // 去掉问号、空格等，只留实质内容
        String q = question.trim().toLowerCase(Locale.ROOT);

        // ① 精确：某个别名的完整字符串出现在问题里。
        //    **取最长的那个匹配**——「平均优先级是多少」同时命中「优先级」与
        //    「平均优先级」，前者短但先遍历到，直接用会让证据指向错的字段。
        //    别名越长越具体，越可能是问题真正指的那个。
        Field best = null;
        int bestLen = 0;
        for (Field f : fields) {
            if (f.alias() == null || f.alias().isBlank()) {
                continue;
            }
            String a = f.alias().trim().toLowerCase(Locale.ROOT);
            if (a.length() >= MIN_TOKEN && q.contains(a) && a.length() > bestLen) {
                best = f;
                bestLen = a.length();
            }
        }
        if (best != null) {
            return new Match(best, Confidence.EXACT);
        }

        // ② 部分：问题里某个片段被别名包含。取最长的那个片段，
        //    片段越长越可信——匹配到「客户」比匹配到「数」有意义得多
        String bestToken = null;
        Field bestField = null;
        for (String token : splitTokens(q)) {
            for (Field f : fields) {
                if (f.alias() == null || f.alias().isBlank()) {
                    continue;
                }
                String a = f.alias().trim().toLowerCase(Locale.ROOT);
                boolean hit = a.contains(token) || token.contains(a);
                if (hit && (bestToken == null || token.length() > bestToken.length())) {
                    bestToken = token;
                    bestField = f;
                }
            }
        }
        if (bestField != null) {
            return new Match(bestField, Confidence.PARTIAL);
        }
        return new Match(null, Confidence.NONE);
    }

    /**
     * 把问题切成候选片段。
     *
     * <p>中文没有空格分词，这里用**连续汉字的滑动窗口**：
     * 「客户总数是多少」→ 客户总数、户总数是、总数是多……再筛掉停用词。
     *
     * <p>不引入分词库：那是另一个依赖，而这里只要能"碰巧对上"就够了——
     * 对不上会归到「无法判断」，那是安全的方向。
     */
    private static List<String> splitTokens(String q) {
        List<String> out = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < q.length(); i++) {
            char ch = q.charAt(i);
            if (Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                run.append(ch);
            } else {
                flush(run, out);
            }
        }
        flush(run, out);

        // 对每段连续汉字，取长度 >= MIN_TOKEN 的前缀窗口
        List<String> windows = new ArrayList<>();
        for (String seg : out) {
            for (int len = seg.length(); len >= MIN_TOKEN; len--) {
                for (int start = 0; start + len <= seg.length(); start++) {
                    String w = seg.substring(start, start + len);
                    if (!STOP_WORDS.contains(w)) {
                        windows.add(w);
                    }
                }
            }
        }
        return windows;
    }

    private static void flush(StringBuilder run, List<String> out) {
        if (run.length() >= MIN_TOKEN) {
            out.add(run.toString());
        }
        run.setLength(0);
    }

    /**
     * 停用词。
     *
     * <p>这些词出现在几乎每句话里，让它们参与匹配等于让所有问题都能"匹配上"。
     */
    private static final java.util.Set<String> STOP_WORDS = java.util.Set.of(
            "是多少", "多少", "什么", "怎么", "如何", "哪些", "哪个",
            "一共", "总共", "合计", "统计", "查询", "数据", "情况");

    /**
     * 从问题里定位到具体某一列。
     *
     * <p>定位不到返回 null——**宁可不给跳转，也不要跳到一个随便挑的列上**。
     */
    private static FixTarget locate(String question, List<Field> fields, String hint) {
        Match m = matchQuestion(question, fields);
        if (m.field == null) {
            return null;
        }
        return new FixTarget(m.field.table(), m.field.alias(), hint);
    }
}

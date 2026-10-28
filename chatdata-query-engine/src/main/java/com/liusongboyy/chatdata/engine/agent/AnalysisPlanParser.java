package com.liusongboyy.chatdata.engine.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.liusongboyy.chatdata.engine.analysis.AnalysisPlan;
import com.liusongboyy.chatdata.engine.analysis.PlanStep;
import com.liusongboyy.chatdata.engine.nlsql.AskDsl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把模型返回的分析计划 JSON 解析成 {@link AnalysisPlan}。
 *
 * <h2>与 {@link DslParser} 的关系</h2>
 *
 * <p>每一个步骤的 {@code dsl} 仍然走 {@link DslParser#parse}——**不另写一套 DSL 解析**。
 * 两条链路的 DSL 必须由同一个解析器产生，否则"单查询能解析、编排里解析不了"这类
 * 分叉迟早出现，而它一定落在权限或字段校验上。
 *
 * <h2>整条拒绝，不做部分执行</h2>
 *
 * <p>任何一步解析失败、id 重复、DSL 重复，都让整条计划作废并回退单查询。
 * 跳过坏步骤执行剩余几步，会得出一个"看起来完整、实则少了一块"的结论——
 * 比直接承认"没拆明白"更危险。
 */
public final class AnalysisPlanParser {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPlanParser.class);
    private static final ObjectMapper M = new ObjectMapper();
    /** 明确的年份写法。相对时间（今年/上季度）不在此推断，见 inferTimeRange。 */
    private static final Pattern YEAR = Pattern.compile("(20\\d{2})\\s*年");

    private AnalysisPlanParser() {
    }

    public static AnalysisPlan parse(String json) {
        return parse(json, null);
    }

    /**
     * @param question 用户原话。用于补全 compare 缺的时间范围（见 sanitize）
     */
    public static AnalysisPlan parse(String json, String question) {
        if (json == null || json.isBlank()) {
            throw new DslGenerationException("分析计划为空");
        }

        JsonNode root;
        try {
            root = M.readTree(DslParser.extractJson(json));
        } catch (Exception e) {
            throw new DslGenerationException("分析计划不是合法 JSON：" + truncate(json));
        }
        if (!root.isObject()) {
            throw new DslGenerationException("分析计划必须是对象");
        }

        JsonNode stepsNode = root.path("steps");
        if (!stepsNode.isArray() || stepsNode.isEmpty()) {
            throw new DslGenerationException("分析计划必须包含 steps 数组，且至少一步");
        }

        List<PlanStep> steps = new ArrayList<>(stepsNode.size());
        Set<String> ids = new LinkedHashSet<>();
        Set<String> dslKeys = new LinkedHashSet<>();

        for (int i = 0; i < stepsNode.size(); i++) {
            JsonNode n = stepsNode.get(i);
            if (!n.isObject()) {
                throw new DslGenerationException("第 " + (i + 1) + " 步不是对象");
            }
            String id = textOrNull(n.path("id"));
            if (id == null) {
                id = "s" + (i + 1);
            }
            if (!ids.add(id)) {
                throw new DslGenerationException("分析计划里步骤 id 重复：" + id);
            }

            JsonNode dslNode = n.path("dsl");
            if (!dslNode.isObject()) {
                throw new DslGenerationException("步骤「" + id + "」缺少 dsl 对象");
            }
            AskDsl dsl;
            try {
                dsl = DslParser.parse(dslNode.toString());
            } catch (DslGenerationException e) {
                throw new DslGenerationException(
                        "步骤「" + id + "」的 DSL 不合法：" + e.getMessage());
            }

            // 同一个 DSL 跑两遍只会浪费一次查询与一次模型调用，还占掉一个步骤名额
            if (!dslKeys.add(dslNode.toString())) {
                throw new DslGenerationException(
                        "步骤「" + id + "」与前面的步骤是同一个查询，请合并或换成不同角度");
            }

            dsl = sanitize(id, dsl, question);
            steps.add(new PlanStep(id, textOrNull(n.path("label")),
                    textOrNull(n.path("purpose")), dsl));
        }

        try {
            return new AnalysisPlan(textOrNull(root.path("intent")), steps);
        } catch (IllegalArgumentException e) {
            throw new DslGenerationException(e.getMessage());
        }
    }

    /**
     * 计划期的可执行性处理：**能修就修，修不了才拒绝**。
     *
     * <p>模型给 compare 时经常加点"顺手"的增强——自定义对比却没给日期、
     * 同比却按天分组。这些是它自己加的，不是用户说过的约束：
     * 丢掉 compare，这一步仍查得出正确数据；而让整条计划作废、退化成单查询，
     * 用户拿到的只会更少。
     *
     * <p>只处理**可安全丢弃的增强**。像"字段不存在"这种，丢弃等于改问题，
     * 仍然整条拒绝。
     *
     * @return 修好的 DSL（可能去掉了 compare）
     */
    /**
     * @param question 用户原话。用于在 compare 缺 timeRange 时<b>补</b>出时间范围，
     *                 而不是把对比丢掉
     */
    private static AskDsl sanitize(String id, AskDsl dsl, String question) {
        if (dsl.compare() == null) {
            return dsl;
        }

        // ① 同比/环比缺 timeRange：先从问题里把时间捞出来补上。
        //    **不能直接丢掉 compare**——用户明确说了「同比」，丢掉等于答应了一次没做的对比。
        //    实测就是这么翻车的：模型拆对了步骤，只是没把「2025 年」填进 timeRange
        if (dsl.timeRange() == null) {
            AskDsl.TimeRange inferred = inferTimeRange(question);
            if (inferred != null) {
                log.info("分析步骤「{}」的 compare 缺时间范围，已从问题推断为「{}」",
                        id, inferred.kind());
                dsl = dsl.withTimeRange(inferred);
            }
        }

        boolean bad = false;
        if (dsl.timeRange() == null) {
            // 推断不出来：这才是真的没法比
            bad = true;
        } else if (dsl.compare().mode() == AskDsl.Compare.Mode.YOY
                && !dsl.dimensions().isEmpty()
                && dsl.granularity() == AskDsl.Granularity.DAY) {
            // ② 按天做同比会踩到 2 月 29 日并组——这个**可以安全修**：
            //    把粒度提到月，语义仍是"逐期同比"
            dsl = dsl.withGranularity(AskDsl.Granularity.MONTH);
            log.info("分析步骤「{}」同比按天分组，已改为按月", id);
        }
        if (!bad) {
            return dsl;
        }
        log.info("分析步骤「{}」的 compare 无从对齐，已丢弃该增强并继续", id);
        return dsl.withoutCompare();
    }

    /**
     * 从问题里找出一个「明确的年份」，用来补 compare 缺的时间范围。
     *
     * <p>只认 {@code 20xx 年} 这种写法——它无歧义。相对说法（「今年」「上季度」）
     * 不在这里推断：那要依赖"今天是几号"，而计划阶段与执行阶段可能跨零点，
     * 两份判断不一致时，回显与 SQL 就会对不上。
     *
     * @return 形如 {@code new AskDsl.TimeRange("2025年", null, null)}；找不到返回 null
     */
    private static AskDsl.TimeRange inferTimeRange(String question) {
        if (question == null) {
            return null;
        }
        Matcher m = YEAR.matcher(question);
        String found = null;
        // 取**最后一个**年份：中文问法里限定期通常出现在后半句
        // （「保费比 2024 年下降了吗，2025 年的情况」→ 2025）
        while (m.find()) {
            found = m.group(1);
        }
        return found == null ? null : new AskDsl.TimeRange(found + "年", null, null);
    }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        String v = n.asText();
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static String truncate(String s) {
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }

    /**
     * 分析计划的 JSON Schema。**手写，理由同 {@code AskDslSchema}**——
     * 反射生成的 schema 会让字段被系统性丢弃。
     *
     * <p>每一步的 {@code dsl} 复用 {@link com.liusongboyy.chatdata.engine.nlsql.AskDslSchema}
     * 的字段定义，保证"单查询能问的，编排里也能问"。
     */
    public static JsonNode jsonSchema() {
        ObjectNode root = M.createObjectNode();
        root.put("type", "object");
        root.put("description", "把一个问题拆成若干次取数。只拆必需的步骤，最多 4 步。");

        ObjectNode props = root.putObject("properties");
        props.set("intent", string("用户想解决的问题，用一句话复述"));

        ObjectNode steps = props.putObject("steps");
        steps.put("type", "array");
        steps.put("description",
                "分析步骤。每一步只回答一个小问题；步骤之间不要重复取数。");
        ObjectNode item = steps.putObject("items");
        item.put("type", "object");
        ObjectNode ip = item.putObject("properties");
        ip.set("id", string("步骤标识，如 s1；省略时按顺序生成"));
        ip.set("label", string("这一步的人话标题，如「各险种的保费」"));
        ip.set("purpose", string("这一步想验证什么，写给结论阶段用"));
        ip.set("dsl", com.liusongboyy.chatdata.engine.nlsql.AskDslSchema.jsonSchema());
        ArrayNode required = item.putArray("required");
        required.add("dsl");

        ArrayNode rootRequired = root.putArray("required");
        rootRequired.add("intent").add("steps");

        return root;
    }

    private static ObjectNode string(String description) {
        ObjectNode n = M.createObjectNode();
        n.put("type", "string");
        n.put("description", description);
        return n;
    }
}

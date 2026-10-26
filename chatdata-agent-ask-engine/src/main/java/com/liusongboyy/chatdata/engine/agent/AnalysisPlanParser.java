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

    private AnalysisPlanParser() {
    }

    public static AnalysisPlan parse(String json) {
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

            dsl = sanitize(id, dsl);
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
    private static AskDsl sanitize(String id, AskDsl dsl) {
        if (dsl.compare() == null) {
            return dsl;
        }
        boolean bad = false;
        if (dsl.timeRange() == null) {
            // 没有时间范围就没有"上期"
            bad = true;
        } else if (dsl.compare().mode() == AskDsl.Compare.Mode.YOY
                && !dsl.dimensions().isEmpty()
                && dsl.granularity() == AskDsl.Granularity.DAY) {
            // 按天做同比会踩到 2 月 29 日并组
            bad = true;
        }
        if (!bad) {
            return dsl;
        }
        log.info("分析步骤「{}」的对比期不成立，已丢弃该增强并继续", id);
        return dsl.withoutCompare();
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

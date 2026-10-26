package com.liusongboyy.chatdata.engine.semantic;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 指标的统计条件：只把满足条件的行计入聚合。
 *
 * <h2>为什么它是语义层的东西，而不是让模型加筛选</h2>
 *
 * <p>「已解决工单数」是一个**口径**：先按状态过滤再计数。用户问「已解决占比」时，
 * 分子分母必须来自同一份口径定义。如果让模型自己在 DSL 里加 filter，
 * 整条查询都会被过滤掉——分母也会消失，得到的永远是 100%。
 *
 * <h2>AND / OR 只做一层</h2>
 *
 * <p>条件是一组 {@link ConditionTerm} 用同一个 {@link Logic} 连起来：
 * 「已解决 且 优先级 &gt; 2」「待处理 或 处理中」。
 * <b>不支持嵌套</b>（(A 或 B) 且 C）——那需要表达式树，而真实口径里极少见；
 * 嵌套结构还会让管理台的编辑器变得难用。需要时再扩展，不在这一层先猜。
 *
 * @param logic 条件之间的连接方式；只有一个条件时无意义
 * @param terms 条件项，至少一个
 */
public record MetricCondition(Logic logic, List<ConditionTerm> terms) {

    /** 条件之间的连接方式。 */
    public enum Logic {
        AND, OR;

        static Logic parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return AND;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "and", "且", "与" -> AND;
                case "or", "或" -> OR;
                default -> throw new SemanticException(
                        SemanticException.Reason.INVALID_TABLE_DEFINITION,
                        "统计条件的 logic 只能是 and / or，实际：" + raw);
            };
        }

        public String keyword() {
            return name();
        }
    }

    public MetricCondition {
        if (terms == null || terms.isEmpty()) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "统计条件不能为空");
        }
        terms = List.copyOf(terms);
        if (terms.size() > MAX_TERMS) {
            // 无上限的话，一个手滑粘贴进去的条件列表能生成几百个占位符
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "统计条件最多 " + MAX_TERMS + " 项，实际 " + terms.size());
        }
        logic = logic == null ? Logic.AND : logic;
    }

    private static final int MAX_TERMS = 10;

    /** 单条件：与常量比较。 */
    public MetricCondition(String field, String op, String value) {
        this(Logic.AND, List.of(new ConditionTerm(field, op, value, null)));
    }

    /** 单条件：与另一个字段比较。 */
    public MetricCondition(String field, String op, String value, String rightField) {
        this(Logic.AND, List.of(new ConditionTerm(field, op, value, rightField)));
    }

    /** 从 {@code columns_json} 的 {@code condition} 块解析。 */
    public static MetricCondition from(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (!n.isObject()) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "指标的 condition 必须是对象");
        }
        JsonNode arr = n.path("conditions");
        if (arr.isMissingNode() || arr.isNull()) {
            // 单条件形态：{field, op, value|rightField}
            return new MetricCondition(Logic.AND, List.of(term(n)));
        }
        if (!arr.isArray()) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "指标的 condition.conditions 必须是数组");
        }
        List<ConditionTerm> terms = new ArrayList<>(arr.size());
        for (JsonNode item : arr) {
            if (!item.isObject() || !item.path("conditions").isMissingNode()) {
                throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                        "统计条件不支持嵌套分组——AND/OR 只做一层");
            }
            terms.add(term(item));
        }
        return new MetricCondition(Logic.parse(text(n, "logic")), terms);
    }

    private static ConditionTerm term(JsonNode n) {
        String field = text(n, "field");
        String value = text(n, "value");
        String right = text(n, "rightField");
        if (field == null) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "统计条件缺少 field");
        }
        return new ConditionTerm(field, text(n, "op"), value, right);
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    /** 展示用，如 {@code 状态 = 已解决 且 优先级 > 2}。 */
    public String display() {
        String joiner = logic == Logic.AND ? " 且 " : " 或 ";
        return String.join(joiner, terms.stream().map(ConditionTerm::display).toList());
    }
}

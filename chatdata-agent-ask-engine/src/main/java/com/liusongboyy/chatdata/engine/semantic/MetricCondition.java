package com.liusongboyy.chatdata.engine.semantic;

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
 * <p>所以条件挂在**指标自己**身上，只影响这一列的聚合：
 * {@code COUNT(CASE WHEN 状态 = ? THEN 工单号 END)}。
 *
 * <h2>只支持字面量比较</h2>
 *
 * <p>不做列与列比较（「关闭时间晚于 SLA 截止时间」）——那是另一类语义原语，
 * 需要两边都解析成列引用并处理类型，留到后面单独做。这里是"这个指标只看某些行"，
 * 取值来自管理台配置，不是用户输入的表达式。
 *
 * @param field 条件字段的业务名。可以是**跨表**的列（JOIN 由规划器补齐）
 * @param op    运算符：= / != / &gt; / &lt; / &gt;= / &lt;= / in / like
 * @param value 比较值。{@code in} 用逗号分隔多个取值
 */
public record MetricCondition(String field, String op, String value) {

    public MetricCondition {
        if (field == null || field.isBlank()) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "指标的统计条件缺少字段名");
        }
        if (value == null || value.isBlank()) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "指标的统计条件「" + field.trim() + "」缺少比较值");
        }
        field = field.trim();
        op = normalizeOp(op);
        value = value.trim();
    }

    /**
     * 规范化运算符。
     *
     * <p>不认识的取值**拒绝**：静默忽略等于把这个条件整条丢掉，
     * 而结果是"指标看起来正常、只是数字偏大"——最危险的那种错。
     */
    private static String normalizeOp(String raw) {
        if (raw == null || raw.isBlank()) {
            return "=";
        }
        String op = raw.trim();
        return switch (op) {
            case "=", "==" -> "=";
            case "!=", "<>" -> "!=";
            case ">", "<", ">=", "<=" -> op;
            case "in", "IN", "In" -> "IN";
            case "like", "LIKE", "Like" -> "LIKE";
            default -> throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "指标的统计条件运算符不受支持：" + raw + "（可用：= / != / > / < / >= / <= / in / like）");
        };
    }

    /** 从 {@code columns_json} 的 {@code condition} 块解析。 */
    public static MetricCondition from(com.fasterxml.jackson.databind.JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (!n.isObject()) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "指标的 condition 必须是对象，如 {\"field\":\"状态\",\"op\":\"=\",\"value\":\"已解决\"}");
        }
        String field = text(n, "field");
        String value = text(n, "value");
        if (field == null || value == null) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "指标的 condition 必须同时给出 field 与 value");
        }
        return new MetricCondition(field, text(n, "op"), value);
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode n, String key) {
        var v = n.path(key);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    /** 展示用，如 {@code 状态 = 已解决}。 */
    public String display() {
        return field + " " + (("IN".equals(op) || "LIKE".equals(op)) ? op.toLowerCase(Locale.ROOT) : op)
                + " " + value;
    }
}

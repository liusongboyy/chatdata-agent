package com.liusongboyy.chatdata.engine.semantic;

/**
 * 统计条件里的一项：一个字段与**常量**或**另一个字段**的比较。
 *
 * <p>两种形态互斥：
 * <ul>
 *   <li>与常量比：{@code 状态 = RESOLVED}，取值走占位符</li>
 *   <li>与另一个字段比：{@code 关闭时间 &gt; SLA截止时间}，两侧都是列引用</li>
 * </ul>
 *
 * <h2>为什么不支持"字段与字段"的 in / like</h2>
 *
 * <p>{@code IN} 与 {@code LIKE} 在右侧写列名没有公认语义（是子查询？是模式？），
 * 各库行为还不一致。故只允许比较运算符，其余取值一律拒绝。
 *
 * @param field      左侧字段的业务名
 * @param op         运算符：= / != / &gt; / &lt; / &gt;= / &lt;= / in / like
 * @param value      与常量比较时的取值；与字段比较时为 null
 * @param rightField 与字段比较时的右侧字段业务名；与常量比较时为 null
 */
public record ConditionTerm(String field, String op, String value, String rightField) {

    public ConditionTerm {
        if (field == null || field.isBlank()) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "统计条件缺少字段名");
        }
        field = field.trim();
        op = normalizeOp(op);

        boolean hasValue = value != null && !value.isBlank();
        boolean hasRight = rightField != null && !rightField.isBlank();
        if (hasValue == hasRight) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "统计条件「" + field + "」必须给出 value（常量）或 rightField（字段名）中的一个");
        }
        if (hasRight) {
            if ("IN".equals(op) || "LIKE".equals(op)) {
                throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                        "字段与字段之间只能用 = / != / > / < / >= / <= 比较，不能用 " + op);
            }
            rightField = rightField.trim();
            value = null;
        } else {
            value = value.trim();
            rightField = null;
        }
    }

    /** 是否是与另一个字段比较。 */
    public boolean comparesWithField() {
        return rightField != null;
    }

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
                    "统计条件的运算符不受支持：" + raw
                            + "（可用：= / != / > / < / >= / <= / in / like）");
        };
    }

    /** 展示用，如 {@code 状态 = 已解决}、{@code 关闭时间 > SLA截止时间}。 */
    public String display() {
        String shown = switch (op) {
            case "!=" -> "≠";
            case ">=" -> "≥";
            case "<=" -> "≤";
            case "IN" -> "in";
            case "LIKE" -> "like";
            default -> op;
        };
        return field + " " + shown + " " + (comparesWithField() ? rightField : value);
    }
}

package com.liusongboyy.chatdata.engine.semantic;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/**
 * 两个注册表之间的关联。
 *
 * <p>解析自 {@code ask_domain.joins_json} 的一项，字段名与厂商保持一致以便照搬配置。
 * 实测确认 {@code left}/{@code right} 存的是**注册表 id**，不是表名。
 *
 * <h2>方向是有语义的，RIGHT 归一化成 LEFT</h2>
 *
 * <p>{@link JoinType#LEFT} 意味着「保留 {@code leftTableId} 的全部行」。
 * {@code RIGHT JOIN a b} 等价于 {@code LEFT JOIN b a}，故在解析时就交换操作数归一化，
 * 让下游只需处理一种方向。
 *
 * <p>这个归一化不是洁癖：若下游要自己判断方向，迟早会有人在某个分支里搞反，
 * 而搞反的后果是**左连接变成右连接**——行数变了，数字变了，且不报错。
 *
 * <p>{@link JoinType#INNER} 方向无关，可双向遍历；{@link JoinType#LEFT} 只能从左往右遍历。
 *
 * @param id          规则标识，用于审计与排错
 * @param leftTableId 左表注册表 id
 * @param rightTableId 右表注册表 id
 * @param leftColumn  左表连接列
 * @param rightColumn 右表连接列
 * @param type        关联类型
 */
public record TableJoin(
        long id,
        String leftTableId,
        String rightTableId,
        String leftColumn,
        String rightColumn,
        JoinType type
) {

    public enum JoinType {
        /** 内连接。方向无关，可双向遍历。 */
        INNER,
        /** 左外连接：保留 {@code leftTableId} 的全部行。**只能从左往右遍历。** */
        LEFT
    }

    public TableJoin {
        if (leftTableId == null || leftTableId.isBlank()
                || rightTableId == null || rightTableId.isBlank()) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "表关联 %d 缺少左表或右表".formatted(id));
        }
        if (leftTableId.equals(rightTableId)) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "表关联 %d 的左右表相同：%s".formatted(id, leftTableId));
        }
        if (leftColumn == null || leftColumn.isBlank()
                || rightColumn == null || rightColumn.isBlank()) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "表关联 %d 缺少连接列".formatted(id));
        }
        type = type == null ? JoinType.INNER : type;
    }

    /**
     * 从 JSON 解析，并归一化方向。
     *
     * <p>{@code RIGHT JOIN} 会被转成交换操作数后的 {@code LEFT JOIN}。
     */
    public static TableJoin from(JsonNode n) {
        String left = text(n, "left");
        String right = text(n, "right");
        String leftCol = text(n, "leftCol");
        String rightCol = text(n, "rightCol");
        JoinType type = parseType(text(n, "type"));

        if (type == JoinType.LEFT && isRightJoin(text(n, "type"))) {
            // RIGHT JOIN a b ≡ LEFT JOIN b a
            return new TableJoin(n.path("id").asLong(0L), right, left, rightCol, leftCol, JoinType.LEFT);
        }
        return new TableJoin(n.path("id").asLong(0L), left, right, leftCol, rightCol, type);
    }

    private static boolean isRightJoin(String raw) {
        return raw != null && raw.toUpperCase(Locale.ROOT).contains("RIGHT");
    }

    /**
     * 解析关联类型。
     *
     * <p>未知类型抛异常而不是回落 INNER——两者行数不同。
     * 例如 {@code FULL OUTER JOIN} 在 MySQL 上根本不支持，静默当成 INNER 会给出错误结果。
     */
    private static JoinType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return JoinType.INNER;
        }
        String t = raw.trim().toUpperCase(Locale.ROOT).replace("_", " ");
        if (t.contains("RIGHT")) {
            return JoinType.LEFT;   // 归一化时会交换操作数
        }
        if (t.contains("LEFT")) {
            return JoinType.LEFT;
        }
        if (t.contains("INNER") || t.equals("JOIN") || t.contains("CROSS")) {
            return JoinType.INNER;
        }
        throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                "不支持的关联类型：" + raw + "。仅支持 INNER JOIN 与 LEFT/RIGHT JOIN");
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    /** 本关联是否连接了指定的两张表（忽略方向）。 */
    public boolean connects(String tableIdA, String tableIdB) {
        return (leftTableId.equals(tableIdA) && rightTableId.equals(tableIdB))
                || (leftTableId.equals(tableIdB) && rightTableId.equals(tableIdA));
    }
}

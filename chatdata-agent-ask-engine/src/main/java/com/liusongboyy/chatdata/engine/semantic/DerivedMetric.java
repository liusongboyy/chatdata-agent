package com.liusongboyy.chatdata.engine.semantic;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/**
 * 派生指标：由**别的指标**算出来的指标，如「赔付率」「平均解决时长」。
 *
 * <h2>为什么定义在语义层，而不是让模型写算式</h2>
 *
 * <p>「赔付率」的分子分母都有业务定义。让模型每次自己拼 {@code SUM(a)/SUM(b)}，
 * 拼错了不会报错，只是数字不同——正是 PRD §12 说的静默语义错误。
 * 而且一旦 DSL 允许任意算式，就等于把 SQL 表达式重新交给模型，
 * 「模型不写 SQL」这条防线随之失效。
 *
 * <p>所以：**模型只引用名字，不做算术**。{@code {"metrics":["赔付率"]}}
 * 与引用普通指标完全一样，DSL 结构不用改。
 *
 * <h2>只做三种</h2>
 *
 * <p>比值 / 差值 / 时长。不开放任意表达式（如 {@code A*B+C}）——
 * 那会重新变成"把 SQL 交给模型"，只是换了个位置。
 *
 * @param kind  类型
 * @param left  比值/差值的左操作数：**域内已注册列的别名**
 * @param right 比值/差值的右操作数
 * @param start 时长的起始时间列别名
 * @param end   时长的结束时间列别名
 * @param unit  时长单位：SECOND / MINUTE / HOUR / DAY
 */
public record DerivedMetric(Kind kind, String left, String right,
                           String start, String end, String unit) {

    public enum Kind {
        /** A / B。分母为 0 时返回 NULL，不报错。 */
        RATIO,
        /** A - B。 */
        DIFF,
        /** 两个时间列之差，按单位取平均。 */
        DURATION;

        static Kind parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                        "派生指标缺少 kind（ratio / diff / duration）");
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "ratio", "比值" -> RATIO;
                case "diff", "差值" -> DIFF;
                case "duration", "时长" -> DURATION;
                default -> throw new SemanticException(
                        SemanticException.Reason.INVALID_TABLE_DEFINITION,
                        "派生指标的 kind 非法：" + raw + "（只支持 ratio / diff / duration）");
            };
        }
    }

    public DerivedMetric {
        if (kind == null) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "派生指标缺少 kind");
        }
        if (kind == Kind.DURATION) {
            if (blank(start) || blank(end)) {
                throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                        "时长类派生指标必须同时给出 start 与 end 两个时间列");
            }
            // 不认识的单位**拒绝**而不是回落：回落成 HOUR 会让"按天看"静默变成"按小时看"
            unit = normalizeUnit(unit);
        } else {
            if (blank(left) || blank(right)) {
                throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                        "比值/差值类派生指标必须同时给出 left 与 right");
            }
            unit = null;
        }
    }

    /** 从 {@code columns_json} 的一项里的 {@code derived} 块解析。 */
    public static DerivedMetric from(JsonNode n) {
        return new DerivedMetric(
                Kind.parse(text(n, "kind")),
                text(n, "left"),
                text(n, "right"),
                text(n, "start"),
                text(n, "end"),
                text(n, "unit"));
    }

    private static String normalizeUnit(String raw) {
        if (raw == null || raw.isBlank()) {
            return "HOUR";
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        return switch (u) {
            case "SECOND", "MINUTE", "HOUR", "DAY" -> u;
            default -> throw new SemanticException(
                    SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "时长单位非法：" + raw + "（只支持 SECOND / MINUTE / HOUR / DAY）");
        };
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}

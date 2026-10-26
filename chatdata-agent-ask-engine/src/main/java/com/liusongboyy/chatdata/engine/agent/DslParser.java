package com.liusongboyy.chatdata.engine.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liusongboyy.chatdata.engine.nlsql.AskDsl;

import java.util.ArrayList;
import java.util.List;

/**
 * 把模型返回的 JSON 解析成 {@link AskDsl}。
 *
 * <h2>为什么不用 Jackson 直接反序列化到 record</h2>
 *
 * <p>Jackson 遇到未知键会失败或忽略，遇到类型不符会抛它自己的异常——
 * 那些错误信息（{@code Unrecognized field "foo"}）对定位问题没什么帮助，
 * 而这里的输入**来自模型**，格式不合预期是常态而非异常。
 *
 * <p>所以逐字段手工取值：容错点显式、错误信息可读、且能顺手做语义校验。
 *
 * <h2>解析宽松，校验严格</h2>
 *
 * <p>宽松的部分：字段缺失、类型写成字符串而非数组、值是 null——都接受并归一化。
 * 模型输出这些小偏差很常见，为它们失败不划算。
 *
 * <p>严格的部分：**非法取值不接受**，如不认识的运算符。那类问题放行会拼出语法错误的 SQL。
 */
public final class DslParser {

    private static final ObjectMapper M = new ObjectMapper();

    private DslParser() {
    }

    public static AskDsl parse(String json) {
        if (json == null || json.isBlank()) {
            throw new DslGenerationException("模型返回为空");
        }

        JsonNode root;
        try {
            root = M.readTree(extractJson(json));
        } catch (Exception e) {
            // 只取 Jackson 消息的**第一行**：它的消息体里会内嵌一段原文
            // （"...at [Source: (String)"xxxxx…"; line: 1, column: 5001]"），
            // 那一段可能几百字，直接拼进来会把日志灌满
            throw new DslGenerationException(
                    "模型返回的内容不是合法 JSON：" + truncate(json)
                            + "（" + truncate(firstLine(e.getMessage())) + "）");
        }

        // granularity **必须显式带出来**。这里曾经漏掉它，于是模型填了 month/year
        // 也被丢掉，AskDsl 构造器兜底成 DAY，渲染器按天分组——
        // 用户问「每年的保费」拿到几百行逐日数据，数字都对但没有一个是他要的。
        // 那条链路上每一环单独看都没问题，只有串起来才暴露。
        try {
            return new AskDsl(
                    stringList(root.path("metrics")),
                    stringList(root.path("dimensions")),
                    timeRange(root.path("timeRange")),
                    filters(root.path("filters")),
                    AskDsl.Granularity.parse(root.path("granularity").asText(null)),
                    sort(root.path("sort")),
                    limit(root.path("limit")),
                    stringList(root.path("shareOf")),
                    AskDsl.Compare.parse(root.path("compare").asText(null)));
        } catch (IllegalArgumentException e) {
            // AskDsl 的构造期校验（排序方向、limit、compare 取值）抛的是
            // IllegalArgumentException；对上层统一成"模型给的 DSL 不合法"，
            // 才能走重问那条路径，而不是当成系统故障报 500
            throw new DslGenerationException("模型返回的 DSL 不合法：" + e.getMessage());
        }
    }

    /**
     * 排序。
     *
     * <p>容忍三种写法：对象数组（约定形态）、字符串数组（{@code "保费 desc"}）、
     * 单个字符串。方向缺省由 {@link AskDsl.Sort} 兜底成 desc。
     */
    private static List<AskDsl.Sort> sort(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        List<AskDsl.Sort> out = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode n : node) {
                if (n.isObject()) {
                    String field = textOrNull(n.path("field"));
                    if (field == null) {
                        field = textOrNull(n.path("by"));
                    }
                    if (field != null) {
                        out.add(new AskDsl.Sort(field, textOrNull(n.path("direction"))));
                    }
                } else if (n.isTextual()) {
                    AskDsl.Sort s = parseInlineSort(n.asText());
                    if (s != null) {
                        out.add(s);
                    }
                }
            }
            return out;
        }
        if (node.isTextual()) {
            AskDsl.Sort s = parseInlineSort(node.asText());
            return s == null ? List.of() : List.of(s);
        }
        return List.of();
    }

    /** 解析「保费 desc」/「保费」这种内联写法。 */
    private static AskDsl.Sort parseInlineSort(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().split("\\s+");
        if (parts.length == 1) {
            return new AskDsl.Sort(parts[0], null);
        }
        return new AskDsl.Sort(parts[0], parts[1]);
    }

    /**
     * 取前 N 行。
     *
     * <p>模型中把数字写成字符串很常见（{@code "limit": "5"}），故按文本解析。
     * 解析不出来就当作没填——限制缺失只是行数多，方向缺省才是语义错误。
     */
    private static Integer limit(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            int n = Integer.parseInt(node.asText().trim());
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 从模型输出里取出 JSON 主体。
     *
     * <p>即便要求"只输出 JSON"，模型仍可能套一层 markdown 代码块——
     * 这是很常见的输出习惯，为它失败不值得。
     */
    private static String extractJson(String raw) {
        if (raw == null) {
            throw new DslGenerationException("模型返回为空");
        }
        String s = raw.trim();

        int fence = s.indexOf("```");
        if (fence >= 0) {
            int start = s.indexOf('\n', fence);
            int end = s.lastIndexOf("```");
            if (start > 0 && end > start) {
                s = s.substring(start + 1, end).trim();
            }
        }

        int objStart = s.indexOf('{');
        int objEnd = s.lastIndexOf('}');
        if (objStart >= 0 && objEnd > objStart) {
            s = s.substring(objStart, objEnd + 1);
        }
        return s;
    }

    /**
     * 取字符串数组。
     *
     * <p>容忍三种形态：正常数组、单个字符串、null。
     * 模型把数组写成裸字符串（{@code "metrics": "保费"}）并不罕见。
     */
    private static List<String> stringList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<String> out = new ArrayList<>(node.size());
            node.forEach(n -> {
                if (!n.isNull()) {
                    String v = n.asText();
                    if (v != null && !v.isBlank()) {
                        out.add(v.trim());
                    }
                }
            });
            return out;
        }
        String single = node.asText();
        return (single == null || single.isBlank()) ? List.of() : List.of(single.trim());
    }

    private static AskDsl.TimeRange timeRange(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        // 模型可能把它写成一个字符串（如 "上个月"），也接受
        if (node.isTextual()) {
            String v = node.asText();
            return (v == null || v.isBlank()) ? null : new AskDsl.TimeRange(v.trim(), null, null);
        }
        String kind = textOrNull(node.path("kind"));
        String start = textOrNull(node.path("start"));
        String end = textOrNull(node.path("end"));
        if (kind == null && start == null && end == null) {
            return null;
        }
        return new AskDsl.TimeRange(kind, start, end);
    }

    private static List<AskDsl.Filter> filters(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        List<AskDsl.Filter> out = new ArrayList<>();

        // 形态一：对象数组 [{field,op,value}] —— 手写 schema 约定的形态
        if (node.isArray()) {
            for (JsonNode n : node) {
                if (n.isTextual()) {
                    // 形态二：字符串数组 ["区域 = 华东"]
                    AskDsl.Filter f = parseInlineFilter(n.asText());
                    if (f != null) {
                        out.add(f);
                    }
                } else if (n.isObject()) {
                    String field = textOrNull(n.path("field"));
                    if (field == null || field.isBlank()) {
                        continue;   // 没有字段名的筛选条件无法使用，跳过而不是报错
                    }
                    out.add(new AskDsl.Filter(field,
                            defaultOp(textOrNull(n.path("op"))),
                            textOrNull(n.path("value"))));
                }
            }
            return out;
        }

        // 形态三：扁平 map {"区域": "华东"}
        if (node.isObject()) {
            node.fields().forEachRemaining(e ->
                    out.add(new AskDsl.Filter(e.getKey(), "=", e.getValue().asText())));
            return out;
        }
        return List.of();
    }

    /** 解析「字段 = 取值」这种内联写法。认不出来返回 null，由调用方跳过。 */
    private static AskDsl.Filter parseInlineFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] ops = {"!=", ">=", "<=", "=", ">", "<"};
        for (String op : ops) {
            int i = raw.indexOf(op);
            if (i > 0) {
                String field = raw.substring(0, i).trim();
                String value = raw.substring(i + op.length()).trim();
                if (!field.isEmpty()) {
                    return new AskDsl.Filter(field, op, value);
                }
            }
        }
        return null;
    }

    private static String defaultOp(String op) {
        return (op == null || op.isBlank()) ? "=" : op;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String v = node.asText();
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /**
     * 截断过长的输出再放进错误信息。
     *
     * <p>模型可能返回几千字的解释文字。原样带进异常消息会灌满日志，
     * 而且**没有任何诊断价值**——真正有用的是开头那段（能看出它想说什么）。
     */
    private static String truncate(String s) {
        if (s == null) {
            return "<null>";
        }
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }

    /** 取多行消息的第一行。Jackson 的解析错误消息后面跟着一整段原文引用。 */
    private static String firstLine(String s) {
        if (s == null) {
            return "<null>";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}

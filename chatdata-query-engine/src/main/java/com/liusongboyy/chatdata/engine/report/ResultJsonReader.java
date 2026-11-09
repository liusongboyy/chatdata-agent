package com.liusongboyy.chatdata.engine.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liusongboyy.chatdata.engine.chat.ChartSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@code ask_message.result_json} 读成 {@link ReportSource}。
 *
 * <h2>为什么需要这一层</h2>
 *
 * <p>{@code result_json} 是 {@code AskEngineStreamController.resultPayload()} 产出的
 * Map 序列化结果——它的形状是**为前端渲染设计的**，不是为报告设计的。
 * 两者要的东西不一样：
 *
 * <ul>
 *   <li>前端要 {@code understanding} 做「我理解成了」回显</li>
 *   <li>报告要 {@code scope} 做口径冲突检测</li>
 * </ul>
 *
 * <p>所以中间要转一道。放在引擎层是因为**转换规则属于领域知识**，
 * 而不是 HTTP 层的事。
 *
 * <h2>解析失败时的方向</h2>
 *
 * <p>返回 {@code null}，让调用方跳过这一轮——**不抛异常**。
 * 一份历史记录格式坏了不该让整份报告生成失败；而用户看到"少了一轮"
 * 比看到"生成失败"更有用（他能自己判断要不要补）。
 */
public final class ResultJsonReader {

    private static final ObjectMapper M = new ObjectMapper();

    private ResultJsonReader() {
    }

    /**
     * 解析一条结果。
     *
     * @param messageId  消息 id
     * @param question   该轮的问题
     * @param resultJson {@code result_json} 原文；null 或空表示这条不是成功结果
     * @return 解析结果；**格式不认得时返回 null**
     */
    public static ReportSource read(String messageId, String question, String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = M.readTree(resultJson);
            if (root == null || !root.isObject()) {
                return null;
            }

            JsonNode analysisNode = root.get("analysis");
            ReportSource.AnalysisPayload analysis =
                    analysisNode != null && analysisNode.isObject()
                            ? readAnalysis(analysisNode)
                            : null;

            return new ReportSource(
                    messageId,
                    question,
                    analysis,
                    stringList(root.get("columns")),
                    readRows(root.get("rows")),
                    readChart(root.get("chart"), stringList(root.get("columns"))),
                    text(root.get("sqlDisplay")),
                    readScope(root.get("understanding")));
        } catch (com.fasterxml.jackson.core.JacksonException | RuntimeException e) {
            // 坏数据：跳过这一轮。上层会把它排除在报告外，
            // 而用户看到的是"少了一轮"，不是"生成失败"。
            //
            // JacksonException 是受检的（readTree 声明抛出），必须显式接住——
            // 不接的话这个"跳过坏数据"的设计就落不了地
            return null;
        }
    }

    /**
     * 解析分析部分。
     *
     * <p>{@code steps} 里的每一步在 {@code result_json} 里是**平铺的**：
     * {@code label} / {@code purpose} / {@code columns} / {@code rows} / {@code chart}
     * / {@code sqlDisplay} / {@code dsl} 全在同一层，而不是嵌套在一个 {@code result} 里。
     * 这是实测确认过的（见 {@code ask_message.result_json} 样例）。
     */
    private static ReportSource.AnalysisPayload readAnalysis(JsonNode a) {
        List<StepSnapshot> steps = new ArrayList<>();
        JsonNode stepsNode = a.get("steps");
        if (stepsNode != null && stepsNode.isArray()) {
            for (JsonNode s : stepsNode) {
                // 失败的步骤不进报告：它没有数据，放进去只会让读者困惑
                if (!"OK".equals(text(s.get("status")))) {
                    continue;
                }
                steps.add(new StepSnapshot(
                        text(s.get("label")),
                        text(s.get("purpose")),
                        stringList(s.get("columns")),
                        readRows(s.get("rows")),
                        // **chart 可能不存在。** 实测：2026-10-26 存的消息里
                        // steps 没有 chart 字段（那时还没加），而现在的代码是有的。
                        // 报告要能读历史数据，所以必须降级
                        readChart(s.get("chart"), stringList(s.get("columns"))),
                        text(s.get("sqlDisplay")),
                        readScope(s.get("dsl")),
                        intOf(s.get("rowCount"))));
            }
        }

        List<FindingView> findings = new ArrayList<>();
        JsonNode fs = a.get("findings");
        if (fs != null && fs.isArray()) {
            for (JsonNode f : fs) {
                findings.add(new FindingView(
                        text(f.get("kind")),
                        text(f.get("text")),
                        readMap(f.get("evidence"))));
            }
        }

        return new ReportSource.AnalysisPayload(
                text(a.get("intent")), steps, findings, text(a.get("narrative")));
    }

    /**
     * 读口径。
     *
     * <p>两个来源：单查询在 {@code understanding} 里，分析步骤在 {@code dsl} 里。
     * 两者的字段名不同（{@code understanding.time} 是对象，{@code dsl.timeRange} 也是），
     * 但语义一样，所以统一处理。
     */
    private static ReportScope readScope(JsonNode n) {
        if (n == null || !n.isObject()) {
            return new ReportScope(List.of(), List.of(), null, List.of());
        }
        // 单查询：understanding.time 是 {kind: "今年", start, end}
        // 分析步骤：dsl.timeRange 同形
        JsonNode timeNode = n.has("time") ? n.get("time") : n.get("timeRange");
        String time = null;
        if (timeNode != null && timeNode.isObject()) {
            time = text(timeNode.get("kind"));
            if (time == null) {
                // 没 kind 但有起止日期：用日期描述
                String start = text(timeNode.get("start"));
                String end = text(timeNode.get("end"));
                if (start != null || end != null) {
                    time = (start == null ? "…" : start) + " ~ " + (end == null ? "…" : end);
                }
            }
        }

        return new ReportScope(
                stringList(n.get("metrics")),
                stringList(n.get("dimensions")),
                time,
                readFilters(n.get("filters")));
    }

    /**
     * 筛选条件。
     *
     * <p>{@code understanding.filters} 与 {@code dsl.filters} 都是数组，
     * 但元素可能是对象（{@code {field, op, value}}）也可能是字符串。
     * 统一转成「字段 运算符 值」的字符串——报告里只需要人能读的形式。
     */
    private static List<String> readFilters(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n == null || !n.isArray()) {
            return out;
        }
        for (JsonNode f : n) {
            if (f.isTextual()) {
                out.add(f.asText());
                continue;
            }
            String field = text(f.get("field"));
            String op = text(f.get("op"));
            JsonNode value = f.get("value");
            if (field == null) {
                continue;
            }
            String v = value == null ? "" : (value.isTextual() ? value.asText() : value.toString());
            out.add(op == null ? field + " = " + v : field + " " + op + " " + v);
        }
        return out;
    }

    /**
     * 读图表规格。
     *
     * <h2>没有 chart 时按列推断</h2>
     *
     * <p>老数据里 {@code steps} 可能没有 {@code chart} 字段（实测：2026-10-26 的消息）。
     * 直接给 {@code TABLE} 会让报告里所有分析步骤都是表格——读者看不出趋势。
     *
     * <p>推断规则保守：**两列且第一列是文本、第二列是数字**就画柱状图，
     * 否则表格。不做更聪明的判断（比如"第一列像日期就用折线"）——
     * 猜错了的图比没有图更误导。
     *
     * @param n        chart 节点，可为 null
     * @param columns  结果列名，推断用
     */
    private static ChartSpec readChart(JsonNode n, List<String> columns) {
        if (n == null || !n.isObject()) {
            return inferChart(columns);
        }
        try {
            ChartSpec.Form form = ChartSpec.Form.valueOf(
                    text(n.get("form")) == null ? "TABLE" : text(n.get("form")));
            return new ChartSpec(form,
                    text(n.get("categoryField")),
                    stringList(n.get("valueFields")),
                    text(n.get("seriesField")),
                    text(n.get("reason")));
        } catch (IllegalArgumentException e) {
            // 认不出的图表形态：退成表格。图表是锦上添花，不该让整轮失败
            return ChartSpec.table("图表形态无法识别");
        }
    }

    /**
     * 按列推断一个保守的图表形态。
     *
     * <h2>规则</h2>
     *
     * <p>真实的分析步骤很少是两列——实测的 4 步是 3~4 列：
     *
     * <pre>
     *   [生效日期, 保费, 保单数]        ← 时间 + 两个指标
     *   [区域, 生效日期, 保费]          ← 分类 + 时间 + 一个指标
     *   [状态, 生效日期, 保费, 保单数]
     * </pre>
     *
     * <p>所以规则是：**第一个非数值列当分类轴，最后一个数值列当值**。
     * 中间那些列（时间、次要指标）在图上放不下，但**不影响读者看出量级与分布**——
     * 而那就是老数据报告里最需要的信息。
     *
     * <h2>为什么不做更聪明的判断</h2>
     *
     * <p>「第一列像日期就用折线」这类启发式，猜错时给出的是**误导性的图**，
     * 比一张诚实的表格糟得多。柱状图对「分类 + 数值」永远成立。
     */
    private static ChartSpec inferChart(List<String> columns) {
        if (columns.size() < 2) {
            return ChartSpec.table("老数据没有图表规格，且只有一列");
        }
        // 第一列当分类轴：分析结果的第一列几乎总是分组维度
        String category = columns.get(0);
        // 最后一列当值：分析结果的最后一个指标通常是要展示的那个
        String value = columns.get(columns.size() - 1);
        return new ChartSpec(ChartSpec.Form.BAR, category, List.of(value), null,
                "老数据没有图表规格，按「首列分类 + 末列数值」推断为柱状图");
    }

    private static List<List<Object>> readRows(JsonNode n) {
        List<List<Object>> out = new ArrayList<>();
        if (n == null || !n.isArray()) {
            return out;
        }
        for (JsonNode row : n) {
            if (!row.isArray()) {
                continue;
            }
            List<Object> cells = new ArrayList<>();
            for (JsonNode cell : row) {
                cells.add(toJava(cell));
            }
            out.add(cells);
        }
        return out;
    }

    /** JSON 标量 → Java 值。对象/数组保留成字符串——报告里不需要嵌套结构。 */
    private static Object toJava(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isTextual()) {
            return n.asText();
        }
        if (n.isIntegralNumber()) {
            return n.asLong();
        }
        if (n.isFloatingPointNumber()) {
            return n.asDouble();
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        return n.toString();
    }

    private static List<String> stringList(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n == null || !n.isArray()) {
            return out;
        }
        for (JsonNode e : n) {
            out.add(e.asText());
        }
        return out;
    }

    private static java.util.Map<String, Object> readMap(JsonNode n) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (n == null || !n.isObject()) {
            return out;
        }
        n.properties().forEach(e -> out.put(e.getKey(), toJava(e.getValue())));
        return out;
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    private static int intOf(JsonNode n) {
        return n == null || n.isNull() ? 0 : n.asInt(0);
    }
}

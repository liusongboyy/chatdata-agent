package com.liusongboyy.chatdata.engine.nlsql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 问数 DSL 的 JSON Schema —— <strong>手写，不反射生成。</strong>
 *
 * <h2>为什么必须手写（这是本项目最容易踩的坑）</h2>
 *
 * <p>agentscope 提供 {@code agent.call(msgs, AskDsl.class, ctx)}，看起来更方便：
 * 直接把 Java 类型交给它，schema 自动生成、结果自动反序列化。**但不能用。**
 *
 * <p>P0.5 spike 实测（见 {@code .scratch/self-built-chatbi/spike-p0-results.md}）：
 * 走 {@code call(msgs, Class<?>, ctx)} 时，**{@code filters} 字段被系统性丢弃**——
 * 问题「上个月<strong>华东</strong>的<strong>有效</strong>保单保费合计」翻译出的 DSL 里，
 * 两个核心约束凭空消失。解析成功、无异常，直接渲染 SQL 会退化成「全量保费合计」，
 * <strong>答案错误且全程静默</strong>。
 *
 * <p>按 schema 来源重新分类 23 次调用后，分离得干干净净：
 *
 * <table>
 *   <tr><th>schema 来源</th><th>结果</th></tr>
 *   <tr><td>Java record 反射生成</td><td><b>✗ 10/10 全丢 filters</b></td></tr>
 *   <tr><td>手写 JSON Schema</td><td><b>✓ 13/13 全对</b></td></tr>
 * </table>
 *
 * <p>关键对照：同一条代码路径（{@code call(msgs, JsonNode, ctx)}）、同模型、
 * 同 temperature、同 maxTokens，唯一差别是 schema 内容。
 *
 * <p>生成的 schema **结构上是合法的**（{@code filters} 定义完整、类型正确），
 * 所以问题不在结构而在内容的某个特征上——最可能是生成器给每个字段都加了
 * 否定式指令（「不要臆造」「不要默认填」），或字段被排成字母序导致 {@code filters} 位置不佳。
 * **具体是哪个特征未定位到**，但换用手写 schema 即可稳定工作。
 *
 * <p><strong>代价：schema 与本文件的 Java 类型可能漂移。</strong>
 * 由 {@code AskDslSchemaTest} 断言两者一致来兜住。
 */
public final class AskDslSchema {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AskDslSchema() {
    }

    /**
     * 返回 DSL 的 JSON Schema。
     *
     * <p>字段顺序**刻意按语义排列**（指标 → 维度 → 时间 → 筛选），不排字母序。
     * 每次调用返回新实例，调用方可安全修改。
     */
    public static JsonNode jsonSchema() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");

        ObjectNode props = root.putObject("properties");

        props.set("metrics", stringArray(
                "指标：用户想看的度量。必须是语义域中已注册的指标别名，如「保费」「保单数」。至少一个。"));

        props.set("dimensions", stringArray(
                "维度：分组依据，如「区域」「月份」「渠道」。用户没有要求分组时返回空数组。"));

        ObjectNode timeRange = props.putObject("timeRange");
        timeRange.put("type", "object");
        timeRange.put("description", "时间范围。用户未提及时间时整体省略。");
        ObjectNode trProps = timeRange.putObject("properties");
        trProps.set("kind", string("时间口径名称，如「上个月」「本月」「今年」"));
        trProps.set("start", string("起始日期，格式 yyyy-MM-dd；无法确定时省略"));
        trProps.set("end", string("结束日期，格式 yyyy-MM-dd；无法确定时省略"));

        ObjectNode filters = props.putObject("filters");
        filters.put("type", "array");
        filters.put("description", "筛选条件。问题中出现的每一个限定词（地区、状态、渠道等）都必须在这里体现，不能省略。");
        ObjectNode filterItem = filters.putObject("items");
        filterItem.put("type", "object");
        ObjectNode fiProps = filterItem.putObject("properties");
        fiProps.set("field", string("字段名，必须是语义域中已注册的字段"));
        fiProps.set("op", string("运算符：= / != / > / < / in / like。"
                + "必须用这些符号或英文单词，不要写中文（如「在」「等于」）"));
        fiProps.set("value", string("比较值"));
        ArrayNode fiRequired = filterItem.putArray("required");
        fiRequired.add("field").add("op").add("value");

        // 时间粒度：**不放进 required**。
        // 它只在"用户明确说了按月/按年"时才有值；强制必填会诱导模型
        // 给每个问题都编一个粒度，那是错的
        ObjectNode gran = props.putObject("granularity");
        gran.put("type", "string");
        gran.put("description",
                "时间维度的粒度。用户明确说「按月」「按年」时才填 month / year；"
                        + "没提就不要填，或填 day。只在维度里含日期字段时有意义。");
        gran.putArray("enum").add("day").add("month").add("year");

        // 显式声明 required：spike 实测模型会直接省略未声明为必需的字段
        ArrayNode required = root.putArray("required");
        required.add("metrics").add("dimensions").add("filters");

        return root;
    }

    private static ObjectNode stringArray(String description) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("type", "array");
        n.put("description", description);
        n.putObject("items").put("type", "string");
        return n;
    }

    private static ObjectNode string(String description) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("type", "string");
        n.put("description", description);
        return n;
    }
}

package com.liusongboyy.chatdata.engine.nlsql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 兜住「手写 schema 与 Java 类型漂移」这个已知代价。
 *
 * <p>为什么需要这个测试：{@link AskDslSchema} 刻意不走反射生成（见该类的说明——
 * 反射生成的 schema 会导致 filters 被系统性丢弃）。代价是 schema 与
 * {@link AskDsl} 之间没有编译期约束，改了 record 忘了改 schema 不会有任何报错，
 * 只会表现为「模型输出少了某个字段」，而且**静默**。
 *
 * <p>本测试把两者钉在一起。
 */
class AskDslSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("schema 的顶层字段与 AskDsl 的 record 组件逐一对应（不多不少）")
    void schemaPropertiesMatchRecordComponents() {
        Set<String> schemaFields = propertyNames(AskDslSchema.jsonSchema());
        Set<String> recordFields = Arrays.stream(AskDsl.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(schemaFields)
                .as("schema 与 AskDsl 的字段必须完全一致；不一致说明改了 record 没同步 schema，或反之")
                .isEqualTo(recordFields);
    }

    @Test
    @DisplayName("timeRange 是对象，其字段与 TimeRange record 对应")
    void timeRangeShapeMatches() {
        JsonNode timeRange = AskDslSchema.jsonSchema().path("properties").path("timeRange");

        assertThat(timeRange.path("type").asText()).isEqualTo("object");
        assertThat(propertyNames(timeRange)).isEqualTo(recordFieldsOf(AskDsl.TimeRange.class));
    }

    @Test
    @DisplayName("filters 是对象数组，元素字段与 Filter record 对应")
    void filterShapeMatches() {
        JsonNode filters = AskDslSchema.jsonSchema().path("properties").path("filters");

        assertThat(filters.path("type").asText()).isEqualTo("array");
        JsonNode items = filters.path("items");
        assertThat(items.path("type").asText()).isEqualTo("object");
        assertThat(propertyNames(items)).isEqualTo(recordFieldsOf(AskDsl.Filter.class));
    }

    @Test
    @DisplayName("required 精确等于 {metrics, dimensions, filters} —— timeRange 刻意排除")
    void requiredSetIsExact() {
        JsonNode schema = AskDslSchema.jsonSchema();
        Set<String> required = toSet(schema.path("required"));

        // 刻意断言**精确集合**而非「包含」：将来增删 required 必须让这个测试红，
        // 从而强制过一次有意识的决策，而不是悄悄改掉。
        assertThat(required)
                .as("spike 实测：未声明 required 的字段会被模型整段省略，故这三个必须声明")
                .containsExactlyInAnyOrder("metrics", "dimensions", "filters");

        assertThat(required)
                .as("""
                        timeRange 刻意不进 required：它是真正可空的（用户未提及时间时本就没有）。
                        强制它必填会诱导模型为了满足 schema 而**编造**时间范围，
                        那与「遗漏时间」同样会导致静默的错答案，且更难发现。""")
                .doesNotContain("timeRange");
    }

    @Test
    @DisplayName("Filter 的 field / op / value 声明为 required，rightField 刻意不声明")
    void filterFieldsAreRequired() {
        JsonNode items = AskDslSchema.jsonSchema().path("properties").path("filters").path("items");

        assertThat(toSet(items.path("required")))
                .as("""
                        rightField 不进 required：绝大多数筛选是"列 运算符 常量"，
                        强制必填会让模型给每个条件都编一个右侧字段。
                        代价是它可能和 value 同时出现——解析时以 rightField 为准。""")
                .containsExactlyInAnyOrder("field", "op", "value");
        assertThat(propertyNames(items)).contains("rightField");
    }

    @Test
    @DisplayName("每个字段都带 description —— 它是喂给模型的字段说明，缺了模型就靠猜")
    void everyPropertyHasDescription() {
        JsonNode schema = AskDslSchema.jsonSchema();
        for (String field : propertyNames(schema)) {
            JsonNode prop = schema.path("properties").path(field);
            assertThat(prop.path("description").asText())
                    .as("字段 %s 缺少 description", field)
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("返回的是新实例，调用方修改不会污染后续调用")
    void returnsFreshInstance() {
        JsonNode first = AskDslSchema.jsonSchema();
        ((com.fasterxml.jackson.databind.node.ObjectNode) first).put("type", "tampered");

        assertThat(AskDslSchema.jsonSchema().path("type").asText()).isEqualTo("object");
    }

    @Test
    @DisplayName("schema 能被序列化（它是直接发给模型网关的）")
    void schemaIsSerializable() throws Exception {
        String json = MAPPER.writeValueAsString(AskDslSchema.jsonSchema());

        assertThat(json).contains("\"metrics\"").contains("\"filters\"").contains("\"required\"");
        // 能被解析回来，说明是合法 JSON
        assertThat(MAPPER.readTree(json).path("type").asText()).isEqualTo("object");
    }

    // ── helpers ──

    private static Set<String> propertyNames(JsonNode schema) {
        return toSetOfFieldNames(schema.path("properties"));
    }

    private static Set<String> toSetOfFieldNames(JsonNode propertiesNode) {
        Set<String> names = new TreeSet<>();
        propertiesNode.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<String> toSet(JsonNode arrayNode) {
        Set<String> out = new TreeSet<>();
        arrayNode.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static Set<String> recordFieldsOf(Class<?> recordClass) {
        return Arrays.stream(recordClass.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** 供 IDE 提示用，避免误删 List 导入。 */
    @SuppressWarnings("unused")
    private static final List<String> DSL_FIELDS = List.of("metrics", "dimensions", "timeRange", "filters");
}

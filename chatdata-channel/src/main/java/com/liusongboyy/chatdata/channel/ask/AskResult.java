package com.liusongboyy.chatdata.channel.ask;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 问数结果（渠道侧的精简模型）。
 *
 * <p>只保留排版卡片需要的字段，<b>不照搬 ask 的完整响应</b>。
 * 照搬会让 channel 与 ask 的响应结构强耦合——ask 加一个字段，
 * channel 要跟着改（哪怕它根本不用）。
 *
 * @param columns    列名
 * @param rows       数据行，每行是一个与 columns 等长的列表
 * @param rowCount   总行数。可能大于 {@code rows.size()}（卡片只放前 N 行）
 * @param sql        实际执行的 SQL（含权限改写）。卡片里默认折叠
 * @param maskedColumns 被脱敏的列名。展示时要标注，否则用户会以为看到的是原值
 * @param conversationId ask 侧的会话 id，用于「查看完整结果」跳转与多轮
 * @param durationMs 端到端耗时
 */
public record AskResult(
        List<String> columns,
        List<List<String>> rows,
        int rowCount,
        String sql,
        List<String> maskedColumns,
        String conversationId,
        long durationMs
) {

    public static AskResult from(JsonNode node) {
        List<String> columns = new ArrayList<>();
        for (JsonNode c : node.path("columns")) {
            columns.add(c.asString());
        }

        List<List<String>> rows = new ArrayList<>();
        for (JsonNode row : node.path("rows")) {
            List<String> values = new ArrayList<>();
            for (JsonNode v : row) {
                values.add(v.isNull() ? "" : v.asString());
            }
            rows.add(values);
        }

        List<String> masked = new ArrayList<>();
        for (JsonNode m : node.path("maskedColumns")) {
            masked.add(m.asString());
        }

        return new AskResult(
                List.copyOf(columns),
                List.copyOf(rows),
                node.path("rowCount").asInt(rows.size()),
                node.path("sqlDisplay").asString(node.path("sql").asString("")),
                List.copyOf(masked),
                node.path("conversationId").asString(null),
                node.path("durationMs").asLong(0));
    }

    /** 结果是否为空。空结果要明确说「没有符合条件的数据」，而不是发一张空表格。 */
    public boolean empty() {
        return rowCount == 0 || rows.isEmpty();
    }

    /**
     * 是否是单值结果（一行一列）。
     *
     * <p>「订单金额合计是多少」这类问题的答案是单个数字，
     * 塞进表格反而不如大字展示直观。
     */
    public boolean singleValue() {
        return rows.size() == 1 && columns.size() == 1;
    }
}

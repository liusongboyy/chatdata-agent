package com.liusongboyy.chatdata.mesh.ask;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 问数结果（mesh 侧的精简模型）。
 *
 * <p>只保留组装 A2A artifact 需要的字段。<b>不照搬 ask 的完整响应</b>——
 * 照搬会让 mesh 与 ask 的响应结构强耦合，ask 加一个字段 mesh 就要跟着改，
 * 哪怕它根本不用。
 *
 * @param columns        列名
 * @param rows           数据行
 * @param rowCount       总行数。可能大于 {@code rows.size()}
 * @param sql            实际执行的 SQL（含权限改写）
 * @param maskedColumns  被脱敏的列名
 * @param answer         文字答复。直连模式与能力问题只有它、没有表格
 * @param analysis       深入分析结果；单查询为 null
 * @param domainId       实际落到的域。调用方据此判断"这次问的是哪类数据"
 * @param durationMs     端到端耗时
 */
public record AskResult(
        List<String> columns,
        List<List<String>> rows,
        int rowCount,
        String sql,
        List<String> maskedColumns,
        String answer,
        Analysis analysis,
        String domainId,
        long durationMs
) {

    public static AskResult from(JsonNode node) {
        List<String> columns = new ArrayList<>();
        for (JsonNode c : node.path("columns")) {
            columns.add(c.asString());
        }
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode row : node.path("rows")) {
            List<String> cells = new ArrayList<>();
            for (JsonNode v : row) {
                cells.add(v.isNull() ? "" : v.asString());
            }
            rows.add(List.copyOf(cells));
        }
        List<String> masked = new ArrayList<>();
        for (JsonNode m : node.path("maskedColumns")) {
            masked.add(m.asString());
        }
        return new AskResult(
                List.copyOf(columns),
                List.copyOf(rows),
                node.path("rowCount").asInt(rows.size()),
                node.path("sqlDisplay").asString(node.path("sql").asString(null)),
                List.copyOf(masked),
                blankToNull(node.path("answer").asString(null)),
                Analysis.from(node.path("analysis")),
                node.path("domainId").asString(null),
                node.path("durationMs").asLong(0));
    }

    /**
     * 有没有可给调用方的东西。
     *
     * <p><b>文字答复与分析结果也算"有"</b>——直连模式的答案就是那段文字，
     * 没有表格。只判 rows 为空的话，那类回答会被当成"查无数据"。
     */
    public boolean hasContent() {
        if (answer != null && !answer.isBlank()) {
            return true;
        }
        if (analysis != null) {
            return true;
        }
        return rowCount > 0 && !rows.isEmpty();
    }

    /** 是否是单值结果（一行一列）。调用方通常希望这类结果直接给标量。 */
    public boolean singleValue() {
        return rows.size() == 1 && columns.size() == 1;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    /**
     * 深入分析的结果。
     *
     * @param narrative  整体结论
     * @param findings   逐条发现
     * @param queryCount 跑了几次查询
     * @param notice     降级说明（分析失败回退单查询时非空）
     */
    public record Analysis(String narrative, List<String> findings, int queryCount,
                           String notice) {
        public static Analysis from(JsonNode node) {
            if (node == null || node.isNull() || node.isMissingNode()) {
                return null;
            }
            List<String> findings = new ArrayList<>();
            for (JsonNode f : node.path("findings")) {
                String t = f.path("text").asString("");
                if (!t.isBlank()) {
                    findings.add(t);
                }
            }
            return new Analysis(
                    blankToNull(node.path("narrative").asString(null)),
                    List.copyOf(findings),
                    node.path("queryCount").asInt(0),
                    blankToNull(node.path("notice").asString(null)));
        }
    }
}

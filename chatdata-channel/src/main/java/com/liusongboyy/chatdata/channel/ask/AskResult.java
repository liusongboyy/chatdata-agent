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
 * @param domainId   实际落到的数据域。反馈要带着它——反馈必须落在产生这条答案的域上，
 *                   否则审核通过后写入的范例会被别的域检索到
 * @param answer     **文字答复**。直连模式与能力问题只有它、没有表格——
 *                   忽略它会让这类回答被显示成「没有符合条件的数据」，那是错的
 * @param analysis   深入分析的结果；单查询为 null
 */
public record AskResult(
        List<String> columns,
        List<List<String>> rows,
        int rowCount,
        String sql,
        List<String> maskedColumns,
        String conversationId,
        long durationMs,
        String domainId,
        String answer,
        Analysis analysis
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
                node.path("durationMs").asLong(0),
                node.path("domainId").asString(null),
                blankToNull(node.path("answer").asString(null)),
                Analysis.from(node.path("analysis")));
    }

    /**
     * 结果是否为空。
     *
     * <p><b>有文字答复时不算空</b>——直连模式与能力问题的答案就是那段文字，
     * 没有表格。曾经只判 {@code rows.isEmpty()}，于是这类回答被显示成
     * 「没有符合条件的数据」，而用户明明得到了答案。实测踩过。
     */
    public boolean empty() {
        if (answer != null && !answer.isBlank()) {
            return false;
        }
        if (analysis != null) {
            return false;
        }
        return rowCount == 0 || rows.isEmpty();
    }

    /** 只有文字、没有表格。这时渲染要走另一条路，不能画空表。 */
    public boolean textOnly() {
        return (rows == null || rows.isEmpty())
                && ((answer != null && !answer.isBlank()) || analysis != null);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    /**
     * 深入分析的结果。
     *
     * <p>只取排版卡片用得上的：结论（narrative）+ 各条发现（findings）+ 用了几个查询。
     * 每一步的明细（SQL、行）不搬过来——那是排查用的，IM 里放不下也没人看。
     *
     * @param narrative 整体结论
     * @param findings  逐条发现，每条一句话
     * @param queryCount 这次分析跑了几次查询
     * @param notice    降级说明：分析失败回退单查询时，必须让用户知道
     * @param steps     每一步做了什么、查出了什么
     */
    public record Analysis(String narrative, List<String> findings, int queryCount,
                           String notice, List<Step> steps) {
        public static Analysis from(JsonNode node) {
            if (node == null || node.isNull() || node.isMissingNode()) {
                return null;
            }
            List<String> findings = new ArrayList<>();
            for (JsonNode f : node.path("findings")) {
                String text = f.path("text").asString("");
                if (!text.isBlank()) {
                    findings.add(text);
                }
            }
            List<Step> steps = new ArrayList<>();
            for (JsonNode st : node.path("steps")) {
                steps.add(Step.from(st));
            }
            return new Analysis(
                    blankToNull(node.path("narrative").asString(null)),
                    List.copyOf(findings),
                    node.path("queryCount").asInt(0),
                    blankToNull(node.path("notice").asString(null)),
                    List.copyOf(steps));
        }
    }

    /**
     * 分析里的一步。
     *
     * <p>只留展示需要的：这一步叫什么、想说明什么、查出几行、SQL 是什么。
     * 每步的完整数据不搬（三次查询的行拼起来卡片会很长）——需要细节的人看 SQL。
     *
     * @param label   这一步做了什么（如「按组织看交易金额」）
     * @param purpose 为什么做这一步
     * @param columns 结果列
     * @param rows    结果行（卡片里按需截断）
     * @param rowCount 行数
     * @param sql     这一步的 SQL
     * @param error   失败原因；成功时为 null
     */
    public record Step(String label, String purpose, List<String> columns,
                       List<List<String>> rows, int rowCount, String sql, String error) {
        public static Step from(JsonNode n) {
            List<String> columns = new ArrayList<>();
            for (JsonNode c : n.path("columns")) {
                columns.add(c.asString());
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode row : n.path("rows")) {
                List<String> cells = new ArrayList<>();
                for (JsonNode v : row) {
                    cells.add(v.isNull() ? "" : v.asString());
                }
                rows.add(List.copyOf(cells));
            }
            return new Step(
                    n.path("label").asString(""),
                    n.path("purpose").asString(""),
                    List.copyOf(columns),
                    List.copyOf(rows),
                    n.path("rowCount").asInt(rows.size()),
                    blankToNull(n.path("sqlDisplay").asString(n.path("sql").asString(null))),
                    blankToNull(n.path("error").asString(null)));
        }
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

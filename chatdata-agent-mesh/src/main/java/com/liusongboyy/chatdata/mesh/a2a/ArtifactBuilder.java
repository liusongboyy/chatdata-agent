package com.liusongboyy.chatdata.mesh.a2a;

import com.liusongboyy.chatdata.mesh.ask.AskResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把问数结果组装成 A2A 的响应内容。
 *
 * <h2>同时给 DataPart 与 TextPart</h2>
 *
 * <p>调用方有两类：能解析 JSON 的（要结构化数据），和只会读文本的（要摘要）。
 * 只给一种就必须让另一类自己去解析或自己去拼。
 *
 * <h2>不塞全量 rows</h2>
 *
 * <p>ask 最多返回 1000 行。塞进 artifact 会让消息很大，而 A2A 的消息有大小限制。
 * 这里给**前 N 行 + 总数**，真要全量让调用方按 {@code fullRowsHint} 说的去查。
 *
 * <p>为什么不全给：调用方通常只需要聚合结果或前几条。给全量的代价是
 * 每条消息几百 KB，而收益是"省一次往返"——那个交换不划算。
 */
@Component
public class ArtifactBuilder {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * DataPart 里最多放几行。
     *
     * <p>比 IM 卡片的 10 行多：这里是对机器，不是给人滚动查看。
     * 但也不能给全量——1000 行会让消息大两个数量级。
     */
    static final int MAX_ROWS_IN_DATA = 50;

    /**
     * 组装结果。
     *
     * @return 文本摘要。结构化数据由 {@link #dataJson} 单独给
     */
    public String textSummary(String question, AskResult result) {
        if (result.answer() != null && !result.answer().isBlank()) {
            return result.answer();
        }
        if (result.analysis() != null) {
            StringBuilder sb = new StringBuilder();
            AskResult.Analysis a = result.analysis();
            if (a.narrative() != null) {
                sb.append(a.narrative());
            }
            if (!a.findings().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append("发现：\n");
                for (String f : a.findings()) {
                    sb.append("· ").append(f).append('\n');
                }
            }
            if (a.queryCount() > 0) {
                sb.append("\n（共执行 ").append(a.queryCount()).append(" 次查询）");
            }
            if (a.notice() != null) {
                sb.append("\n注意：").append(a.notice());
            }
            return sb.toString().trim();
        }
        if (!result.hasContent()) {
            // 空结果要明说。给一个空数组会让调用方以为解析失败了
            return "没有符合条件的数据。";
        }
        if (result.singleValue()) {
            return result.columns().get(0) + "：" + result.rows().get(0).get(0);
        }
        return "共 " + result.rowCount() + " 行，"
                + result.columns().size() + " 列（"
                + String.join("、", result.columns()) + "）。"
                + "完整数据见结构化部分。";
    }

    /**
     * 结构化结果。
     *
     * <p>字段名沿用 ask 的：调用方如果同时接了两个入口，语义是一致的。
     *
     * @param question 原始问题。带上它，调用方在日志里能把结果与问题对上
     */
    public Map<String, Object> data(String question, AskResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("question", question);
        out.put("domainId", result.domainId());
        out.put("durationMs", result.durationMs());

        // 文字答复优先：直连模式与能力问题只有它
        if (result.answer() != null && !result.answer().isBlank()) {
            out.put("type", "answer");
            out.put("answer", result.answer());
            return out;
        }

        if (result.analysis() != null) {
            out.put("type", "analysis");
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("narrative", result.analysis().narrative());
            a.put("findings", result.analysis().findings());
            a.put("queryCount", result.analysis().queryCount());
            a.put("notice", result.analysis().notice());
            out.put("analysis", a);
            return out;
        }

        out.put("type", "table");
        out.put("columns", result.columns());
        out.put("rowCount", result.rowCount());

        int limit = Math.min(result.rows().size(), MAX_ROWS_IN_DATA);
        out.put("rows", result.rows().subList(0, limit));
        if (result.rowCount() > limit) {
            // 明确告诉调用方"这里不是全量"。不说的话，它会以为拿到了全部——
            // 而基于残缺数据做决策比拿不到数据更糟
            out.put("truncated", true);
            out.put("fullRowsHint",
                    "只返回前 " + limit + " 行，共 " + result.rowCount() + " 行。"
                            + "需要全量请直接调问数服务的 /api/ask/engine");
        }

        if (!result.maskedColumns().isEmpty()) {
            // 不标注的话，调用方会把脱敏值当真值拿去计算
            out.put("maskedColumns", result.maskedColumns());
        }
        if (result.sql() != null && !result.sql().isBlank()) {
            out.put("sql", result.sql());
        }
        return out;
    }

    /** 序列化成 DataPart 的内容。 */
    public String dataJson(String question, AskResult result) {
        return JSON.writeValueAsString(data(question, result));
    }

    /** 把列名 + 前几行拼成可直接读的表格文本，给只读文本的调用方兜底。 */
    String tableAsText(AskResult result) {
        int cols = result.columns().size();
        List<String> lines = new ArrayList<>();
        lines.add(String.join(" | ", result.columns()));
        int limit = Math.min(result.rows().size(), MAX_ROWS_IN_DATA);
        for (int i = 0; i < limit; i++) {
            lines.add(String.join(" | ", result.rows().get(i)));
        }
        if (result.rowCount() > limit) {
            lines.add("… 共 " + result.rowCount() + " 行");
        }
        return String.join("\n", lines);
    }
}

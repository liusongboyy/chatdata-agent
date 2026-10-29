package com.liusongboyy.chatdata.channel.render;

import com.liusongboyy.chatdata.channel.ask.AskResult;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把问数结果排成飞书卡片。
 *
 * <h2>版面约束来自手机端</h2>
 *
 * <p>飞书卡片在手机上大约 20 字符宽。列多了会折行到不可读，
 * 所以<b>列数上限 5、行数上限 10</b>，超出的用「查看完整结果」兜底。
 * 这不是保守，是不想让用户在一个更小的屏幕上看到一张挤成乱码的表。
 *
 * <h2>SQL 用折叠面板，不做服务端开关</h2>
 *
 * <p>{@code collapsible_panel} 的展开状态是<b>客户端本地</b>的，
 * 不需要回调、不需要 PATCH 卡片、不需要按钮去重。
 * 「默认收起 + 谁都能点开」用一个组件就满足了，没必要给服务端添路径。
 *
 * <p>代价：需要飞书客户端 V7.9+，低版本显示「请升级客户端」占位。
 * 不影响主信息（结论 + 表格）的呈现。
 */
@Component
public class FeishuCardRenderer {

    /** 卡片里最多放几列。超过就只说「共 N 列」并引导去看完整结果。 */
    static final int MAX_COLUMNS = 5;

    /** 卡片里最多放几行。 */
    static final int MAX_ROWS = 10;

    private static final ObjectWriter JSON = JsonMapper.builder().build().writerWithDefaultPrettyPrinter();

    /**
     * 渲染结果卡片。
     *
     * @param question 用户原问题。放在标题里，让群里几个人同时问时不至于分不清
     */
    public String renderResult(String question, AskResult result) {
        List<Map<String, Object>> elements = new ArrayList<>();

        if (result.empty()) {
            // 空结果必须明说。发一张空表格会让人以为是渲染失败
            elements.add(md("**没有符合条件的数据。**"));
            elements.add(note("可以试着放宽时间范围，或换一种问法。"));
            return card(title(question), elements);
        }

        if (result.singleValue()) {
            // 单值结果用大字，比塞进表格直观得多
            elements.add(md("**" + result.columns().get(0) + "：**"));
            elements.add(md("## " + result.rows().get(0).get(0)));
        } else {
            elements.add(table(result));
            if (result.rowCount() > result.rows().size()) {
                elements.add(note("共 %d 行，仅展示前 %d 行".formatted(
                        result.rowCount(), result.rows().size())));
            }
        }

        if (!result.maskedColumns().isEmpty()) {
            // 不标注的话，用户会以为看到的就是原值，然后拿它去对账
            elements.add(note("已脱敏列：" + String.join("、", result.maskedColumns())));
        }

        if (result.sql() != null && !result.sql().isBlank()) {
            elements.add(sqlPanel(result.sql()));
        }

        return card(title(question), elements);
    }

    /** 错误卡片。原因要能读懂，不暴露内部细节。 */
    public String renderError(String question, String message) {
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(md("**" + message + "**"));
        return card(title(question), elements);
    }

    /** 「正在查询」的占位卡片。 */
    public String renderPending(String question) {
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(md("正在查询…"));
        return card(title(question), elements);
    }

    private Map<String, Object> title(String question) {
        // 标题过长会挤掉正文，截断是最省事的解法
        String q = question == null ? "" : question.strip();
        if (q.length() > 40) {
            q = q.substring(0, 40) + "…";
        }
        return Map.of(
                "tag", "plain_text",
                "content", q.isBlank() ? "问数结果" : q);
    }

    /**
     * 表格。列多时只取前 {@link #MAX_COLUMNS} 列。
     *
     * <p>飞书卡片的表格用 {@code column_set} 逐行拼——没有原生 table 组件。
     * 第一行当表头，加粗。
     */
    private Map<String, Object> table(AskResult result) {
        int cols = Math.min(result.columns().size(), MAX_COLUMNS);
        List<Map<String, Object>> rows = new ArrayList<>();

        List<Map<String, Object>> header = new ArrayList<>();
        for (int i = 0; i < cols; i++) {
            header.add(cell("**" + result.columns().get(i) + "**", true));
        }
        rows.add(columnSet(header));

        for (int r = 0; r < Math.min(result.rows().size(), MAX_ROWS); r++) {
            List<String> row = result.rows().get(r);
            List<Map<String, Object>> cells = new ArrayList<>();
            for (int i = 0; i < cols; i++) {
                cells.add(cell(i < row.size() ? row.get(i) : "", false));
            }
            rows.add(columnSet(cells));
        }

        Map<String, Object> box = new LinkedHashMap<>();
        box.put("tag", "column_set");
        box.put("flex_mode", "none");
        box.put("background_style", "default");
        box.put("columns", rows);
        return box;
    }

    private Map<String, Object> cell(String text, boolean header) {
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("tag", "markdown");
        md.put("content", text == null || text.isEmpty() ? "—" : text);
        if (header) {
            md.put("text_align", "left");
        }
        Map<String, Object> col = new LinkedHashMap<>();
        col.put("tag", "column");
        col.put("width", "weighted");
        col.put("weight", 1);
        col.put("vertical_align", "top");
        col.put("elements", List.of(md));
        return col;
    }

    private Map<String, Object> columnSet(List<Map<String, Object>> columns) {
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("tag", "column_set");
        set.put("flex_mode", "none");
        set.put("columns", columns);
        return set;
    }

    /**
     * SQL 折叠面板，默认收起。
     *
     * <p>展开状态由客户端管理，所以这里不需要任何回调或消息更新逻辑。
     */
    private Map<String, Object> sqlPanel(String sql) {
        Map<String, Object> title = new LinkedHashMap<>();
        title.put("tag", "markdown");
        title.put("content", "查看 SQL");

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("title", title);

        Map<String, Object> code = new LinkedHashMap<>();
        code.put("tag", "markdown");
        // 三层反引号：SQL 里可能含反引号标识符
        code.put("content", "```sql\n" + sql + "\n```");

        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("tag", "collapsible_panel");
        panel.put("expanded", false);
        panel.put("header", header);
        panel.put("elements", List.of(code));
        return panel;
    }

    private Map<String, Object> md(String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", "markdown");
        m.put("content", content);
        return m;
    }

    private Map<String, Object> note(String content) {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("tag", "plain_text");
        text.put("content", content);
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("tag", "note");
        n.put("elements", List.of(text));
        return n;
    }

    /**
     * 卡片外壳。
     *
     * <p>{@code config.wide_screen_mode} 让桌面端铺满宽度——表格在窄卡片里
     * 会挤成一行几个字。
     */
    private String card(Map<String, Object> title, List<Map<String, Object>> elements) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("schema", "2.0");
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("title", title);
        header.put("template", "blue");
        card.put("header", header);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("wide_screen_mode", true);
        card.put("config", config);
        card.put("body", Map.of("elements", elements));
        return JSON.writeValueAsString(card);
    }
}

package com.liusongboyy.chatdata.channel.render;

import com.liusongboyy.chatdata.channel.ask.AskClient;
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

    /** 卡片里每页放几行。 */
    static final int PAGE_SIZE = 10;

    /**
     * 最多翻到第几行。
     *
     * <p>ask 自身最多返回 1000 行（{@code SqlExecutor} 的 maxRows），
     * 这里与它对齐——超过这个数的行根本不存在，翻页翻过去也只会是空的。
     */
    static final int MAX_ROWS_TOTAL = 1000;

    private static final ObjectWriter JSON = JsonMapper.builder().build().writerWithDefaultPrettyPrinter();

    /**
     * 渲染结果卡片。
     *
     * @param question 用户原问题。放在标题里，让群里几个人同时问时不至于分不清
     */
    /**
     * 渲染结果卡片（第 1 页，或某一页）。
     *
     * @param answerId 已存答案的 id，翻页与反馈按钮都带上它；null 表示没存下来
     *                 （存储失败时的兜底——数据照发，只是不能翻页/反馈）
     * @param page     从 0 开始的页码
     */
    public String renderResult(String question, AskResult result, Long answerId, int page) {
        List<Map<String, Object>> elements = new ArrayList<>();

        // 文字答复（直连模式、能力问题）：没有表格，答案就是这段话。
        // 必须先于"空结果"判断——否则会把它显示成「没有符合条件的数据」，
        // 而用户明明得到了答案。实测踩过。
        if (result.answer() != null && !result.answer().isBlank()) {
            elements.add(md(result.answer()));
            return card(title(question), elements);
        }

        // 深入分析：结论 + 逐条发现 + 用了几个查询
        if (result.analysis() != null) {
            elements.add(analysisSection(result.analysis()));
            // 步骤明细放折叠面板：主信息是结论，过程按需展开。
            // **不能只报次数不给内容**——"执行了 3 次查询"如果看不到是哪 3 次，
            // 那个数字就没有证据，用户只能选择信或不信
            Map<String, Object> steps = stepsPanel(result.analysis().steps());
            if (steps != null) {
                elements.add(steps);
            }
            if (result.sql() != null && !result.sql().isBlank()) {
                elements.add(sqlPanel(result.sql()));
            }
            if (answerId != null) {
                elements.add(feedbackRow(answerId));
            }
            return card(title(question), elements);
        }

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
            int total = Math.min(result.rows().size(), MAX_ROWS_TOTAL);
            int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
            int safePage = Math.max(0, Math.min(page, pages - 1));
            int from = safePage * PAGE_SIZE;
            int to = Math.min(from + PAGE_SIZE, total);

            elements.add(table(result, from, to));

            if (pages > 1) {
                elements.add(note("第 %d/%d 页 · 第 %d–%d 行%s".formatted(
                        safePage + 1, pages, from + 1, to,
                        result.rowCount() > total
                                ? "（共 %d 行，最多展示前 %d 行）".formatted(result.rowCount(), total)
                                : "（共 %d 行）".formatted(result.rowCount()))));
                elements.add(pager(answerId, safePage, pages));
            } else if (result.rowCount() > total) {
                elements.add(note("共 %d 行，最多展示前 %d 行".formatted(result.rowCount(), total)));
            }
        }

        if (!result.maskedColumns().isEmpty()) {
            // 不标注的话，用户会以为看到的就是原值，然后拿它去对账
            elements.add(note("已脱敏列：" + String.join("、", result.maskedColumns())));
        }

        if (result.sql() != null && !result.sql().isBlank()) {
            elements.add(sqlPanel(result.sql()));
        }

        // 反馈按钮：只有存下来了才能关联上下文——没存就没有 answerId，
        // 而反馈接口需要 question/sql/assistantId，那些都在库里
        if (answerId != null) {
            elements.add(feedbackRow(answerId));
        }

        return card(title(question), elements);
    }

    /**
     * 深入分析的结果。
     *
     * <p>重点是**结论**，不是过程。分析跑了几次查询、每步什么 SQL 都不进来——
     * IM 里放不下，业务人员也不关心。只保留：结论 + 逐条发现 + "跑了几次查询"这一行元信息。
     */
    private Map<String, Object> analysisSection(AskResult.Analysis a) {
        StringBuilder sb = new StringBuilder();
        if (a.narrative() != null && !a.narrative().isBlank()) {
            sb.append(a.narrative()).append("\n\n");
        }
        if (!a.findings().isEmpty()) {
            sb.append("**发现：**\n");
            for (String f : a.findings()) {
                sb.append("· ").append(f).append('\n');
            }
            sb.append('\n');
        }
        // 只报次数不给内容等于没给信息——所以要指向下面的折叠面板，
        // 让用户知道"那几次查询在哪能看"
        if (a.queryCount() > 0) {
            String where = (a.steps() != null && !a.steps().isEmpty())
                    ? "（明细在下方「查看这 " + a.steps().size() + " 次查询」里）"
                    : "";
            sb.append("<font color='grey'>本次分析执行了 ")
                    .append(a.queryCount()).append(" 次查询").append(where).append("</font>");
        }
        if (a.notice() != null && !a.notice().isBlank()) {
            sb.append("\n<font color='orange'>").append(a.notice()).append("</font>");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", "markdown");
        m.put("content", sb.toString());
        return m;
    }

    /**
     * 分析步骤明细（折叠，默认收起）。
     *
     * <p>每一步给：做了什么、查出几行、SQL。**每步的结果表最多 5 行**——
     * 单查询的结果已经在主卡片里说清了，步骤面板的作用是让人看到
     * "系统到底分几步、每步查了什么"，不是再灌一遍数据。
     */
    private Map<String, Object> stepsPanel(List<AskResult.Step> steps) {
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> inner = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            AskResult.Step step = steps.get(i);
            StringBuilder sb = new StringBuilder();
            sb.append("**").append(i + 1).append(". ")
                    .append(step.label().isBlank() ? "查询" : escapeMd(step.label()))
                    .append("**");
            if (step.error() != null) {
                sb.append("\n<font color='red'>失败：").append(escapeMd(step.error())).append("</font>");
            } else {
                sb.append("\n<font color='grey'>").append(step.rowCount()).append(" 行</font>");
            }
            if (step.purpose() != null && !step.purpose().isBlank()) {
                sb.append("\n<font color='grey'>").append(escapeMd(step.purpose())).append("</font>");
            }
            inner.add(md(sb.toString()));
            if (step.sql() != null && !step.sql().isBlank()) {
                inner.add(md("```sql\n" + step.sql() + "\n```"));
            }
            if (i < steps.size() - 1) {
                inner.add(md("---"));
            }
        }

        Map<String, Object> title = new LinkedHashMap<>();
        title.put("tag", "markdown");
        title.put("content", "查看这 " + steps.size() + " 次查询");
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("title", title);

        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("tag", "collapsible_panel");
        panel.put("expanded", false);
        panel.put("header", header);
        panel.put("elements", inner);
        return panel;
    }

    /**
     * 翻页按钮。
     *
     * <p>用 {@code behaviors: {type: callback}} 而不是 url：翻页要带 answerId 与页码，
     * 走链接就得拼 URL、还得解决"点了跳出去"的体验问题。回调是原生的。
     *
     * <p>第一页不给「上一页」，最后一页不给「下一页」——点了没反应的按钮
     * 比没有按钮更让人困惑。
     */
    private Map<String, Object> pager(Long answerId, int page, int pages) {
        List<Map<String, Object>> buttons = new ArrayList<>();
        if (answerId != null) {
            if (page > 0) {
                buttons.add(navButton("上一页", "page", answerId, page - 1));
            }
            if (page < pages - 1) {
                buttons.add(navButton("下一页", "page", answerId, page + 1));
            }
        }
        Map<String, Object> col = new LinkedHashMap<>();
        col.put("tag", "column_set");
        col.put("flex_mode", "bisect");
        // column_set 在 2.0 里用 columns；每列内嵌一个按钮
        List<Map<String, Object>> cols = new ArrayList<>();
        for (Map<String, Object> b : buttons) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("tag", "column");
            c.put("width", "auto");
            c.put("elements", List.of(b));
            cols.add(c);
        }
        col.put("columns", cols);
        return col;
    }

    private Map<String, Object> navButton(String text, String action, long answerId, int page) {
        Map<String, Object> btn = new LinkedHashMap<>();
        btn.put("tag", "button");
        btn.put("text", Map.of("tag", "plain_text", "content", text));
        btn.put("type", "default");
        btn.put("behaviors", List.of(Map.of(
                "type", "callback",
                "value", Map.of("action", action, "answerId", answerId, "page", page))));
        return btn;
    }

    /** 反馈按钮行。 */
    private Map<String, Object> feedbackRow(long answerId) {
        List<Map<String, Object>> cols = new ArrayList<>();
        for (var spec : List.of(
                Map.entry("👍 有用", "helpful"),
                Map.entry("👎 答错了", "wrong"))) {
            Map<String, Object> btn = new LinkedHashMap<>();
            btn.put("tag", "button");
            btn.put("text", Map.of("tag", "plain_text", "content", spec.getKey()));
            btn.put("type", "default");
            btn.put("behaviors", List.of(Map.of(
                    "type", "callback",
                    "value", Map.of("action", spec.getValue(), "answerId", answerId))));
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("tag", "column");
            c.put("width", "auto");
            c.put("elements", List.of(btn));
            cols.add(c);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tag", "column_set");
        row.put("flex_mode", "bisect");
        row.put("columns", cols);
        return row;
    }

    /** 错误卡片。原因要能读懂，不暴露内部细节。 */
    public String renderError(String question, String message) {
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(md("**" + message + "**"));
        return card(title(question), elements);
    }

    /**
     * 定时订阅的结果卡片。
     *
     * <p>与手动提问的结果卡区别：标题带「定时」，让人一眼看出这条是自动推的，
     * 而不是自己刚问的。除此之外排版一致——同一个问题，不该因为触发方式不同
     * 就长得不一样。
     */
    public String subscriptionCard(String question, AskResult result) {
        List<Map<String, Object>> elements = new ArrayList<>();
        if (result.answer() != null && !result.answer().isBlank()) {
            elements.add(md(result.answer()));
        } else if (result.analysis() != null && result.analysis().narrative() != null) {
            elements.add(md(result.analysis().narrative()));
        } else if (result.singleValue()) {
            elements.add(md("**" + result.columns().get(0) + "："
                    + result.rows().get(0).get(0) + "**"));
        } else {
            StringBuilder sb = new StringBuilder("共 " + result.rowCount() + " 行。\n");
            int n = Math.min(result.rows().size(), 10);
            for (int i = 0; i < n; i++) {
                sb.append("· ").append(String.join(" | ", result.rows().get(i))).append('\n');
            }
            elements.add(md(sb.toString()));
        }
        elements.add(md("<font color='grey'>定时推送 · " + question + "</font>"));
        Map<String, Object> t = title(question);
        // 标题加前缀，和手动问答区分开
        t = new LinkedHashMap<>(t);
        t.put("content", "⏰ " + t.get("content"));
        return card(t, elements);
    }

    /**
     * 订阅执行失败的卡片。
     *
     * <p><b>失败必须说清楚。</b>「今天没收到」比「收到了报错」难查得多——
     * 用户不知道是被权限拒了、服务挂了、还是自己把订阅删了。
     *
     * <p>权限类的失败额外给一句"检查你的账号权限"，因为那是最常见的原因，
     * 而且只有用户自己能处理（找管理员重新授权）。
     */
    public String subscriptionFailureCard(String question, String message) {
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(md("**这次定时查询没能完成。**"));
        elements.add(md(message == null ? "原因未知" : message));
        elements.add(md("<font color='grey'>问题：" + question
                + "\n如果提示权限，请检查你的账号是否还有对应数据的访问权限。</font>"));
        Map<String, Object> t = title(question);
        t = new LinkedHashMap<>(t);
        t.put("content", "⚠️ 定时推送失败");
        return card(t, elements);
    }

    /**
     * 绑定引导卡片。
     *
     * <p>给一条<b>能点</b>的链接，而不是「请联系管理员」——
     * 后者会让每一次新用户接入都变成一次人工工单。
     *
     * <p>链接指向网关的 {@code /iam/app/bind}：那里有用户的登录会话，
     * 绑定到谁由那次会话决定，而不是由链接决定（见 BindTicketIssuer）。
     */
    public String renderBindGuide(String bindUrl) {
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(md("**你还没有绑定问数账号。**"));
        elements.add(md("点下面的按钮，用你的问数账号登录并确认绑定，之后就能直接在这里提问。"));
        elements.add(button("立即绑定", bindUrl));
        elements.add(note("链接 10 分钟内有效，且只能用一次。不是你本人操作的话请忽略这条消息。"));
        return card(Map.of("tag", "plain_text", "content", "绑定问数账号"), elements);
    }

    /**
     * 助理选择卡片。
     *
     * <p>有多个可用助理时必须让用户选：不指定的话 ask 会落到第一个启用中的域，
     * 而那一问会**被错误地答成别的指标**——比"请先选一下"糟糕得多。
     *
     * <p>不用下拉选择组件：那条路要走卡片回调（card.action.trigger），
     * 多一条异步链路就多一处失败态。用按钮 + 文本命令（{@code #助理 门店分析}），
     * 点按钮只是帮你把这条命令发出来而已——按钮的 url 做不到这件事，
     * 所以这里给出可复制的命令 + 每个助理的编号。
     */
    public String renderAssistantPicker(String question, List<AskClient.AskAssistant> choices) {
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(md("**你有多个问数助理，这一问要交给哪一个？**"));
        elements.add(md("回复下面的命令即可（例如 `#助手 1`）："));
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < choices.size(); i++) {
            var c = choices.get(i);
            list.append("**").append(i + 1).append(".** ").append(c.name()).append('\n');
        }
        elements.add(md(list.toString()));
        if (question != null && !question.isBlank()) {
            elements.add(md("<font color='grey'>选好后请重新发送你的问题："
                    + escapeMd(question) + "</font>"));
        }
        return card(Map.of("tag", "plain_text", "content", "选择问数助理"), elements);
    }

    /**
     * 能力清单卡片。
     *
     * <p>回答「你能问什么」时**不要**把字段名一股脑倒出来：用户要的是
     * 「我能问这类问题」，不是一张字段表。所以顺序是：
     * 助理名 + 欢迎语 → 示例问题（最能说明能力）→ 字段清单（放在最后，且折叠）。
     *
     * <p>字段放在折叠面板里：需要精确写法的人可以展开，其余人看完示例问题就够了。
     */
    public String renderCapabilities(AskClient.AskAssistant assistant,
                                     AskClient.Capabilities caps) {
        List<Map<String, Object>> elements = new ArrayList<>();

        String domain = caps == null || caps.domainName() == null || caps.domainName().isBlank()
                ? assistant.name()
                : assistant.name() + "（" + caps.domainName() + "）";
        elements.add(md("当前使用的是 **" + domain + "**。"));

        if (assistant.welcome() != null && !assistant.welcome().isBlank()) {
            elements.add(md(escapeMd(assistant.welcome())));
        }

        if (!assistant.questions().isEmpty()) {
            elements.add(md("**你可以这样问：**"));
            for (String q : assistant.questions()) {
                elements.add(md("· " + q));
            }
        }

        if (caps != null && !caps.fields().isEmpty()) {
            // 字段多时只显示前 40 个：全列出来既读不完，也会把卡片撑得很长
            List<String> fields = caps.fields();
            StringBuilder sb = new StringBuilder();
            int shown = Math.min(fields.size(), 40);
            for (int i = 0; i < shown; i++) {
                sb.append("· ").append(fields.get(i)).append('\n');
            }
            if (fields.size() > shown) {
                sb.append("… 共 ").append(fields.size()).append(" 项");
            }
            Map<String, Object> title = new LinkedHashMap<>();
            title.put("tag", "markdown");
            title.put("content", "可问的字段（" + fields.size() + "）");
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("title", title);
            Map<String, Object> panel = new LinkedHashMap<>();
            panel.put("tag", "collapsible_panel");
            panel.put("expanded", false);
            panel.put("header", header);
            panel.put("elements", List.of(md(sb.toString())));
            elements.add(panel);
        }

        elements.add(md("<font color='grey'>想看别的助理？发「#助手」查看列表。</font>"));
        return card(Map.of("tag", "plain_text", "content", "我能问什么"), elements);
    }

    /** 当前使用哪个助理的提示。切换成功后回一张，让用户确认。 */
    public String renderAssistantSwitched(AskClient.AskAssistant chosen) {
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(md("已切换到 **" + chosen.name() + "**。"));
        if (!chosen.welcome().isBlank()) {
            elements.add(md("<font color='grey'>" + escapeMd(chosen.welcome()) + "</font>"));
        }
        if (!chosen.questions().isEmpty()) {
            elements.add(md("**可以试试问：**"));
            for (String q : chosen.questions()) {
                elements.add(md("· " + q));
            }
        }
        return card(Map.of("tag", "plain_text", "content", "已切换助理"), elements);
    }

    /** markdown 里要转义的字符。助理名/欢迎语是配置内容，可能含 * _ 等。 */
    private static String escapeMd(String v) {
        return v.replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_").replace("`", "\\`");
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
    /**
     * 表格。用 **markdown 原生表格语法**。
     *
     * <p>不用 {@code column_set} 逐行拼：那套在 JSON 2.0 里结构也变了，
     * 而 markdown 表格是 2.0 明确支持的标准语法（CommonMark），
     * 少一层自己维护的布局逻辑。代价是列宽不能精细控制——在 IM 里不值得为此较真。
     *
     * <p>单元格里的 {@code |} 必须转义，否则会把表格撑成多列。
     */
    /**
     * 表格。用 **markdown 原生表格语法**。
     *
     * <p>不用 {@code column_set} 逐行拼：那套在 JSON 2.0 里结构也变了，
     * 而 markdown 表格是 2.0 明确支持的标准语法（CommonMark），
     * 少一层自己维护的布局逻辑。代价是列宽不能精细控制——在 IM 里不值得为此较真。
     *
     * <p>单元格里的 {@code |} 必须转义，否则会把表格撑成多列。
     */
    private Map<String, Object> table(AskResult result, int from, int to) {
        int cols = Math.min(result.columns().size(), MAX_COLUMNS);
        StringBuilder md = new StringBuilder();

        List<String> head = new ArrayList<>();
        for (int i = 0; i < cols; i++) {
            head.add(escapeCell(result.columns().get(i)));
        }
        md.append("| ").append(String.join(" | ", head)).append(" |\n");
        md.append("|").append(" --- |".repeat(cols)).append("\n");

        int end = Math.min(to, Math.min(result.rows().size(), MAX_ROWS_TOTAL));
        int start = Math.max(0, Math.min(from, end));
        for (int r = start; r < end; r++) {
            List<String> row = result.rows().get(r);
            List<String> cells = new ArrayList<>();
            for (int i = 0; i < cols; i++) {
                String v = i < row.size() ? row.get(i) : "";
                cells.add(escapeCell(v == null || v.isEmpty() ? "—" : v));
            }
            md.append("| ").append(String.join(" | ", cells)).append(" |\n");
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", "markdown");
        m.put("content", md.toString());
        return m;
    }

    /** 表格单元格转义。竖线会把一列撑成两列；换行会把一行撑成两行。 */
    private static String escapeCell(String v) {
        return v.replace("|", "\\|").replace("\n", " ");
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

    private Map<String, Object> button(String text, String url) {
        Map<String, Object> btn = new LinkedHashMap<>();
        btn.put("tag", "button");
        btn.put("text", Map.of("tag", "plain_text", "content", text));
        btn.put("type", "primary");
        btn.put("url", url);
        return btn;
    }

    private Map<String, Object> md(String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", "markdown");
        m.put("content", content);
        return m;
    }

    /**
     * 次要说明文字。
     *
     * <p><b>不能用 {@code note} 组件</b>：卡片 JSON 2.0 已废弃它
     * （飞书报 {@code cards of schema V2 no longer support tag: note}）。
     * 官方给的替代是「普通文本 + notation 字号 + grey 颜色」，这里就用 markdown 实现。
     */
    private Map<String, Object> note(String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", "markdown");
        m.put("content", "<font color='grey'>" + content + "</font>");
        m.put("text_size", "notation");
        return m;
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

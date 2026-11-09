package com.liusongboyy.chatdata.ask.report;

import com.liusongboyy.chatdata.ask.conversation.MessageRow;
import com.liusongboyy.chatdata.ask.conversation.mapper.ConversationMapper;
import com.liusongboyy.chatdata.ask.report.mapper.ReportMapper;
import com.liusongboyy.chatdata.engine.report.Report;
import com.liusongboyy.chatdata.engine.report.ReportBuilder;
import com.liusongboyy.chatdata.engine.report.ReportSource;
import com.liusongboyy.chatdata.engine.report.ResultJsonReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 报告的生成与存取。
 *
 * <h2>它做的事很少</h2>
 *
 * <p>真正的合并逻辑在引擎层的 {@link ReportBuilder}（纯函数、可测）。
 * 这里只负责三件事：**从库里取数据、调 builder、存回去**。
 *
 * <p>这样分层是刻意的——合并逻辑最容易错，把它放在不依赖数据库的地方，
 * 才能用单测钉死。而这个类只需要验证"取数与存取的接线对不对"。
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    /** 列表默认返回多少条。 */
    private static final int DEFAULT_LIMIT = 20;

    private final ReportMapper reports;
    private final ConversationMapper conversations;
    private final ObjectMapper json = new ObjectMapper();

    public ReportService(ReportMapper reports, ConversationMapper conversations) {
        this.reports = reports;
        this.conversations = conversations;
    }

    /**
     * 可勾选的轮次。
     *
     * <p>返回**所有成功的问答**，带两个标记让前端决定默认勾选：
     * <ul>
     *   <li>{@code hasAnalysis}——有没有分析（没有的进「数据」章节）</li>
     *   <li>{@code selectable}——能不能勾（失败的消息不能）</li>
     * </ul>
     *
     * @param conversationId 会话 id
     * @param username       当前用户；**不属于他的会话返回 404**
     */
    public List<Candidate> candidates(String conversationId, String username) {
        requireOwnership(conversationId, username);

        List<MessageRow> messages = conversations.selectMessages(conversationId);
        List<Candidate> out = new ArrayList<>();

        // 逐条配对：ASSISTANT 消息的问题在它前一条 USER 消息里
        String lastQuestion = null;
        for (MessageRow m : messages) {
            if ("USER".equals(m.role())) {
                lastQuestion = m.content();
                continue;
            }
            if (!"ASSISTANT".equals(m.role())) {
                continue;
            }
            boolean hasResult = m.resultJson() != null && !m.resultJson().isBlank();
            boolean success = "SUCCESS".equals(m.status()) && hasResult;

            out.add(new Candidate(
                    String.valueOf(m.id()),
                    lastQuestion == null ? m.content() : lastQuestion,
                    success,
                    // 失败的消息没有数据，不能进报告
                    success,
                    success && hasAnalysis(m.resultJson()),
                    m.createdAt()));
        }
        return out;
    }

    /**
     * 生成报告。
     *
     * @param conversationId 会话 id
     * @param username       当前用户
     * @param messageIds     勾选的消息 id；**空表示全选成功的那些**
     * @return 生成的报告
     */
    public Report create(String conversationId, String username, List<String> messageIds) {
        requireOwnership(conversationId, username);

        List<MessageRow> messages = conversations.selectMessages(conversationId);
        Map<String, String> questionOf = questionIndex(messages);

        List<ReportSource> sources = new ArrayList<>();
        String lastQuestion = null;

        for (MessageRow m : messages) {
            if ("USER".equals(m.role())) {
                lastQuestion = m.content();
                continue;
            }
            if (!"ASSISTANT".equals(m.role())) {
                continue;
            }
            String id = String.valueOf(m.id());
            // 勾选了就只要勾选的；没勾（空列表）= 全部成功的
            boolean wanted = messageIds == null || messageIds.isEmpty()
                    || messageIds.contains(id);
            if (!wanted) {
                continue;
            }
            if (!"SUCCESS".equals(m.status()) || m.resultJson() == null) {
                continue;
            }
            ReportSource src = ResultJsonReader.read(id,
                    lastQuestion == null ? m.content() : lastQuestion, m.resultJson());
            if (src == null) {
                // 解析不了的那轮跳过。用户看到的是"少了一轮"，
                // 比"生成失败"更有用——他能自己判断要不要补
                log.warn("报告跳过无法解析的消息：conv={} msgId={}", conversationId, id);
                continue;
            }
            sources.add(src);
        }

        if (sources.isEmpty()) {
            throw new IllegalArgumentException("选中的问答里没有可用的数据");
        }

        Report report = ReportBuilder.build(conversationId, username, sources);
        persist(report);
        log.info("生成报告：id={} conv={} user={} 节数={} 口径冲突={}",
                report.id(), conversationId, username,
                report.body().sectionCount(), report.body().scopeConflict());
        return report;
    }

    /** 读一份报告。**只读自己的。** */
    public Report get(String reportId, String username) {
        ReportRow row = reports.selectById(reportId);
        if (row == null || !row.username().equals(username)) {
            // 不是自己的一律 404，**不泄露存在性**
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报告不存在");
        }
        return toReport(row);
    }

    /** 我的报告列表。 */
    public Page list(String username, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(size <= 0 ? DEFAULT_LIMIT : size, 100));
        int offset = (p - 1) * s;

        List<Summary> items = reports.selectByUser(username, offset, s).stream()
                .map(r -> new Summary(r.id(), r.conversationId(), r.title(),
                        r.generatedAt(), r.sourceMessageIds().isBlank()
                                ? 0 : r.sourceMessageIds().split(",").length))
                .toList();
        return new Page(items, reports.countByUser(username), p, s);
    }

    /** 删除。软删除，只作用于自己的。 */
    public void delete(String reportId, String username) {
        int n = reports.softDelete(reportId, username);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报告不存在");
        }
    }

    // ─────────────────────────────────────────────────────────────

    private void persist(Report report) {
        try {
            reports.insert(new ReportRow(
                    report.id(),
                    report.conversationId(),
                    report.username(),
                    report.title(),
                    truncate(report.intent(), 500),
                    json.writeValueAsString(report.body()),
                    String.join(",", report.sourceMessageIds()),
                    report.modelKey(),
                    report.generatedAt() == null ? LocalDateTime.now() : report.generatedAt()));
        } catch (RuntimeException e) {
            // 存不进去就不该假装成功——用户点"生成"期待的是"以后还能打开"
            log.error("报告落库失败：id={}", report.id(), e);
            throw new IllegalStateException("报告已生成但保存失败：" + e.getMessage(), e);
        }
    }

    private Report toReport(ReportRow row) {
        try {
            com.liusongboyy.chatdata.engine.report.ReportBody body =
                    json.readValue(row.bodyJson(),
                            com.liusongboyy.chatdata.engine.report.ReportBody.class);
            return new Report(row.id(), row.conversationId(), row.username(),
                    row.title(), row.intent(), body,
                    row.sourceMessageIds().isBlank()
                            ? List.of() : List.of(row.sourceMessageIds().split(",")),
                    row.modelKey(), row.generatedAt());
        } catch (RuntimeException e) {
            // 正文坏了：说明格式变了或者存的时候就有问题。
            // **明确报错**，不返回一份空报告——那会让用户以为报告本来就是空的
            log.error("报告正文无法解析：id={}", row.id(), e);
            throw new IllegalStateException("这份报告的格式无法识别（可能是旧版本生成的）");
        }
    }

    /** 会话必须属于当前用户。**不泄露存在性**：不是自己的返回 404。 */
    private void requireOwnership(String conversationId, String username) {
        var page = conversations.selectById(conversationId);
        if (page == null || !page.username().equals(username)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
    }

    /** 消息 id → 该轮的问题。用于单查询轮的标题。 */
    private Map<String, String> questionIndex(List<MessageRow> messages) {
        Map<String, String> out = new HashMap<>();
        String last = null;
        for (MessageRow m : messages) {
            if ("USER".equals(m.role())) {
                last = m.content();
            } else if (last != null) {
                out.put(String.valueOf(m.id()), last);
            }
        }
        return out;
    }

    private boolean hasAnalysis(String resultJson) {
        return resultJson != null && resultJson.contains("\"analysis\":{");
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ─────────────────────────────────────────────────────────────

    /**
     * 一个可勾选的轮次。
     *
     * @param messageId   消息 id，生成报告时传回来
     * @param question    该轮的问题
     * @param selectable  能不能勾。**失败的消息不能**——它没有数据
     * @param hasAnalysis 有没有分析。前端据此标注「分析」/「数据」
     */
    public record Candidate(String messageId, String question, boolean selectable,
                            boolean success, boolean hasAnalysis, LocalDateTime createdAt) {
    }

    /** 列表里的一行。**不带正文**——列表页不需要，带上会让响应变大几十倍。 */
    public record Summary(String id, String conversationId, String title,
                          LocalDateTime generatedAt, int sourceCount) {
    }

    public record Page(List<Summary> items, int total, int page, int size) {
    }
}

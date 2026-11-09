package com.liusongboyy.chatdata.engine.report;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一份分析报告。
 *
 * <h2>报告是快照，不是视图</h2>
 *
 * <p>它把某一刻多轮问答的结果**完整抄下来**，而不是存一堆引用。
 * 三个月后打开，看到的必须还是当时那份数据——否则「报告」就没有意义了。
 *
 * <p>这也意味着：语义层的口径后来改了，报告仍是旧口径。**这是对的**，
 * 报告记录的是「当时怎么算的」。
 *
 * @param id             报告 id
 * @param conversationId 来源会话
 * @param username       生成人
 * @param title          标题。**模型生成**；失败时降级为 {@code intent}
 * @param intent         用户原始问题（第一轮），降级标题用它
 * @param body           正文
 * @param sourceMessageIds 勾选了哪几条 {@code ask_message.id}
 * @param modelKey       生成标题用的模型；null 表示没调模型（降级路径）
 * @param generatedAt    生成时间。**必须写死**——报告是历史文件
 */
public record Report(
        String id,
        String conversationId,
        String username,
        String title,
        String intent,
        ReportBody body,
        List<String> sourceMessageIds,
        String modelKey,
        LocalDateTime generatedAt
) {

    public Report {
        sourceMessageIds = sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds);
        title = (title == null || title.isBlank()) ? fallbackTitle(intent) : title.trim();
        generatedAt = generatedAt == null ? LocalDateTime.now() : generatedAt;
    }

    /**
     * 标题降级。
     *
     * <p>模型不可用时用原始问题当标题——不完美，但比「未命名报告」有用得多。
     * 用户至少知道这份报告是关于什么的。
     */
    private static String fallbackTitle(String intent) {
        if (intent == null || intent.isBlank()) {
            return "分析报告";
        }
        String t = intent.trim();
        // 原始问题可能很长（「为什么 2025 年华东区保费比 2024 年下降，
        // 具体是哪些险种和客户等级造成的」），标题要短
        return t.length() <= 40 ? t : t.substring(0, 40) + "…";
    }
}

package com.liusongboyy.chatdata.engine.chat;

import com.liusongboyy.chatdata.engine.datasource.QueryResult;
import com.liusongboyy.chatdata.engine.nlsql.AskDsl;
import com.liusongboyy.chatdata.engine.nlsql.RenderedSql;
import com.liusongboyy.chatdata.engine.semantic.Domain;

/**
 * 一次问数的结果。
 *
 * @param dsl       语义意图。**保留它是为了审计与复核**——问数答错时，
 *                  第一现场是 DSL（模型理解成了什么），不是 SQL
 * @param sql       实际下发的语句（已含权限改写）
 * @param result    数据
 * @param maskedColumns 本次被脱敏的列名。**审计要单独记这一类**——
 *                  "谁看了脱敏数据"与"谁看了明文"是不同的事件
 * @param durationMs 端到端耗时
 * @param chart      推荐的图表形态。前端据此选默认视图，并允许用户切换
 * @param answer     **文字答复**。用于"能力问题"——用户问"这里能分析哪些数据"时，
 *                   直连模式让模型**不查库**、直接根据表结构回答，这就是那条通道。
 *                   数据问题为 null（那时答案是 result 里的数据）。见 {@code RawAskService}
 * @param mode       产生这个结果的模式。直连模式（RAW）**没有行/列权限**，前端要据此明示
 * @param modelSql   **直连模式**下模型写的 SQL。建模模式为 null——那时 SQL 在 {@link #sql} 里。
 *                   两种模式都有 SQL，只是来源不同，故由 {@link #sqlText()} 统一取出
 * @param understanding 系统实际理解到的事实（指标/维度/时间/筛选/数据范围）。
 *                   **直连模式为 null**——那一模式没有 DSL，也就没有可回显的结构化事实。
 *                  界面把它摊开给用户看——这是**唯一能挡住静默语义错误的位置**，
 *                  理由见 {@link AskUnderstanding}
 * @param analysis   分析编排的完整结果；单查询为 null。
 *                   为 null 时其余字段就是全部内容，前端走原有渲染
 * @param notice     降级说明（如"分析计划未能生成，已回退为单次查询"）。
 *                   **任何降级都必须说清楚**，否则用户以为自己看到的是分析结论
 */
public record AskResult(
        AskDsl dsl,
        RenderedSql sql,
        String modelSql,
        QueryResult result,
        java.util.Set<String> maskedColumns,
        long durationMs,
        ChartSpec chart,
        AskUnderstanding understanding,
        String answer,
        Domain.Mode mode,
        com.liusongboyy.chatdata.engine.analysis.AnalysisResult analysis,
        String notice
) {

    /** 兼容构造器：单查询与直连模式不涉及编排。 */
    public AskResult(AskDsl dsl, RenderedSql sql, String modelSql, QueryResult result,
                     java.util.Set<String> maskedColumns, long durationMs, ChartSpec chart,
                     AskUnderstanding understanding, String answer, Domain.Mode mode) {
        this(dsl, sql, modelSql, result, maskedColumns, durationMs, chart, understanding,
                answer, mode, null, null);
    }

    public AskResult {
        maskedColumns = maskedColumns == null ? java.util.Set.of() : java.util.Set.copyOf(maskedColumns);
        mode = mode == null ? Domain.Mode.MODELED : mode;
        notice = (notice == null || notice.isBlank()) ? null : notice.trim();
    }

    /** 带回一条降级说明的副本。用于"分析计划失败、已回退单查询"这类情形。 */
    public AskResult withNotice(String text) {
        return new AskResult(dsl, sql, modelSql, result, maskedColumns, durationMs, chart,
                understanding, answer, mode, analysis, text);
    }

    /**
     * 实际执行的 SQL 原文。**两种模式都有值，调用方只需读这一个。**
     *
     * <p>存在的理由：直连模式的 SQL 由模型写，没有 {@link RenderedSql}（那玩意儿的
     * 前提是"我们自己渲染的"），但界面、审计、排查要的都是同一样东西——那句 SQL。
     */
    public String sqlText() {
        return sql != null ? sql.sql() : modelSql;
    }

    /**
     * 展示给用户 / 写进审计的 SQL：参数已内联成字面量。
     *
     * <p>{@link #sqlText()} 是**参数化**版本（含 {@code ?}），执行与排查用它；
     * 但用户和审计看那句是没意义的——{@code WHERE org_path LIKE ?} 说明不了
     * "查的是哪个组织"。两者刻意分开，别把这里的结果拿去执行。
     *
     * <p>直连模式下模型写的就是完整 SQL，没有参数，两者相同。
     */
    public String sqlDisplayText() {
        return sql != null ? sql.displaySql() : modelSql;
    }

    /**
     * 这是不是一条"只说不用查"的答复。
     *
     * <p>界面据此**不渲染表格与 0 行提示**——那不是"查询没查到数据"，
     * 而是"这个问题的答案不是一张表"。
     */
    public boolean isTextOnly() {
        return answer != null && !answer.isBlank();
    }

    /** 是否直连模式。UI 据此显示「未启用行/列权限」的提示。 */
    public boolean isRaw() {
        return mode == Domain.Mode.RAW;
    }

    /** 结果是否为空（查询成功但没有数据）。**与"没听懂"是两回事**。 */
    public boolean isEmpty() {
        return result.isEmpty();
    }
}

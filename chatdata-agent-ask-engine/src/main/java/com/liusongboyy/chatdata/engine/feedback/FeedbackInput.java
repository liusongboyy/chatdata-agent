package com.liusongboyy.chatdata.engine.feedback;

import com.liusongboyy.chatdata.engine.datasource.SqlTools;

/**
 * 用户提交的反馈。
 *
 * <h2>为什么在入口就校验修正 SQL</h2>
 *
 * <p>反馈最终可能进入范例库（见 {@code AdminSqlExampleService.createFromFeedback}）。
 * 范例会原样进 prompt，一条 {@code DELETE} 放进去，下一个相似问题就可能被带成删除语句。
 * 所以**提交时先挡一次只读**，审核时再挡一次——两次用的是同一个判据
 * （{@link SqlTools#rejectUnsafe}），不各写一份。
 *
 * @param domainId     产生这条答案的域 id（字符串，与其它接口一致）
 * @param assistantId  助理 id，可为空
 * @param question     原始问题
 * @param generatedSql 系统实际执行的 SQL，可为空（能力答复没有 SQL）
 * @param correctedSql 修正后的 SQL；仅 {@link FeedbackType#SQL_CORRECTION} 使用
 * @param note         补充说明
 * @param type         反馈类型
 */
public record FeedbackInput(
        String domainId,
        String assistantId,
        String question,
        String generatedSql,
        String correctedSql,
        String note,
        FeedbackType type
) {

    /** 与 ask_feedback.question 的列宽一致。 */
    private static final int MAX_QUESTION = 2000;
    /** 与 ask_feedback.note 的列宽一致。 */
    private static final int MAX_NOTE = 500;
    /** SQL 的合理上限：多层 CTE 也能装下，又不至于让一次提交撑爆日志。 */
    private static final int MAX_SQL = 8000;

    public FeedbackInput validated() {
        if (domainId == null || domainId.isBlank()) {
            throw new FeedbackException("缺少数据域——反馈必须落在产生答案的那个域上");
        }
        long domain;
        try {
            domain = Long.parseLong(domainId.trim());
        } catch (NumberFormatException e) {
            throw new FeedbackException("数据域 id 非法：" + domainId);
        }
        if (domain <= 0) {
            throw new FeedbackException("数据域 id 非法：" + domainId);
        }
        if (type == null) {
            throw new FeedbackException("反馈类型不能为空");
        }
        if (question == null || question.isBlank()) {
            throw new FeedbackException("问题不能为空——反馈要靠它找到对应的问答");
        }
        String q = question.trim();
        if (q.length() > MAX_QUESTION) {
            q = q.substring(0, MAX_QUESTION);
        }

        String corrected = correctedSql == null || correctedSql.isBlank() ? null : correctedSql.trim();
        if (type == FeedbackType.SQL_CORRECTION) {
            if (corrected == null) {
                throw new FeedbackException("选择了「修正 SQL」，但没有填写 SQL");
            }
            String rejection = SqlTools.rejectUnsafe(corrected);
            if (rejection != null) {
                throw new FeedbackException("修正的 SQL 不合规：" + rejection);
            }
            if (corrected.length() > MAX_SQL) {
                throw new FeedbackException("修正的 SQL 太长（超过 " + MAX_SQL + " 字符）");
            }
        } else {
            // 非修正类型不收 SQL：留着会让审核人误以为可以入库
            corrected = null;
        }

        String n = note == null || note.isBlank() ? null : note.trim();
        if (n != null && n.length() > MAX_NOTE) {
            throw new FeedbackException("补充说明不能超过 " + MAX_NOTE + " 字");
        }

        String generated = generatedSql == null || generatedSql.isBlank() ? null : generatedSql.trim();
        if (generated != null && generated.length() > MAX_SQL) {
            generated = generated.substring(0, MAX_SQL);
        }

        return new FeedbackInput(String.valueOf(domain),
                assistantId == null || assistantId.isBlank() ? null : assistantId.trim(),
                q, generated, corrected, n, type);
    }

    public long domainIdAsLong() {
        return Long.parseLong(domainId);
    }
}

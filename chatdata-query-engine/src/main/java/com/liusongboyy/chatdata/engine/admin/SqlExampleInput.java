package com.liusongboyy.chatdata.engine.admin;

import com.liusongboyy.chatdata.engine.datasource.SqlTools;

/**
 * SQL 范例的创建/更新入参。
 *
 * @param question 示例问法（中文）
 * @param sql      该问题的正确 SQL
 * @param note     口径要点，可为空
 * @param keywords 检索关键词，顿号/逗号分隔。**命中即视为强相关**——
 *                 范例多的库靠它比字面相似度可靠（见 {@code SqlExampleSelector}）
 * @param enabled  是否参与检索
 * @param sortNo   排序号
 */
public record SqlExampleInput(String question, String sql, String note, String keywords,
                              Boolean enabled, Integer sortNo) {

    /** 兼容构造器：不指定时默认启用、排序 0、无关键词。 */
    public SqlExampleInput(String question, String sql, String note) {
        this(question, sql, note, null, true, 0);
    }

    public SqlExampleInput validated() {
        if (question == null || question.isBlank()) {
            throw new AdminException("示例问法不能为空——范例要靠它被检索到");
        }
        if (sql == null || sql.isBlank()) {
            throw new AdminException("SQL 不能为空");
        }

        // **在保存时就拦掉非只读的写法。**范例库会原样进 prompt，
        // 一条 DELETE 写进去，下一个相似问题就可能被带成删除语句
        String rejection = SqlTools.rejectUnsafe(sql);
        if (rejection != null) {
            throw new AdminException("范例的 SQL 不合规：" + rejection);
        }

        return new SqlExampleInput(question.trim(), sql.trim(),
                note == null || note.isBlank() ? null : note.trim(),
                keywords == null || keywords.isBlank() ? null : keywords.trim(),
                enabled == null || enabled,
                sortNo == null ? 0 : sortNo);
    }
}

package com.liusongboyy.chatdata.engine.raw;

/**
 * 一条 SQL 范例：某个中文问法对应的**正确** SQL。
 *
 * @param id       主键
 * @param domainId 所属域。范例只在该域内参与检索——它引用的表名换个域就不存在了
 * @param question 示例问法，应贴近业务人员的真实说法
 * @param sql      该问题的正确 SQL
 * @param note     口径要点。会与范例一起进 prompt；写清"为什么这么写"比写"这是什么"有用
 * @param keywords 检索关键词，顿号/逗号分隔。**命中即视为强相关**——见 {@link SqlExampleSelector}
 * @param source   {@code MANUAL}（手写）或 {@code AUDIT}（从审计里人工确认后存入）
 * @param enabled  是否参与检索
 */
public record SqlExample(long id, long domainId, String question, String sql, String note,
                         String keywords, String source, boolean enabled) {

    /** 兼容构造器：不带关键词。既有调用点与测试不必改。 */
    public SqlExample(long id, long domainId, String question, String sql, String note,
                      String source, boolean enabled) {
        this(id, domainId, question, sql, note, null, source, enabled);
    }

    /** 拆好的关键词。 */
    public java.util.List<String> keywordList() {
        if (keywords == null || keywords.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(keywords.split("[、,，\\s]+"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}

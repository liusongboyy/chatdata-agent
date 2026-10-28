package com.liusongboyy.chatdata.engine.semantic;

/** {@code ask_assistant} 的一行，**原始投影**。JSON 由 {@link AssistantLoader} 解析。 */
public record AssistantRow(
        long id,
        String name,
        String avatar,
        String description,
        String welcome,
        Long domainId,
        String domainIds,
        boolean showSql,
        String status,
        String questionsJson,
        String permJson
) {
}

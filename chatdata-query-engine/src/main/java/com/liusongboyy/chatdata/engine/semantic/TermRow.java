package com.liusongboyy.chatdata.engine.semantic;

/** {@code ask_term} 的一行，**原始投影**。JSON 由 {@link DomainLoader} 解析。 */
public record TermRow(
        long id,
        long domainId,
        String name,
        String synonymsJson,
        String description,
        String applyKind
) {
}

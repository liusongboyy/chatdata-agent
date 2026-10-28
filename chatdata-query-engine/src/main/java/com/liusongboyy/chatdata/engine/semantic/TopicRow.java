package com.liusongboyy.chatdata.engine.semantic;

/** {@code ask_topic} 的一行，**原始投影**。 */
public record TopicRow(
        long id,
        long domainId,
        String name,
        String description,
        String status,
        String fieldsJson
) {
    public boolean published() {
        return "published".equalsIgnoreCase(status);
    }
}

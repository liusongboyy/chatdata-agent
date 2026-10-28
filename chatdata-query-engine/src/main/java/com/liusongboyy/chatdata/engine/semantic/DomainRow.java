package com.liusongboyy.chatdata.engine.semantic;

/**
 * {@code ask_domain} 的一行，**原始投影**。
 *
 * <p>JSON 字段保持字符串形态，由 {@link DomainLoader} 解析。
 * 不在这里解析的原因：解析需要 Jackson、需要处理坏数据、需要给出行级错误上下文——
 * 那是加载器的职责，塞进投影记录会让它变成一个既做映射又做解析的混合体。
 *
 * @param id           主键
 * @param name         域名称
 * @param datasourceId 绑定数据源 id，可为空（空表示用应用默认库）
 * @param status       状态
 * @param mode         模式 {@code MODELED} / {@code RAW}。为 null 时按 MODELED 处理
 * @param joinsJson    表关联 JSON
 * @param permJson     权限配置 JSON
 */
public record DomainRow(
        long id,
        String name,
        Long datasourceId,
        String status,
        String mode,
        String joinsJson,
        String permJson
) {

    /**
     * 兼容构造器：不指定模式即建模模式。
     *
     * <p>既有的加载器与测试不必改——它们面对的都是加模式之前的域。
     */
    public DomainRow(long id, String name, Long datasourceId, String status,
                     String joinsJson, String permJson) {
        this(id, name, datasourceId, status, null, joinsJson, permJson);
    }

    public boolean enabled() {
        return "enabled".equalsIgnoreCase(status);
    }
}

package com.liusongboyy.chatdata.engine.semantic;

import com.liusongboyy.chatdata.engine.permission.AssistantPermission;

import java.util.List;

/**
 * 助理。
 *
 * <h2>它不是数据保护点</h2>
 *
 * <p>ADR-0002 实测：助手层拦下返回**明确错误**，域层拦下返回 **0 行**。
 * 前者体验更好，但也说明助理只决定「这个助理可不可见/可用」——
 * **数据保护由域层的行/列规则承担**。不要把助理权限当数据闸门。
 *
 * <h2>多域：存了但还没有智能路由</h2>
 *
 * <p>{@link #domainIds()} 是绑定的全部域，但问数时**只用 {@link #primaryDomainId()}**。
 * 真正的智能路由要让模型判断问题该落哪个域，那是独立的一块工作。
 * 本轮的做法是：多域时让**用户自己选**，而不是假装服务端会路由。
 */
public record Assistant(
        String id,
        String name,
        String avatar,
        String description,
        String welcome,
        String primaryDomainId,
        List<String> domainIds,
        boolean showSql,
        Status status,
        List<String> questions,
        AssistantPermission permission
) {

    public enum Status {
        /** 已上线。只有这个状态能问数。 */
        ONLINE,
        /** 草稿。 */
        DRAFT,
        /** 已下线。 */
        OFFLINE;

        public static Status parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return DRAFT;
            }
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "online", "已上线" -> ONLINE;
                case "draft", "草稿" -> DRAFT;
                case "offline", "已下线" -> OFFLINE;
                default -> throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                        "不支持的助理状态：" + raw + "（只支持 online/draft/offline）");
            };
        }
    }

    public Assistant {
        if (id == null || id.isBlank()) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "助理缺少 id");
        }
        domainIds = domainIds == null ? List.of() : List.copyOf(domainIds);
        questions = questions == null ? List.of() : List.copyOf(questions);
        status = status == null ? Status.DRAFT : status;
        // 权限缺失 = 未启用访问控制。AssistantPermission.from(null) 会构造出
        // enabled=false，即**拒绝**——厂商在缺 enabled 键时是放行，本项目改掉了
        permission = permission == null ? AssistantPermission.from(null) : permission;
    }

    /** 是否可问数。只有 online 才行。 */
    public boolean isOnline() {
        return status == Status.ONLINE;
    }

    public String displayName() {
        return (name == null || name.isBlank()) ? id : name;
    }

    /** 全部绑定域：主域在前，去重。 */
    public List<String> allDomainIds() {
        List<String> out = new java.util.ArrayList<>();
        if (primaryDomainId != null && !primaryDomainId.isBlank()) {
            out.add(primaryDomainId);
        }
        domainIds.stream().filter(d -> !out.contains(d)).forEach(out::add);
        return List.copyOf(out);
    }

    /** 是否绑定了多个域。多域时界面要让用户选，而不是替它猜。 */
    public boolean hasMultipleDomains() {
        return allDomainIds().size() > 1;
    }
}

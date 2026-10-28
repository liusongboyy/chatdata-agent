package com.liusongboyy.chatdata.engine.permission;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 列级规则：某个角色看某列时被打码。
 *
 * <p>对应厂商 {@code perm_json.colRules[]}。
 *
 * <p><strong>⚠️ 这套模型有一个产品层面的缺口，代码修不了。</strong>
 * ADR-0002 实测：同一业务口径在语义层可能存在多个等价字段，
 * 例如 {@code premium} 与 {@code premium_total} 都表示"保费"。
 * 规则配的是 {@code field: premium}，而 {@code premium_total} 是**另一个列**——
 * 厂商行为正确（规则本就不该覆盖它），但**受限制的用户换个等价指标就能看到明文**。
 *
 * <p>故：
 * <ul>
 *   <li>{@link ColumnMasker} 只负责按配置打码，不负责发现等价字段</li>
 *   <li>等价字段的提示必须在**语义建模**侧做（PRD §7 修正缺口之三），
 *       见 {@code semantic} 包的字段注册校验</li>
 * </ul>
 *
 * @param id     规则标识
 * @param role   适用角色，逐字符精确匹配
 * @param field  列名
 * @param action 动作。厂商目前只有"脱敏"一种；未知取值一律按**脱敏**处理（失败关闭）
 */
public record ColRule(long id, String role, String field, String action) {

    /** 厂商配置里"脱敏"的中文取值。 */
    private static final String ACTION_MASK = "脱敏";

    /**
     * 已知的**不**脱敏动作。目前为空——厂商只实现了"脱敏"一种。
     *
     * <p>将来若新增"明文可见"之类的动作，加到这里。**白名单式**：
     * 不在此集合里的一律脱敏，避免"不认识的取值就放行"造成默认泄露。
     */
    private static final java.util.Set<String> NON_MASKING_ACTIONS = java.util.Set.of();

    public static ColRule from(JsonNode n) {
        JsonNode action = n.path("action");
        return new ColRule(
                n.path("id").asLong(0L),
                text(n, "role"),
                text(n, "field"),
                action.isMissingNode() || action.isNull() ? null : action.asText());
    }

    /**
     * 是否应当脱敏。
     *
     * <p><strong>白名单式判断：不认识的取值一律脱敏（失败关闭）。</strong>
     * 厂商配置里只见过「脱敏」一种取值，若将来新增了别的动作而我们不认识，
     * 按"不认识就放行"处理等于默认泄露。宁可多打码——多打码用户会来问，
     * 少打码没人会发现。
     */
    public boolean shouldMask() {
        if (action == null || action.isBlank()) {
            return true;
        }
        return !NON_MASKING_ACTIONS.contains(action.trim());
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}

package com.liusongboyy.chatdata.engine.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 一个可用的模型。
 *
 * <h2>为什么价格允许为空</h2>
 *
 * <p>有些部署在私有网络里用开源自建模型（vLLM / Ollama），没有"单价"这个概念。
 * 它们应该能用，只是算不出钱——看板显示「未配单价」而不是 0。
 *
 * <p><b>0 元比"不知道"危险得多</b>：它会让成本看起来不存在。
 *
 * @param key           稳定标识。审计里存它，**改了会让历史成本统计断链**
 * @param displayName   给用户看的名字
 * @param description   用途说明，写给人看而不是写参数
 * @param baseUrl       OpenAI 兼容接口地址。**这是区分供应商的唯一判据**
 * @param modelName     供应商侧的模型名
 * @param allowedRoles  可用角色。**空列表表示不限制**——
 *                      不是"谁都不可用"，后者会让新加的模型默认隐身
 * @param priceInput    输入单价（元/百万 token），可空
 * @param priceCached   缓存命中的输入单价，可空
 * @param priceOutput   输出单价，可空
 * @param pricedAt      这套单价是哪天生效的。价格会变，看板上要能说明
 * @param isDefault     是否全局默认。**全局只有一个**
 * @param enabled       是否启用。停用而不是删除——审计里引用了这个 key
 */
public record ModelDef(
        String key,
        String displayName,
        String description,
        String baseUrl,
        String modelName,
        List<String> allowedRoles,
        BigDecimal priceInput,
        BigDecimal priceCached,
        BigDecimal priceOutput,
        LocalDateTime pricedAt,
        boolean isDefault,
        boolean enabled
) {

    public ModelDef {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("model_key 不能为空");
        }
        allowedRoles = allowedRoles == null ? List.of() : List.copyOf(allowedRoles);
    }

    /** 没配单价时算不出钱。三个都要有才算配全。 */
    public boolean priced() {
        return priceInput != null && priceOutput != null;
    }

    /**
     * 某个角色能不能用这个模型。
     *
     * <p><b>空白名单 = 不限制</b>，这是刻意的：新加的模型不该默认对所有人隐身，
     * 那会让管理员配完以为没生效。
     */
    public boolean allowRole(String role) {
        return allowedRoles.isEmpty() || allowedRoles.contains(role);
    }
}

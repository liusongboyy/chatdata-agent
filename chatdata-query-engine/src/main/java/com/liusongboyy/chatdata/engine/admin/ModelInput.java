package com.liusongboyy.chatdata.engine.admin;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 模型的创建/更新入参。
 *
 * <p><strong>⚠️ 字段名必须与前端逐字符一致。</strong>Spring Boot 默认静默忽略未知字段，
 * 拼错一个名字的后果是那个字段变成 null、对应校验被整个跳过——而校验代码看起来还在。
 * 与 {@link DatasourceInput} 是同一个坑，那里记了完整经过。
 *
 * <h2>为什么单价允许全空但不允许配一半</h2>
 *
 * <p>全空 = "这个模型算不出钱"（自建模型、内部署），是合法状态；
 * 成本看板会显示「未配单价」而不是 0。
 *
 * <p>配一半（只填输入、不填输出）则是<b>算得出钱但算错</b>——
 * 那种错不会报任何异常，只会让某个月的账单少一截。所以必须拒。
 *
 * @param key          稳定标识。**创建后不可改**——审计里存的是它，改了历史成本统计会断链
 * @param displayName  给用户看的名字，如「DeepSeek V4 Flash（快速）」
 * @param description  用途说明，写给人看而不是写参数
 * @param baseUrl      OpenAI 兼容接口地址。**这是区分供应商的唯一判据**
 * @param apiKey       明文密钥。**更新时为 null 表示不改**——
 *                     管理页回显不了密钥（也不该回显），留空必须解释成"不动"
 * @param modelName    供应商侧的模型名
 * @param allowedRoles 可用角色码。**空列表表示不限制**——
 *                     不是"谁都不可用"，后者会让新加的模型默认隐身
 * @param priceInput   输入单价（元/百万 token），可空
 * @param priceCached  缓存命中的输入单价，可空
 * @param priceOutput  输出单价，可空
 * @param isDefault    是否设为全局默认
 * @param enabled      是否启用
 */
public record ModelInput(
        String key,
        String displayName,
        String description,
        String baseUrl,
        String apiKey,
        String modelName,
        List<String> allowedRoles,
        BigDecimal priceInput,
        BigDecimal priceCached,
        BigDecimal priceOutput,
        boolean isDefault,
        boolean enabled
) {

    /**
     * 键的字符集。
     *
     * <p>限死成小写字母/数字/下划线/连字符：它会进审计表、进 URL 参数、
     * 被手工写进 SQL 查询成本。允许空格或中文会在这三处各出一次问题，
     * 而每处的表现都不一样。
     */
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    /** base URL 只认 http/https——否则会得到一个永远连不上的配置，报错在几十秒之后。 */
    private static final Pattern URL_PATTERN = Pattern.compile("https?://.+");

    /**
     * 校验并归一化。**在系统边界做。**
     *
     * @param requireKey 创建时为 true（键必填且不可改）；更新时为 false（键从路径来）
     */
    public ModelInput validated(boolean requireKey) {
        if (requireKey) {
            require(key, "模型键");
            if (!KEY_PATTERN.matcher(key.trim()).matches()) {
                throw new AdminException(
                        "模型键只能是小写字母、数字、下划线或连字符，且不超过 32 个字符"
                                + "（它是审计里的标识，不能有空格或中文）。当前值：" + key);
            }
        }
        require(displayName, "显示名");
        require(baseUrl, "接口地址");
        require(modelName, "模型名");

        String url = baseUrl.trim();
        if (!URL_PATTERN.matcher(url).matches()) {
            throw new AdminException("接口地址要以 http:// 或 https:// 开头。当前值：" + url);
        }

        validatePrices(priceInput, priceCached, priceOutput);

        return new ModelInput(
                key == null ? null : key.trim(),
                displayName.trim(),
                description == null ? null : description.trim(),
                url,
                // 密钥不 trim：有些供应商的密钥里确实带空白，改掉就连不上了。
                // 空串归一成 null——它表示"清空"，与"不改"不同
                apiKey == null || apiKey.isEmpty() ? null : apiKey,
                modelName.trim(),
                normalizeRoles(allowedRoles),
                priceInput, priceCached, priceOutput,
                isDefault, enabled);
    }

    /**
     * 单价校验。
     *
     * <h2>两条规则，各有其后果</h2>
     *
     * <p><b>① 要么全配、要么全不配。</b>配一半会让成本"算得出但算错"——
     * 输入有价、输出按 0，那个数字看起来完全正常，只是少了输出那部分。
     *
     * <p><b>② 配了就必须大于 0。</b>0 元会让成本显示成"免费"，
     * 而免费与"不知道"是两件事：前者会让人以为这个模型随便用，
     * 后者会让人去找管理员配单价。0 元传递的是错误信息。
     */
    private static void validatePrices(BigDecimal input, BigDecimal cached, BigDecimal output) {
        boolean any = input != null || cached != null || output != null;
        if (!any) {
            return;   // 全空合法：自建模型没有单价这个概念
        }
        if (input == null || output == null) {
            throw new AdminException(
                    "单价要么全配（输入、输出必填），要么全不配。"
                            + "配一半会让成本算成半对，比不配更难查");
        }
        requirePositive(input, "输入单价");
        requirePositive(cached, "缓存单价");
        requirePositive(output, "输出单价");
    }

    private static void requirePositive(BigDecimal v, String label) {
        if (v != null && v.compareTo(BigDecimal.ZERO) <= 0) {
            throw new AdminException(label + "必须大于 0。不配单价请全部留空——"
                    + "0 元会让看板把它显示成「免费」，而它其实是「不知道」");
        }
    }

    /**
     * 角色列表归一化：去空白、去重、去掉空串。
     *
     * <p><b>不校验角色码是否真实存在。**那需要 IAM，而 IAM 不可用时
     * 整个保存就该失败吗？不该——那是可用性问题，不是正确性问题。
     * 填错的角色码后果是"这个模型这个角色选不到"，界面上看得见，
     * 且管理员能自己发现（下拉里根本选不到那个角色）。
     */
    private static List<String> normalizeRoles(List<String> roles) {
        if (roles == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String r : roles) {
            if (r != null && !r.isBlank()) {
                out.add(r.trim());
            }
        }
        return List.copyOf(out);
    }

    private static void require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new AdminException(label + "不能为空");
        }
    }
}

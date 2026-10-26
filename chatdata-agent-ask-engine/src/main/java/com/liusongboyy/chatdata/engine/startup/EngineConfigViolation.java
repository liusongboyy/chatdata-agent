package com.liusongboyy.chatdata.engine.startup;

/**
 * 一条配置违规。
 *
 * @param category 类别。**报错信息按它分组**——运维看到"3 个域没有授权"和
 *                 "2 个域的关联引用不存在的表"，处理方式完全不同
 * @param domainId 域 id，加载失败时可能为空
 * @param domainName 域名称，用于人工定位
 * @param message 具体说明，必须能直接指导怎么改
 */
public record EngineConfigViolation(
        Category category,
        String domainId,
        String domainName,
        String message
) {

    /**
     * 违规类别。
     *
     * <p>严重度是**类别的属性而不是单条的**：{@code 未设置授权}永远是 ERROR，
     * {@code 授权里的角色不存在}永远是 WARNING。放在类别上比在每条违规上带一个
     * 字段更难用错，也不必给调用点加参数。
     */
    public enum Category {

        /**
         * 域加载失败：perm_json / columns_json 格式坏、聚合方式非法、
         * SQL 虚拟表没有定义等。**该域完全不可用**。
         */
        DOMAIN_NOT_LOADABLE("域加载失败", Severity.ERROR),

        /**
         * 没有设置任何有效授权。
         *
         * <p>这是 ADR-0002 的 R1：厂商语义下"授权为空 = 对所有已认证用户开放"，
         * 失败形态是静默的——不报错，只是数据都能看到。
         */
        NO_GRANT("未设置访问授权", Severity.ERROR),

        /** 绑定的数据源不存在，或数据源类型没有对应的 SQL 方言。 */
        DATASOURCE_UNUSABLE("数据源不可用", Severity.ERROR),

        /** 域内没有任何对 LLM 可见的表——这个域问不出任何东西。 */
        NO_VISIBLE_TABLE("没有可用的表", Severity.ERROR),

        /** 表关联引用了域内不存在的注册表 id。 */
        BROKEN_JOIN_REF("表关联引用无效", Severity.ERROR),

        /** 域内没有任何可用于问数的字段。 */
        NO_FIELD("没有可用字段", Severity.ERROR),

        /**
         * 授权名单（{@code grantRoles} / {@code unrestrictedRoles}）里写了
         * 角色目录中不存在或已停用的角色。
         *
         * <p>后果是**该授权永远不生效**——本该有权限的人没有。属于"少给了权限"，
         * 用户会来报障，所以是 WARNING 而不是拒绝启动：把一个打字错误升级成
         * 起不来，代价与收益不成比例。
         */
        DANGLING_GRANT_ROLE("授权里的角色不存在", Severity.WARNING),

        /**
         * 行/列规则（{@code rowRules} / {@code colRules}）里写了
         * 角色目录中不存在或已停用的角色。
         *
         * <p><strong>这条比上一条严重，故为 ERROR。</strong>规则是用来**限制**的，
         * 角色匹配不上意味着限制整条不生效：本意"区域经理只能看华东"，
         * 结果区域经理看到全部。这是**静默的数据过度暴露**——
         * 与 ADR-0002 记的「等价字段脱敏遗漏」同一类，也是 docs/permission-config.md
         * 第五节的坑⑤之外另一个静默失效点。
         */
        DANGLING_RULE_ROLE("行列规则里的角色不存在", Severity.ERROR),

        /**
         * 助理**用不起来**：没绑域、绑的域不存在，或授权里的角色在域层没有访问权。
         *
         * <p>表现为"看得见助理、点进去被拒"，而拒绝信息是"您没有该数据域的访问权限"——
         * 用户完全不知道问题出在配置上。属于配置不一致，不泄露数据，故 WARNING。
         */
        ASSISTANT_DEAD_END("助理无法真正使用", Severity.WARNING),

        /**
         * 直连模式（RAW）的域里配了行级/列级规则。
         *
         * <p>该模式由模型自己写 SQL，规则**没有安全的注入点**（ADR-0002 已否决
         * 解析后注入谓词），所以配了也不会生效。**不生效的"限制"比没有更危险**：
         * 管理员以为自己保护了数据，实际没有。故为 ERROR。
         */
        RAW_DOMAIN_WITH_RULES("直连模式配了行/列规则", Severity.ERROR),

        /**
         * 派生指标（比值 / 差值 / 时长）的输入引用解析不到。
         *
         * <p>表现为"这个指标问不出来"，而报错发生在用户提问时——离配置现场很远。
         * 并且它常常只坏一半：同域的普通指标全都正常，只有这一个不可用，
         * 比整域不可用更难被发现。故为 ERROR。
         */
        BROKEN_DERIVED_METRIC("派生指标引用无效", Severity.ERROR),

        /**
         * 带条件的指标里，条件字段解析不到。
         *
         * <p>与派生指标同一个道理：条件字段写错时指标仍然"能问"，
         * 只是算出来是空集或报 Unknown column，而错因离配置现场很远。
         */
        BROKEN_METRIC_CONDITION("指标统计条件无效", Severity.ERROR);

        /** 严重度。 */
        public enum Severity {
            /** 配置错误：计入失败，配合 {@code fail-on-violation} 可直接拒绝启动。 */
            ERROR,
            /** 值得处理但不该拦住启动：只记日志。 */
            WARNING
        }

        private final String label;
        private final Severity severity;

        Category(String label, Severity severity) {
            this.label = label;
            this.severity = severity;
        }

        public String label() {
            return label;
        }

        public Severity severity() {
            return severity;
        }

        /** 是否计入启动失败。 */
        public boolean isFatal() {
            return severity == Severity.ERROR;
        }
    }

    /** 一行可读描述，用于启动日志。 */
    public String describe() {
        return "[%s] %s (id=%s)：%s".formatted(category.label(), domainName, domainId, message);
    }
}

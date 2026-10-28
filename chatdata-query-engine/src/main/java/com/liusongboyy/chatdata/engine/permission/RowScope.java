package com.liusongboyy.chatdata.engine.permission;

/**
 * 主体在一个语义域内**能看到的行范围**。
 *
 * <h2>为什么要有这个类型</h2>
 *
 * <p>厂商的 {@code rowRules} 是「按角色授予限制」的模型：某角色**没有匹配的规则**时，
 * 不附加任何过滤条件。ADR-0002 把这条定性为**模型的固有语义，不是缺陷**，
 * 并要求靠配置纪律兜住：
 *
 * <blockquote>
 *   <b>R2：被授予域访问权即等于域内全量可见，除非另有 {@code rowRules} 约束。</b>
 *   授予访问权时必须是有意为之。
 * </blockquote>
 *
 * <p>本项目**保留这个语义**（改掉它会让所有未配行规则的合法配置直接不可用），
 * 但做两件事让它不再是**静默**的：
 *
 * <ol>
 *   <li>把"无规则"与"显式授予全量"分成两种类型（{@link ImplicitUnrestricted} 与
 *       {@link Unrestricted}），**审计可区分**；</li>
 *   <li>隐式全量在审计中单独标记，并计入 {@code PermissionDecision.Allowed}，
 *       使"谁在靠隐式全量看数据"可被查询。</li>
 * </ol>
 *
 * <p><strong>注意本项目真正修掉的默认放行不在这里</strong>，而在
 * {@link DomainPermission#grantsAccess}（授权为空时厂商对所有人开放）与
 * {@link AssistantPermission#from}（缺 {@code enabled} 时厂商对任何角色放行）。
 */
public sealed interface RowScope {

    /**
     * 可见全部行，且是**显式授予**的——角色列在 {@code unrestrictedRoles} 里。
     *
     * <p>这是管理员明确写下的意图，审计按正常放行记录。
     */
    record Unrestricted() implements RowScope {
    }

    /**
     * 可见全部行，但**没有匹配的行规则**——即厂商的固有语义。
     *
     * <p>与 {@link Unrestricted} 的区别是意图是否被写下。这里没有写下，
     * 所以审计要单独标记：出现大量此类记录，说明管理员在批量授予域访问权时
     * 没有考虑行范围，值得复核。
     */
    record ImplicitUnrestricted() implements RowScope {
    }

    /**
     * 只能看到满足该谓词的行。
     *
     * <p>多条规则（用户有多个角色，或同一角色多条规则）按 **OR** 合并——
     * 用户的可见范围是其所有角色可见范围的并集。这是最小惊讶的选择：
     * 用 AND 合并会让多角色用户比单角色用户看到更少，与直觉相反。
     */
    record Restricted(SqlPredicate predicate) implements RowScope {
    }

    /**
     * 无法确定行范围，必须拒绝。
     *
     * <p>只在**规则存在但无法求值**时产生（如动态变量取不到）。
     * 厂商在此情形下跳过规则并放行全部行——那是我们要修的默认放行缺口之一。
     *
     * @param reason 给管理员看的说明，不含敏感信息
     */
    record Denied(String reason) implements RowScope {
    }

    default boolean isDenied() {
        return this instanceof Denied;
    }

    /** 是否可见全部行（显式或隐式）。 */
    default boolean isUnrestricted() {
        return this instanceof Unrestricted || this instanceof ImplicitUnrestricted;
    }

    /** 是否是"没写下行规则"的隐式全量——审计需要区分这一种。 */
    default boolean isImplicitlyUnrestricted() {
        return this instanceof ImplicitUnrestricted;
    }
}

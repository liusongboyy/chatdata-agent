package com.liusongboyy.chatdata.engine.permission;

import java.util.Set;

/**
 * 一次权限判定的结果。
 *
 * <h2>为什么不沿用厂商的 {@code {"blocked":true}}</h2>
 *
 * <p>ADR-0002 实测：厂商对"被拒绝"返回 **HTTP 200**，响应体是
 * {@code {"blocked":true, "text":"您没有模型[...]权限"}}，而非错误码。
 * 后果是任何基于"有没有抛异常"的判断都会把它当作成功——
 * **审计里"被拒绝"与"正常查了但无数据"会变得无法区分**。
 *
 * <p>本项目改为：拒绝走明确的状态码（HTTP 403），并带上机器可读的
 * {@link Reason}，让审计能单独记一类 {@code BLOCKED}。
 */
public sealed interface PermissionDecision {

    /**
     * 放行。
     *
     * @param rowScope      行范围。**不是 {@code Unrestricted} 就必须注入谓词**，
     *                      调用方不得因为"嫌麻烦"而忽略它
     * @param maskedColumns 需要脱敏的列，空集表示无脱敏
     */
    record Allowed(RowScope rowScope, Set<String> maskedColumns) implements PermissionDecision {
        public Allowed {
            maskedColumns = maskedColumns == null ? Set.of() : Set.copyOf(maskedColumns);
        }
    }

    /**
     * 拒绝。
     *
     * @param reason  机器可读的原因，供审计分类
     * @param message 给用户看的说明。**不得包含内部字段名、角色清单等配置细节**
     */
    record Denied(Reason reason, String message) implements PermissionDecision {
    }

    /** 拒绝原因。审计按这个分类，不要用 message 文本做判断。 */
    enum Reason {
        /** 该助手未对该用户/角色开放 */
        ASSISTANT_NOT_GRANTED,
        /** 该用户/角色没有本数据域的访问权 */
        DOMAIN_NOT_GRANTED,
        /** 有域访问权，但没有任何可确定的行范围（本项目新增的拒绝类型，厂商此处是放行） */
        NO_ROW_SCOPE,
        /** 行规则无法求值（如动态变量取不到）。厂商此处跳过规则并放行 */
        RULE_NOT_EVALUABLE,
        /**
         * 请求的模型不可用：不存在、已停用、超出角色白名单，或被域绑定限制。
         *
         * <p><b>与数据权限分开单列。</b>两者都表现为"不能用"，但处理方式完全不同：
         * 数据权限要找管理员开权限，模型问题只要换一个模型重试即可。
         * 混成一类，用户会被引导去做一件没有用的事。
         */
        MODEL_NOT_ALLOWED
    }

    default boolean isAllowed() {
        return this instanceof Allowed;
    }

    default boolean isDenied() {
        return this instanceof Denied;
    }
}

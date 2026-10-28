package com.liusongboyy.chatdata.engine.permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 权限判定的唯一入口。
 *
 * <p>判定顺序：**助手 → 域 → 行范围 → 列脱敏**。前三步任一拒绝即整体拒绝。
 *
 * <p>之所以是"唯一入口"：权限一旦有多条调用路径，就一定会有一条忘了加检查。
 * 上层（{@code nlsql} 渲染 SQL 之前）只应通过本类拿结论，不要自己拼判断。
 *
 * <p>本类**无状态**，可安全并发使用。
 */
public final class PermissionGuard {

    private static final Logger log = LoggerFactory.getLogger(PermissionGuard.class);

    private PermissionGuard() {
    }

    /**
     * 判定主体能否在指定助手下访问指定域，以及能看哪些行、哪些列要脱敏。
     *
     * @param assistant 助手权限；传 null 表示本次请求不经过助手（如直连 API）
     * @param domain    域权限，不可为 null
     * @param ctx       判定主体
     */
    public static PermissionDecision check(AssistantPermission assistant,
                                           DomainPermission domain,
                                           PermissionContext ctx) {
        // ① 助手层。注意判据与域层不同：开关必须打开，且主体在名单内
        if (assistant != null && !assistant.grantsAccess(ctx)) {
            // 日志记详细原因供运维排查；返回给用户的 message 不带配置细节
            log.warn("助手层拒绝：userId={} roles={} enabled={}",
                    ctx.userId(), ctx.roles(), assistant.enabled());
            return new PermissionDecision.Denied(
                    PermissionDecision.Reason.ASSISTANT_NOT_GRANTED,
                    "您没有该助理的访问权限");
        }

        // ② 域层。厂商在此处"授权为空 = 对所有已认证用户开放"，我们改为拒绝
        if (!domain.grantsAccess(ctx)) {
            log.warn("域层拒绝：userId={} roles={} hasAnyGrant={}",
                    ctx.userId(), ctx.roles(), domain.hasAnyGrant());
            return new PermissionDecision.Denied(
                    PermissionDecision.Reason.DOMAIN_NOT_GRANTED,
                    "您没有该数据域的访问权限");
        }

        // ③ 行范围
        RowScope scope;
        try {
            scope = RowRuleCompiler.compile(domain, ctx);
        } catch (RowRuleCompiler.RuleNotEvaluableException e) {
            // 厂商此处跳过规则并放行全部行；本项目拒绝
            log.error("行规则无法求值，拒绝本次查询：userId={} 原因={}", ctx.userId(), e.getMessage());
            return new PermissionDecision.Denied(
                    PermissionDecision.Reason.RULE_NOT_EVALUABLE,
                    "数据权限配置无法应用，请联系管理员");
        }

        if (scope instanceof RowScope.Denied denied) {
            log.error("行范围无法确定，拒绝本次查询：userId={} roles={} 详情={}",
                    ctx.userId(), ctx.roles(), denied.reason());
            return new PermissionDecision.Denied(
                    PermissionDecision.Reason.NO_ROW_SCOPE,
                    "您在该数据域内没有可访问的数据范围，请联系管理员");
        }

        // 隐式全量：厂商语义（有域访问权即全量可见），保留但**不静默**——
        // 审计据此可查出"谁在靠没配行规则看全量数据"，这是配置复核的入口
        if (scope.isImplicitlyUnrestricted()) {
            log.warn("隐式全量行可见：userId={} roles={} —— 该角色有域访问权但未配置行级规则，"
                    + "按厂商语义可见全部行。若非有意为之，请补配 rowRules 或列入 unrestrictedRoles",
                    ctx.userId(), ctx.roles());
        }

        // ④ 列脱敏
        Set<String> masked = ColumnMasker.maskedColumns(domain, ctx);

        return new PermissionDecision.Allowed(scope, masked);
    }
}

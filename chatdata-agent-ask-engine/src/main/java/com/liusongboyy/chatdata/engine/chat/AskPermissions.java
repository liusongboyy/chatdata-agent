package com.liusongboyy.chatdata.engine.chat;

import com.liusongboyy.chatdata.engine.agent.AskDeniedException;
import com.liusongboyy.chatdata.engine.permission.PermissionContext;
import com.liusongboyy.chatdata.engine.permission.PermissionDecision;
import com.liusongboyy.chatdata.engine.permission.PermissionGuard;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 问数入口的权限闸门。**两种模式共用**。
 *
 * <h2>为什么必须共用</h2>
 *
 * <p>建模模式与直连模式的差别在执行，不在准入。各写一份判断的话，
 * 分叉一定会出现在权限上——而那是这个项目最不能出错的地方
 * （ADR-0002：一个漏配的域就是一次全量数据暴露）。
 *
 * <p>直连模式尤其要注意：它的行/列规则不生效，所以**域级授权就是唯一的闸门**，
 * 更不能因为"新写了一条链路"而放松。
 */
public final class AskPermissions {

    private static final Logger log = LoggerFactory.getLogger(AskPermissions.class);

    private AskPermissions() {
    }

    /**
     * 判定并放行。被拒时抛 {@link AskDeniedException}（带机器可读的 reason，供审计分类）。
     *
     * <p><strong>必须在任何模型调用之前调用。</strong>无权限的请求不该消耗 LLM 调用，
     * 也不该把语义域的描述（字段清单）暴露给它。
     *
     * @return 放行的判定结果（调用方需要它取行范围与脱敏列）
     * @throws AskDeniedException 被拒
     */
    public static PermissionDecision.Allowed require(Domain domain, AskRequest request) {
        // attributes 来自资源服务器的内省结果（orgCode/orgPath 等），
        // 动态行规则靠它们求值；取不到时 PermissionGuard 会拒绝，而不是跳过规则
        PermissionContext ctx = PermissionContext.of(
                request.userId(), request.roles(), request.attributes());
        PermissionDecision decision = PermissionGuard.check(null, domain.permission(), ctx);

        if (decision instanceof PermissionDecision.Denied denied) {
            log.warn("问数被拒：user={} roles={} mode={} reason={}",
                    request.username(), request.roles(), domain.mode(), denied.reason());
            throw new AskDeniedException(denied.reason(), denied.message());
        }
        return (PermissionDecision.Allowed) decision;
    }
}

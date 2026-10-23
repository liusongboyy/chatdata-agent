package com.liusongboyy.chatdata.engine.startup;

import com.liusongboyy.chatdata.engine.permission.ColRule;
import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import com.liusongboyy.chatdata.engine.permission.RowRule;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 校验权限配置里引用的角色**是否真的存在**。纯逻辑、无 Spring 依赖。
 *
 * <h2>它挡的是什么</h2>
 *
 * <p>{@code docs/permission-config.md} 第四节记了这个坑，并且记了一个现成的反例：
 * 「演示数据 {@code 系统用户分析域} 的 {@code colRules} 引用了角色 {@code 财务}，
 * 而本项目 {@code sys_role} 里没有这个角色——该规则永远不会生效」，且
 * <b>"角色名不一致 → 规则静默不生效，不报错、不打日志"</b>。
 *
 * <p>换句话说：配置界面点了保存、库里写着规则、而运行时它一条都不匹配。
 * 这不是理论风险——本次排查就撞到一个（`工单问数助手` 的授权里写了
 * 不存在的角色 `管理员`，导致管理员看不到那个助理）。
 *
 * <h2>两类引用的严重度不同，别合并成一条</h2>
 *
 * <table>
 *   <tr><th>引用位置</th><th>失效的后果</th><th>严重度</th></tr>
 *   <tr><td>授权名单 grantRoles / unrestrictedRoles</td>
 *       <td><b>少给</b>了权限——本该能访问的人访问不了，用户会来报障</td>
 *       <td>WARNING</td></tr>
 *   <tr><td>行列规则 rowRules / colRules</td>
 *       <td><b>多给</b>了权限——限制整条不生效，受限角色看到全量数据，
 *           且**没有任何人会发现**</td>
 *       <td>ERROR</td></tr>
 * </table>
 *
 * <p>把两者都当错误会让一个打字错误拦住启动；都当警告则放过了真正的数据暴露。
 *
 * <h2>大小写是敏感的，这里刻意不做归一化</h2>
 *
 * <p>ADR-0002 的实测结论是「角色名逐字符匹配」。既然运行时是逐字符匹配，
 * 启动校验也必须逐字符匹配——若这里宽容地忽略大小写，
 * 就会出现"校验通过、运行时匹配不上"这种最坏的组合。
 */
public final class RoleReferenceValidator {

    private RoleReferenceValidator() {
    }

    /**
     * 校验一个域的角色引用。
     *
     * @param roles 角色码 → 是否启用
     */
    public static List<EngineConfigViolation> validateDomain(DomainPermission perm,
                                                             String id, String name,
                                                             Map<String, Boolean> roles) {
        List<EngineConfigViolation> out = new ArrayList<>();

        // 授权名单：失效 = 少给了权限
        Set<String> grants = new LinkedHashSet<>(perm.grantRoles());
        grants.addAll(perm.unrestrictedRoles());
        for (String role : new TreeSet<>(grants)) {
            String problem = problemOf(role, roles);
            if (problem != null) {
                out.add(new EngineConfigViolation(
                        EngineConfigViolation.Category.DANGLING_GRANT_ROLE, id, name,
                        "%s，该授权不会对任何人生效。"
                                .formatted(problem)
                                + "角色码必须与 IAM 角色目录里的角色逐字符一致"));
            }
        }

        // 行列规则：失效 = 限制了但没生效 = 数据过度暴露
        for (RowRule r : perm.rowRules()) {
            String problem = problemOf(r.role(), roles);
            if (problem != null) {
                out.add(new EngineConfigViolation(
                        EngineConfigViolation.Category.DANGLING_RULE_ROLE, id, name,
                        "行规则 %d（字段 %s）写了%s，**这条限制不会生效**——"
                                .formatted(r.id(), r.field(), problem)
                                + "本该被限行的角色将看到全部行。可用角色见 IAM 角色目录"));
            }
        }
        for (ColRule c : perm.colRules()) {
            String problem = problemOf(c.role(), roles);
            if (problem != null) {
                out.add(new EngineConfigViolation(
                        EngineConfigViolation.Category.DANGLING_RULE_ROLE, id, name,
                        "列规则 %d（字段 %s，动作 %s）写了%s，**这条限制不会生效**——"
                                .formatted(c.id(), c.field(), c.action(), problem)
                                + "本该被脱敏/隐藏的角色将看到明文。可用角色见 IAM 角色目录"));
            }
        }
        return out;
    }

    /**
     * 校验一个助理的角色引用。
     *
     * <p>助理层只有授权名单，没有行列规则——它的判据是 {@code enabled} 开关
     * 加上名单（见 {@code AssistantPermission}），所以这里只报 WARNING。
     */
    public static List<EngineConfigViolation> validateAssistant(boolean enabled,
                                                                Set<String> grantRoles,
                                                                String id, String name,
                                                                Map<String, Boolean> roles) {
        List<EngineConfigViolation> out = new ArrayList<>();
        if (!enabled) {
            // 未启用的助理本来就不对任何人开放，名单写错也无所谓
            return out;
        }
        for (String role : new TreeSet<>(grantRoles == null ? Set.of() : grantRoles)) {
            String problem = problemOf(role, roles);
            if (problem != null) {
                out.add(new EngineConfigViolation(
                        EngineConfigViolation.Category.DANGLING_GRANT_ROLE, id, name,
                        "助理的授权名单里%s，该授权不会对任何人生效。"
                                .formatted(problem)
                                + "角色码必须与 IAM 角色目录里的角色逐字符一致"));
            }
        }
        return out;
    }

    /**
     * 这个角色码有什么问题。
     *
     * @return 问题描述；{@code null} 表示没问题
     */
    private static String problemOf(String role, Map<String, Boolean> roles) {
        if (role == null || role.isBlank()) {
            // 空角色名单独说清楚：它同样匹配不上任何主体
            return "空角色名（rowRules / colRules 里的 role 不能为空）";
        }
        Boolean enabled = roles.get(role);
        if (enabled == null) {
            return "角色「%s」不存在".formatted(role);
        }
        if (!enabled) {
            // 存在但停用与不存在要分开：前者是运维动作，后者是打字错误，
            // 两者的处理方式完全不同
            return "角色「%s」已停用".formatted(role);
        }
        return null;
    }
}

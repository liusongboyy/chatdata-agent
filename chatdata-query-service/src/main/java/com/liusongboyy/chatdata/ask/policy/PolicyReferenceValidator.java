package com.liusongboyy.chatdata.ask.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liusongboyy.chatdata.engine.admin.AdminException;
import com.liusongboyy.chatdata.engine.permission.AssistantPermission;
import com.liusongboyy.chatdata.engine.permission.ColRule;
import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import com.liusongboyy.chatdata.engine.permission.RowRule;
import com.liusongboyy.chatdata.iam.client.IamRoleDirectoryClient;
import com.liusongboyy.chatdata.iam.client.IamUserDirectoryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 保存数据域/助理策略时，对引用到的角色码与 username 做 **best-effort** 校验。
 *
 * <h2>为什么在保存时校验</h2>
 *
 * <p>角色码写错时引擎不会报错，只是规则永远匹配不上：
 * 授权名单写错是"少给了权限"，行/列规则写错是"限制没生效"——后者会让受限角色
 * 看到全量或明文，而界面上与配置正确时长得一模一样。启动校验能兜住，但那要等到
 * 下一次重启，且会拦住整个应用。能在保存时发现就不要推给启动。
 *
 * <h2>为什么叫 best-effort</h2>
 *
 * <p>角色/用户目录在 IAM。IAM 抖动时**不能阻断保存**：否则一次目录故障会让
 * 整个管理台保存不了，而管理员改的可能只是一个描述。拿不到目录就记 WARNING
 * 并放行，把最终判据留给启动校验与运行时 fail-closed。
 *
 * <p>停用的角色/用户**不阻断**：它们可能存在，只是当前不生效。这里记 WARNING，
 * 与前端"标黄提示但不拦保存"的取舍一致。
 */
@Component
public class PolicyReferenceValidator {

    private static final Logger log = LoggerFactory.getLogger(PolicyReferenceValidator.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final IamRoleDirectoryClient roleDirectory;
    private final IamUserDirectoryClient userDirectory;

    public PolicyReferenceValidator(IamRoleDirectoryClient roleDirectory,
                                    IamUserDirectoryClient userDirectory) {
        this.roleDirectory = roleDirectory;
        this.userDirectory = userDirectory;
    }

    /** 校验数据域策略。空/坏 JSON 不在这里报——由 DomainInput 给出更准确的错误。 */
    public void validateDomain(String permJson) {
        if (blank(permJson)) return;
        JsonNode json;
        try {
            json = JSON.readTree(permJson);
        } catch (Exception e) {
            return;
        }
        DomainPermission perm = DomainPermission.from(json);

        checkRowRuleValues(perm);

        Set<String> roles = new LinkedHashSet<>(perm.grantRoles());
        roles.addAll(perm.unrestrictedRoles());
        for (RowRule r : perm.rowRules()) add(roles, r.role());
        for (ColRule c : perm.colRules()) add(roles, c.role());

        check(roles, perm.grantUsers());
    }

    /** 引擎认识的动态变量名。静态规则的取值写成这些名字，必然是漏勾了「动态」。 */
    private static final Set<String> KNOWN_SYS_VARS =
            Set.of("orgCode", "orgPath", "orgPaths", "userCode", "userId");

    /**
     * 行规则的取值语义。
     *
     * <p><strong>拦的是哪一种错：</strong>管理员想配动态规则，却把变量名
     * {@code orgPaths} 填进了「条件值」，同时没勾「动态」。保存能过——
     * 校验只要求"静态规则必须有值"，而它确实有值。
     *
     * <p>后果是**静默的**：渲染出 {@code org_path LIKE 'orgPaths%'}，
     * 匹配不到任何行，用户看到 0 条数据，既没有报错也没有提示。
     * 只有把 SQL 摊开看才能发现，而那通常已经过了很久。
     *
     * <p>为什么直接拒绝而不是警告：一个组织路径字段的取值恰好等于
     * {@code orgPaths} 这种字面量，现实中不存在；而放过去的代价是
     * 数据静默不可见。两侧不对等，故选拒绝。
     */
    private static void checkRowRuleValues(DomainPermission perm) {
        for (RowRule r : perm.rowRules()) {
            if (r.dynamic()) {
                continue;
            }
            String value = r.value() == null ? "" : r.value().trim();
            if (KNOWN_SYS_VARS.contains(value)) {
                throw new AdminException(
                        ("行规则 %d 的条件值写的是「%s」——这是一个动态变量名，不是取值。"
                                + "你是想按当前登录用户的范围过滤吧？请勾选「动态」并在变量里选 %s；"
                                + "否则这条规则会拼成 LIKE '%s%%'，一条数据都匹配不到。")
                                .formatted(r.id(), value, value, value));
            }
        }
    }

    /** 校验助理策略。 */
    public void validateAssistant(String permJson) {
        if (blank(permJson)) return;
        JsonNode json;
        try {
            json = JSON.readTree(permJson);
        } catch (Exception e) {
            return;
        }
        AssistantPermission perm = AssistantPermission.from(json);
        check(perm.grantRoles(), perm.grantUsers());
    }

    private void check(Set<String> roles, Set<String> users) {
        checkRoles(roles);
        checkUsers(users);
    }

    private void checkRoles(Set<String> roles) {
        if (roles.isEmpty()) return;

        Map<String, Boolean> catalog;
        try {
            catalog = roleDirectory.roles().stream()
                    .collect(Collectors.toMap(
                            IamRoleDirectoryClient.RoleEntry::code,
                            IamRoleDirectoryClient.RoleEntry::enabled,
                            (a, b) -> a));
        } catch (RuntimeException e) {
            log.warn("IAM 角色目录不可用，跳过策略的角色引用校验：{}", e.getMessage());
            return;
        }

        List<String> missing = roles.stream().filter(r -> !catalog.containsKey(r)).toList();
        if (!missing.isEmpty()) {
            throw new AdminException(
                    "权限配置引用了不存在的角色：%s。请从 IAM 角色目录中选择，角色码需逐字符一致。"
                            .formatted(String.join("、", missing)));
        }

        List<String> disabled = roles.stream()
                .filter(r -> Boolean.FALSE.equals(catalog.get(r)))
                .toList();
        if (!disabled.isEmpty()) {
            log.warn("权限配置引用了已停用的角色，相关规则不会生效：{}", String.join("、", disabled));
        }
    }

    private void checkUsers(Set<String> users) {
        if (users.isEmpty()) return;

        Set<String> found;
        try {
            found = userDirectory.usersByNames(users).stream()
                    .map(IamUserDirectoryClient.UserEntry::username)
                    .collect(Collectors.toSet());
        } catch (RuntimeException e) {
            log.warn("IAM 用户目录不可用，跳过策略的用户引用校验：{}", e.getMessage());
            return;
        }

        List<String> missing = users.stream().filter(u -> !found.contains(u)).toList();
        if (!missing.isEmpty()) {
            throw new AdminException(
                    "权限配置引用了不存在的用户：%s。grantUsers 存的是 username，不是显示名。"
                            .formatted(String.join("、", missing)));
        }
    }

    private static void add(Set<String> out, String value) {
        if (value != null && !value.isBlank()) out.add(value);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

package com.liusongboyy.chatdata.engine.startup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liusongboyy.chatdata.engine.permission.AssistantPermission;
import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 角色引用校验的测试。
 *
 * <p>要防的是一个已经发生过的静默失效：权限配置里写了不存在的角色，
 * 界面保存成功、库里存着规则、而运行时一条都不匹配。
 * 角色名是逐字符匹配的，本次排查撞到过
 * 「助理授权里写了不存在的角色 {@code 管理员}」。
 */
class RoleReferenceValidatorTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** sys_role 里实际存在的角色。 */
    private static final Map<String, Boolean> ROLES = Map.of(
            "区域经理", true,
            "分析师", true,
            "停用角色", false);

    private static DomainPermission perm(String json) {
        try {
            return DomainPermission.from(M.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException("夹具 JSON 写错", e);
        }
    }

    private static List<EngineConfigViolation.Category> categories(List<EngineConfigViolation> vs) {
        return vs.stream().map(EngineConfigViolation::category).toList();
    }

    @Nested
    @DisplayName("行列规则里的角色失效 —— 严重：限制了但没生效")
    class RuleRoles {

        @Test
        @DisplayName("★ 行规则写了不存在的角色 → ERROR，消息要说清是「限制没生效」")
        void danglingRowRuleRoleIsFatal() {
            DomainPermission p = perm("""
                    {"v":1,"grantRoles":["区域经理"],
                     "rowRules":[{"id":7,"role":"区域经济","field":"region","op":"=","value":"华东"}]}""");

            List<EngineConfigViolation> vs =
                    RoleReferenceValidator.validateDomain(p, "d1", "保险分析域", ROLES);

            assertThat(vs).hasSize(1);
            EngineConfigViolation v = vs.get(0);
            assertThat(v.category()).isEqualTo(EngineConfigViolation.Category.DANGLING_RULE_ROLE);
            // 严重度是类别的属性——规则类失效必须拦住启动，否则是静默的数据过度暴露
            assertThat(v.category().isFatal()).isTrue();
            assertThat(v.message()).contains("区域经济").contains("不会生效").contains("全部行");
        }

        @Test
        @DisplayName("★ 列规则写了不存在的角色 → ERROR，且点明会看到明文")
        void danglingColRuleRoleIsFatal() {
            DomainPermission p = perm("""
                    {"v":1,"grantRoles":["分析师"],
                     "colRules":[{"id":3,"role":"财务","field":"premium","action":"脱敏"}]}""");

            List<EngineConfigViolation> vs =
                    RoleReferenceValidator.validateDomain(p, "d1", "保险分析域", ROLES);

            assertThat(categories(vs))
                    .containsExactly(EngineConfigViolation.Category.DANGLING_RULE_ROLE);
            assertThat(vs.get(0).message()).contains("财务").contains("明文");
        }

        @Test
        @DisplayName("角色存在但已停用 → 与「不存在」分开报，处理方式不同")
        void disabledRoleIsReportedDifferently() {
            DomainPermission p = perm("""
                    {"v":1,"grantRoles":["区域经理"],
                     "rowRules":[{"id":1,"role":"停用角色","field":"region","op":"=","value":"华东"}]}""");

            List<EngineConfigViolation> vs =
                    RoleReferenceValidator.validateDomain(p, "d1", "域", ROLES);

            assertThat(vs).hasSize(1);
            // 指向"已停用"（运维动作），而不是"不存在"（打字错误）
            assertThat(vs.get(0).message()).contains("已停用").doesNotContain("不存在");
        }

        @Test
        @DisplayName("空的 role 也要报——它同样匹配不上任何主体")
        void blankRoleIsReported() {
            DomainPermission p = perm("""
                    {"v":1,"grantRoles":["区域经理"],
                     "rowRules":[{"id":1,"role":"","field":"region","op":"=","value":"华东"}]}""");

            assertThat(RoleReferenceValidator.validateDomain(p, "d1", "域", ROLES))
                    .singleElement()
                    .satisfies(v -> assertThat(v.message()).contains("空角色名"));
        }

        @Test
        @DisplayName("角色都有效 → 零违规")
        void healthyConfigPasses() {
            DomainPermission p = perm("""
                    {"v":1,"grantRoles":["区域经理","分析师"],"unrestrictedRoles":["分析师"],
                     "rowRules":[{"id":1,"role":"区域经理","field":"region","op":"=","value":"华东"}],
                     "colRules":[{"id":2,"role":"分析师","field":"premium","action":"脱敏"}]}""");

            assertThat(RoleReferenceValidator.validateDomain(p, "d1", "域", ROLES)).isEmpty();
        }
    }

    @Nested
    @DisplayName("授权名单里的角色失效 —— 轻微：授权不生效（少给了权限）")
    class GrantRoles {

        @Test
        @DisplayName("grantRoles 里写了不存在的角色 → WARNING，不拦启动")
        void danglingGrantRoleIsWarningOnly() {
            DomainPermission p = perm("""
                    {"v":1,"grantRoles":["区域经理","管理员"]}""");

            List<EngineConfigViolation> vs =
                    RoleReferenceValidator.validateDomain(p, "d1", "保险分析域", ROLES);

            assertThat(vs).singleElement().satisfies(v -> {
                assertThat(v.category()).isEqualTo(EngineConfigViolation.Category.DANGLING_GRANT_ROLE);
                assertThat(v.category().isFatal())
                        .as("一个打字错误不该让整个应用起不来——后果只是某个人少了个权限")
                        .isFalse();
                assertThat(v.message()).contains("管理员");
            });
        }

        @Test
        @DisplayName("unrestrictedRoles 同样在检查范围内 —— 它也是授权")
        void unrestrictedRolesAreChecked() {
            DomainPermission p = perm("""
                    {"v":1,"grantRoles":["区域经理"],"unrestrictedRoles":["老板"]}""");

            assertThat(RoleReferenceValidator.validateDomain(p, "d1", "域", ROLES))
                    .singleElement()
                    .satisfies(v -> assertThat(v.message()).contains("老板"));
        }

        @Test
        @DisplayName("助理：未启用时不报 —— 它本来就不对任何人开放，名单写错无所谓")
        void disabledAssistantIsSkipped() {
            assertThat(RoleReferenceValidator.validateAssistant(
                    false, Set.of("不存在的角色"), "2", "工单助手", ROLES)).isEmpty();
        }

        @Test
        @DisplayName("★ 助理：已启用且名单里有不存在的角色 → WARNING（本次排查撞到的正是这个）")
        void enabledAssistantWithDanglingRole() {
            AssistantPermission perm = AssistantPermission.from(json("""
                    {"enabled":true,"grantRoles":["区域经理","分析师","管理员"],"grantUsers":[]}"""));

            List<EngineConfigViolation> vs = RoleReferenceValidator.validateAssistant(
                    perm.enabled(), perm.grantRoles(), "2", "工单问数助手", ROLES);

            assertThat(vs).singleElement().satisfies(v -> {
                assertThat(v.category().isFatal()).isFalse();
                assertThat(v.message()).contains("管理员").contains("不会对任何人生效");
            });
        }

        private com.fasterxml.jackson.databind.JsonNode json(String s) {
            try {
                return M.readTree(s);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    @DisplayName("大小写敏感：校验必须与运行时同一套判据，否则校验通过了运行时却匹配不上")
    void caseSensitive() {
        DomainPermission p = perm("""
                {"v":1,"grantRoles":["RegionManager"]}""");

        assertThat(RoleReferenceValidator.validateDomain(p, "d1", "域", ROLES))
                .as("ADR-0002 的结论是角色名逐字符匹配，校验不能比运行时更宽容")
                .hasSize(1);
    }

    @Test
    @DisplayName("全部角色都停用：仍然逐条报，不因为「库是空的」而静默跳过")
    void allRolesDisabledStillReports() {
        Map<String, Boolean> allDisabled = Map.of("区域经理", false);
        DomainPermission p = perm("""
                {"v":1,"grantRoles":["区域经理"]}""");

        assertThat(RoleReferenceValidator.validateDomain(p, "d1", "域", allDisabled))
                .singleElement()
                .satisfies(v -> assertThat(v.message()).contains("已停用"));
    }
}

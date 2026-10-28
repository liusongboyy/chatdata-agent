package com.liusongboyy.chatdata.engine.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限判定的测试。
 *
 * <p>核心是**逐条钉死本项目相对厂商的三处修正**：
 * 默认放行改默认拒绝、动态变量取不到不再跳过、助手缺 enabled 不再放行。
 * 每条测试都注明了厂商的行为，便于日后对照。
 */
class PermissionGuardTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** 取自真实环境的一条域权限配置（保险分析域），保持原样以便对照。 */
    private static final String REAL_DOMAIN_JSON = """
            {
              "colRules": [
                {"id": 1, "role": "分析师", "field": "premium", "action": "脱敏"},
                {"id": 2, "role": "分析师", "field": "premium_total", "action": "脱敏"}
              ],
              "isPublic": false,
              "rowRules": [
                {"id": 1, "op": "=", "role": "区域经理", "field": "region",
                 "value": "华东", "sysVar": "", "dynamic": false}
              ],
              "grantRoles": ["区域经理", "分析师"],
              "grantUsers": []
            }""";

    private static DomainPermission domain(String json) throws Exception {
        return DomainPermission.from(M.readTree(json));
    }

    private static PermissionContext user(String... roles) {
        return PermissionContext.of("u1", Set.of(roles));
    }

    @Nested
    @DisplayName("域层闸门")
    class DomainGate {

        @Test
        @DisplayName("授权角色放行")
        void grantsListedRole() throws Exception {
            PermissionDecision d = PermissionGuard.check(null, domain(REAL_DOMAIN_JSON), user("分析师"));

            assertThat(d.isAllowed()).isTrue();
        }

        @Test
        @DisplayName("未授权角色拒绝")
        void deniesUnlistedRole() throws Exception {
            PermissionDecision d = PermissionGuard.check(null, domain(REAL_DOMAIN_JSON), user("路人"));

            assertThat(d).isInstanceOfSatisfying(PermissionDecision.Denied.class,
                    x -> assertThat(x.reason()).isEqualTo(PermissionDecision.Reason.DOMAIN_NOT_GRANTED));
        }

        @Test
        @DisplayName("★ 授权为空时拒绝 —— 厂商此处对所有已认证用户开放")
        void deniesWhenNoGrantAtAll() throws Exception {
            // 厂商行为：grantRoles/grantUsers 皆空且 isPublic=false → 对所有已认证用户开放
            // 本项目：拒绝
            DomainPermission empty = domain("{}");

            assertThat(PermissionGuard.check(null, empty, user("任何人")).isDenied())
                    .as("空授权必须拒绝，这是本项目修正的主要缺口")
                    .isTrue();
        }

        @Test
        @DisplayName("isPublic=true 时放行 —— 公开是显式声明的，不属默认放行")
        void publicDomainAllows() throws Exception {
            DomainPermission pub = domain("{\"isPublic\": true}");

            assertThat(PermissionGuard.check(null, pub, user("任何人")).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("grantUsers 里直接列出的用户放行，不依赖角色")
        void grantsListedUser() throws Exception {
            DomainPermission d = domain("{\"grantUsers\": [\"u1\"]}");

            assertThat(PermissionGuard.check(null, d, user()).isAllowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("助手层闸门 —— 判据与域层不同")
    class AssistantGate {

        @Test
        @DisplayName("enabled=true 且角色在名单内才放行")
        void requiresBothEnabledAndRole() throws Exception {
            DomainPermission dom = domain(REAL_DOMAIN_JSON);

            AssistantPermission ok = AssistantPermission.from(
                    M.readTree("{\"enabled\": true, \"grantRoles\": [\"分析师\"]}"));
            assertThat(PermissionGuard.check(ok, dom, user("分析师")).isAllowed()).isTrue();

            AssistantPermission disabled = AssistantPermission.from(
                    M.readTree("{\"enabled\": false, \"grantRoles\": [\"分析师\"]}"));
            assertThat(PermissionGuard.check(disabled, dom, user("分析师")).isDenied())
                    .as("开关关闭时，名单里有没有该角色都不放行")
                    .isTrue();
        }

        @Test
        @DisplayName("★ 缺 enabled 键时拒绝 —— 厂商此处对任何角色集放行")
        void deniesWhenEnabledKeyMissing() throws Exception {
            // 厂商行为：配置里没有 enabled 键 → 对任何角色集放行，包括空集与不存在的角色
            // 本项目：asBoolean(false) → 拒绝
            AssistantPermission missingEnabled = AssistantPermission.from(
                    M.readTree("{\"grantRoles\": [\"分析师\"]}"));

            assertThat(missingEnabled.enabled()).isFalse();
            assertThat(PermissionGuard.check(missingEnabled, domain(REAL_DOMAIN_JSON), user("分析师")).isDenied())
                    .as("缺 enabled 必须按 false 处理，这是本项目修正的缺口")
                    .isTrue();
        }

        @Test
        @DisplayName("完全无权限配置时拒绝，不是放行")
        void deniesWhenConfigAbsent() throws Exception {
            AssistantPermission absent = AssistantPermission.from(null);

            assertThat(PermissionGuard.check(absent, domain(REAL_DOMAIN_JSON), user("分析师")).isDenied()).isTrue();
        }
    }

    @Nested
    @DisplayName("行范围 —— 本项目与厂商分歧最大处")
    class RowScopeResolution {

        @Test
        @DisplayName("有匹配规则 → Restricted，谓词可直接注入")
        void restrictedWithPredicate() throws Exception {
            PermissionDecision d = PermissionGuard.check(null, domain(REAL_DOMAIN_JSON), user("区域经理"));

            assertThat(d).isInstanceOfSatisfying(PermissionDecision.Allowed.class, x -> {
                assertThat(x.rowScope()).isInstanceOf(RowScope.Restricted.class);
                SqlPredicate p = ((RowScope.Restricted) x.rowScope()).predicate();
                assertThat(p.sql()).isEqualTo("{col:region} = ?");
                assertThat(p.params()).containsExactly("华东");
            });
        }

        @Test
        @DisplayName("有域访问权但无匹配行规则 → 隐式全量，且与显式授予可区分")
        void implicitUnrestrictedWhenNoRowRule() throws Exception {
            // 真实配置里「分析师」在 grantRoles 内，但 rowRules 只有「区域经理」的。
            // 厂商行为：不附加任何过滤条件，分析师看到域内全部行（ADR-0002 实测确认）。
            // 本项目**保留这个语义**——改成拒绝会让所有未配行规则的合法配置直接不可用，
            // 而且 ADR-0002 已把它定性为模型固有语义（R2），不是缺陷。
            // 我们做的是让它不再静默：单独一个类型，审计可区分。
            PermissionDecision d = PermissionGuard.check(null, domain(REAL_DOMAIN_JSON), user("分析师"));

            assertThat(d).isInstanceOfSatisfying(PermissionDecision.Allowed.class, x -> {
                assertThat(x.rowScope().isUnrestricted()).isTrue();
                assertThat(x.rowScope().isImplicitlyUnrestricted())
                        .as("必须能与 unrestrictedRoles 显式授予区分开，否则审计查不出谁在靠隐式全量看数据")
                        .isTrue();
            });
        }

        @Test
        @DisplayName("显式授予与隐式全量是两个不同类型")
        void explicitAndImplicitAreDistinguishable() throws Exception {
            DomainPermission explicit = domain(
                    "{\"grantRoles\": [\"R\"], \"unrestrictedRoles\": [\"R\"]}");
            DomainPermission implicit = domain("{\"grantRoles\": [\"R\"]}");

            RowScope explicitScope = ((PermissionDecision.Allowed)
                    PermissionGuard.check(null, explicit, user("R"))).rowScope();
            RowScope implicitScope = ((PermissionDecision.Allowed)
                    PermissionGuard.check(null, implicit, user("R"))).rowScope();

            assertThat(explicitScope).isInstanceOf(RowScope.Unrestricted.class);
            assertThat(explicitScope.isImplicitlyUnrestricted()).isFalse();
            assertThat(implicitScope).isInstanceOf(RowScope.ImplicitUnrestricted.class);
            assertThat(implicitScope.isImplicitlyUnrestricted()).isTrue();
        }

        @Test
        @DisplayName("显式列入 unrestrictedRoles → 可见全量")
        void explicitUnrestrictedAllows() throws Exception {
            DomainPermission d = domain("""
                    {"grantRoles": ["管理员"], "unrestrictedRoles": ["管理员"], "rowRules": []}""");

            PermissionDecision decision = PermissionGuard.check(null, d, user("管理员"));

            assertThat(decision).isInstanceOfSatisfying(PermissionDecision.Allowed.class,
                    x -> assertThat(x.rowScope().isUnrestricted()).isTrue());
        }

        @Test
        @DisplayName("★ 动态变量取不到 → 拒绝 —— 厂商此处跳过规则并放行全部行")
        void deniesWhenDynamicVarMissing() throws Exception {
            // 厂商行为：sysVar 取不到 → 跳过该条规则 + 打日志，该角色的限制静默消失
            // 本项目：拒绝
            DomainPermission d = domain("""
                    {"grantRoles": ["R"], "rowRules": [
                      {"id":1,"role":"R","field":"org_code","op":"=","sysVar":"orgCode","dynamic":true}
                    ]}""");

            PermissionDecision decision = PermissionGuard.check(null, d, user("R"));

            assertThat(decision).isInstanceOfSatisfying(PermissionDecision.Denied.class,
                    x -> assertThat(x.reason()).isEqualTo(PermissionDecision.Reason.RULE_NOT_EVALUABLE));
        }
    }

    @Nested
    @DisplayName("列脱敏")
    class ColumnMasking {

        @Test
        @DisplayName("按角色返回需脱敏的列")
        void masksPerRole() throws Exception {
            DomainPermission d = domain("""
                    {"grantRoles": ["分析师"],
                     "colRules": [{"id":1,"role":"分析师","field":"premium","action":"脱敏"}]}""");

            PermissionDecision decision = PermissionGuard.check(null, d, user("分析师"));

            assertThat(decision).isInstanceOfSatisfying(PermissionDecision.Allowed.class,
                    x -> assertThat(x.maskedColumns()).containsExactly("premium"));
        }

        @Test
        @DisplayName("无匹配角色时不脱敏")
        void noMaskWithoutMatchingRole() throws Exception {
            DomainPermission d = domain("""
                    {"grantRoles": ["R"], "unrestrictedRoles": ["R"],
                     "colRules": [{"id":1,"role":"别人","field":"premium","action":"脱敏"}]}""");

            PermissionDecision decision = PermissionGuard.check(null, d, user("R"));

            assertThat(decision).isInstanceOfSatisfying(PermissionDecision.Allowed.class,
                    x -> assertThat(x.maskedColumns()).isEmpty());
        }

        @Test
        @DisplayName("未知 action 按脱敏处理（失败关闭）")
        void unknownActionMasksAnyway() {
            ColRule unknown = new ColRule(1L, "R", "premium", "某个将来才有的动作");

            assertThat(unknown.shouldMask())
                    .as("不认识的 action 若按放行处理，等于默认泄露")
                    .isTrue();
        }
    }
}

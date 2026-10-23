package com.liusongboyy.chatdata.ask.policy;

import com.liusongboyy.chatdata.engine.admin.AdminException;
import com.liusongboyy.chatdata.iam.client.IamRoleDirectoryClient;
import com.liusongboyy.chatdata.iam.client.IamUserDirectoryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 保存时的策略引用校验。
 *
 * <p>重点不是"能查到"，而是**查不到时怎么办**：角色/用户不存在必须拦住保存；
 * IAM 自己不可用必须放行（否则目录抖动会让整个管理台保存不了）。
 */
class PolicyReferenceValidatorTest {

    private IamRoleDirectoryClient roleDirectory;
    private IamUserDirectoryClient userDirectory;
    private PolicyReferenceValidator validator;

    @BeforeEach
    void setUp() {
        roleDirectory = mock(IamRoleDirectoryClient.class);
        userDirectory = mock(IamUserDirectoryClient.class);
        validator = new PolicyReferenceValidator(roleDirectory, userDirectory);
    }

    private static IamRoleDirectoryClient.RoleEntry role(String code, boolean enabled) {
        return new IamRoleDirectoryClient.RoleEntry(code, code, "DATA", enabled);
    }

    @Test
    @DisplayName("域策略：授权角色、全量角色、行/列规则里的角色都要校验")
    void domainChecksEveryRolePosition() {
        when(roleDirectory.roles()).thenReturn(List.of(role("analyst", true), role("manager", true)));
        when(userDirectory.usersByNames(anyCollection())).thenReturn(List.of());

        String json = """
                {"v":1,"grantRoles":["analyst"],"unrestrictedRoles":["manager"],
                 "rowRules":[{"id":1,"role":"manager","field":"region","op":"=","value":"华东"}],
                 "colRules":[{"id":1,"role":"analyst","field":"premium","action":"脱敏"}]}""";

        assertThatCode(() -> validator.validateDomain(json)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("角色不存在 → 拒绝保存，并列出全部缺失的角色")
    void missingRoleRejects() {
        when(roleDirectory.roles()).thenReturn(List.of(role("analyst", true)));

        String json = """
                {"grantRoles":["analyst","分析员"],
                 "rowRules":[{"id":1,"role":"区域经理","field":"region","op":"=","value":"华东"}]}""";

        assertThatThrownBy(() -> validator.validateDomain(json))
                .isInstanceOf(AdminException.class)
                .hasMessageContaining("分析员")
                .hasMessageContaining("区域经理");
    }

    @Test
    @DisplayName("角色已停用 → 放行（前端已标黄，启动校验另报）")
    void disabledRoleDoesNotBlock() {
        when(roleDirectory.roles()).thenReturn(List.of(role("old-role", false)));

        assertThatCode(() -> validator.validateAssistant(
                "{\"enabled\":true,\"grantRoles\":[\"old-role\"]}"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("用户名不存在 → 拒绝保存")
    void missingUserRejects() {
        when(userDirectory.usersByNames(anyCollection())).thenReturn(List.of());

        assertThatThrownBy(() -> validator.validateAssistant(
                "{\"enabled\":true,\"grantUsers\":[\"zhangsan\"]}"))
                .isInstanceOf(AdminException.class)
                .hasMessageContaining("zhangsan")
                .hasMessageContaining("username");
    }

    @Test
    @DisplayName("IAM 不可用 → 跳过校验，不阻断保存")
    void unavailableIamDoesNotBlock() {
        when(roleDirectory.roles()).thenThrow(new IllegalStateException("IAM down"));
        when(userDirectory.usersByNames(anyCollection())).thenThrow(new IllegalStateException("IAM down"));

        assertThatCode(() -> validator.validateDomain(
                "{\"grantRoles\":[\"analyst\"],\"grantUsers\":[\"zhangsan\"],\"isPublic\":true}"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("空/坏 JSON 不在这里报：让 DomainInput / AssistantInput 给准确错误")
    void blankOrBadJsonIsLeftToInputValidation() {
        assertThatCode(() -> validator.validateDomain(null)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validateAssistant("not-json")).doesNotThrowAnyException();
    }
}

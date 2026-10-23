package com.liusongboyy.chatdata.ask.engine;

import com.liusongboyy.chatdata.engine.startup.RoleDirectory;
import com.liusongboyy.chatdata.iam.client.IamRoleDirectoryClient;
import com.liusongboyy.chatdata.iam.client.IamUserDirectoryClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 角色清单这条适配的装配验证。
 *
 * <p>它接的是**三个模块的边界**：引擎声明取角色的口子，应用侧用
 * {@link IamRoleDirectoryClient} 实现它，而角色清单的事实来源在 IAM。
 * 两边各自的单测都绿，不代表接得上——而接不上的表现是"角色引用校验静默不跑"，
 * 比没有校验更糟：报告里写着"校验通过"，实际什么都没查。
 *
 * <p>IAM 客户端在这里被替换掉：本用例要验的是"装配接对了没有"，
 * 而不是 HTTP 细节（后者由 {@code IamRoleDirectoryClientTest} 覆盖）。
 */
@SpringBootTest(properties = {
        "chatdata.auth.secret=role-dir-test-secret",
        "chatdata.engine.datasource.secret=role-dir-test-engine-secret",
        // 目录客户端走同一套客户端凭证；策略来源默认已是 local（ADR-0010），
        // 但密钥仍要能读到，否则目录调用会带着空密码去换 token
        "chatdata.iam.client-secret=role-dir-test-iam-secret",
        // 关掉启动校验：本用例只验这条适配，不关心它与别的违规是否让应用起不来
        "chatdata.engine.startup.enabled=false"
})
class RoleDirectoryWiringTest {

    @MockitoBean
    private IamRoleDirectoryClient client;

    @Autowired
    private RoleDirectory roleDirectory;

    @Autowired
    private IamUserDirectoryClient userDirectory;

    @Test
    @DisplayName("用户目录客户端总是装配：策略编辑器要能选用户")
    void userDirectoryIsAlwaysWired() {
        assertThat(userDirectory)
                .as("数据策略归 ask，但身份仍在 IAM——用户目录必须总是可用")
                .isNotNull();
    }

    @Test
    @DisplayName("能从 IAM 角色目录读到角色，且带启用状态")
    void readsRolesFromIam() {
        when(client.roles()).thenReturn(List.of(
                new IamRoleDirectoryClient.RoleEntry("chat2bi", "超级管理员", "ADMIN", true),
                new IamRoleDirectoryClient.RoleEntry("区域经理", "区域经理", "DATA", true),
                new IamRoleDirectoryClient.RoleEntry("已归档", "已归档", "DATA", false)));

        Optional<Map<String, Boolean>> roles = roleDirectory.roles();

        assertThat(roles)
                .as("拿不到角色清单时校验会静默跳过，所以这条必须有值")
                .isPresent();

        // 停用的角色也要在清单里：报"已停用"与报"不存在"是两种不同的问题
        assertThat(roles.get())
                .containsEntry("chat2bi", true)
                .containsEntry("区域经理", true)
                .containsEntry("已归档", false);
    }

    @Test
    @DisplayName("角色码不做归一化 —— 校验必须与运行时逐字符匹配的判据一致")
    void roleCodesAreNotNormalized() {
        when(client.roles()).thenReturn(List.of(
                new IamRoleDirectoryClient.RoleEntry(" 带空白的角色 ", "带空白", "DATA", true)));

        Map<String, Boolean> roles = roleDirectory.roles().orElseThrow();

        // 若这里被 trim/小写化过，就会出现"启动校验通过、运行时匹配不上"的最坏组合
        assertThat(roles).containsOnlyKeys(" 带空白的角色 ");
    }

    @Test
    @DisplayName("IAM 不可用时返回 empty（跳过校验），不是'一个角色都没有'")
    void unavailableIamYieldsEmpty() {
        when(client.roles()).thenThrow(new IllegalStateException("IAM 不可用"));

        assertThat(roleDirectory.roles())
                .as("当成'没有角色'会把每份配置都误报成'角色不存在'，一屏假告警等于没有告警")
                .isEmpty();
    }
}

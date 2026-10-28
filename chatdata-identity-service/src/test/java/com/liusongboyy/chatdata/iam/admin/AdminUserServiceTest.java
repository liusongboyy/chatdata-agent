package com.liusongboyy.chatdata.iam.admin;

import com.liusongboyy.chatdata.iam.config.IamProperties;
import com.liusongboyy.chatdata.iam.org.OrgMapper;
import com.liusongboyy.chatdata.iam.org.OrgRow;
import com.liusongboyy.chatdata.iam.role.RoleMapper;
import com.liusongboyy.chatdata.iam.role.RoleRow;
import com.liusongboyy.chatdata.iam.user.UserMapper;
import com.liusongboyy.chatdata.iam.user.UserRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户管理的闸门。
 *
 * <p>重点不是"能不能建"，而是**拒绝的时候说了什么**：
 * 一个不存在的角色码如果被静默忽略，管理员会以为授了权——这是本项目最怕的失败形态。
 */
class AdminUserServiceTest {

    private UserMapper users;
    private OrgMapper orgs;
    private RoleMapper roles;
    private PasswordEncoder encoder;
    private com.liusongboyy.chatdata.iam.permission.PermissionChecker perm;
    private AdminUserService service;

    @BeforeEach
    void setUp() {
        users = mock(UserMapper.class);
        orgs = mock(OrgMapper.class);
        roles = mock(RoleMapper.class);
        encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("$2a$encoded");
        perm = mock(com.liusongboyy.chatdata.iam.permission.PermissionChecker.class);
        // 默认给足权限：本用例集验的是"角色码/密码"这些输入闸门，
        // 权限闸门另有 AdminRolePermissionGuardTest 专门覆盖
        when(perm.has(anyString())).thenReturn(true);
        service = new AdminUserService(users, orgs, roles, encoder, new IamProperties(), perm);
    }

    private static AdminInputs.UserCreate input(String username, String password, List<String> roleCodes) {
        return new AdminInputs.UserCreate(username, password, "张三", 10L, roleCodes, "ACTIVE");
    }

    @Test
    @DisplayName("★ 不存在的角色码必须拒绝，而不是静默忽略")
    void rejectsUnknownRole() {
        when(roles.selectByCode("t_default", "不存在的角色")).thenReturn(null);

        assertThatThrownBy(() -> service.create(input("u1", "secret123", List.of("不存在的角色"))))
                .isInstanceOf(IamAdminException.class)
                .hasMessageContaining("角色不存在");

        verify(users, never()).insertUser(any());
    }

    @Test
    @DisplayName("密码过短直接拒绝")
    void rejectsShortPassword() {
        assertThatThrownBy(() -> service.create(input("u1", "123", List.of())))
                .isInstanceOf(IamAdminException.class)
                .hasMessageContaining("至少");

        verify(users, never()).insertUser(any());
    }

    @Test
    @DisplayName("正常创建：写哈希、绑组织、授角色")
    void createsUser() {
        when(roles.selectByCode("t_default", "区域经理"))
                .thenReturn(new RoleRow(1L, "t_default", "区域经理", "区域经理", "DATA", null, true, 0));
        when(orgs.selectById(10L, "t_default")).thenReturn(mock(com.liusongboyy.chatdata.iam.org.OrgRow.class));
        doAnswer(inv -> {
            ((Map<String, Object>) inv.getArgument(0)).put("id", 7L);
            return 1;
        }).when(users).insertUser(any());

        long id = service.create(input("zhangsan", "zhangsan123", List.of("区域经理")));

        assertThat(id).isEqualTo(7L);
        verify(users).insertUserOrg(7L, 10L, true);
        verify(users).deleteUserRoles(7L);
        verify(users).insertUserRole(7L, "t_default", "区域经理");
    }

    @Test
    @DisplayName("重名给出可读提示，而不是 500")
    void duplicateUsernameIsFriendly() {
        when(users.insertUser(any())).thenThrow(new DuplicateKeyException("dup"));

        // 不绑组织：这条只验重名分支，组织校验会先拦住（夹具不 stub 它）
        AdminInputs.UserCreate noOrg =
                new AdminInputs.UserCreate("admin", "secret123", "管理员", null, List.of(), "ACTIVE");

        assertThatThrownBy(() -> service.create(noOrg))
                .isInstanceOf(IamAdminException.class)
                .hasMessageContaining("用户名已存在");
    }

    @Test
    @DisplayName("重置密码写哈希，且不返回明文")
    void resetPasswordHashes() {
        when(users.selectById(7L)).thenReturn(new UserRow(7L, "u1", "old", "张三", "ACTIVE", 0));

        service.resetPassword(7L, new AdminInputs.PasswordReset("newpass123"));

        verify(encoder).encode("newpass123");
        verify(users).updatePassword(7L, "$2a$encoded");
    }

    @Test
    @DisplayName("给不存在的用户重置密码 → 明确报错")
    void resetPasswordUnknownUser() {
        when(users.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.resetPassword(99L, new AdminInputs.PasswordReset("newpass123")))
                .isInstanceOf(IamAdminException.class)
                .hasMessageContaining("用户不存在");

        verify(users, never()).updatePassword(anyLong(), anyString());
    }

    @Test
    @DisplayName("角色覆盖式保存：先清空再插入")
    void assignRolesReplaces() {
        when(users.selectById(7L)).thenReturn(new UserRow(7L, "u1", "h", "张三", "ACTIVE", 0));
        when(roles.selectByCode(eq("t_default"), anyString()))
                .thenReturn(new RoleRow(1L, "t_default", "分析师", "分析师", "DATA", null, true, 0));

        service.assignRoles(7L, new AdminInputs.RoleCodes(List.of("分析师", "区域经理")));

        verify(users).deleteUserRoles(7L);
        verify(users).insertUserRole(eq(7L), eq("t_default"), eq("分析师"));
        verify(users).insertUserRole(eq(7L), eq("t_default"), eq("区域经理"));
        verify(users, never()).insertUserOrg(anyLong(), anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("分页：size 超上限夹到 200，页码越界回到最后一页")
    void listClampsSizeAndFallsBackToLastPage() {
        when(users.countPage("t_default", null, null, null)).thenReturn(250L);
        when(users.selectPage("t_default", null, null, null, 200, 200L)).thenReturn(List.of());

        AdminUserService.UserPage page = service.list(9, 5000, null, null, null);

        assertThat(page.total()).isEqualTo(250L);
        assertThat(page.size()).isEqualTo(200);
        assertThat(page.page()).isEqualTo(2);
        verify(users).selectPage("t_default", null, null, null, 200, 200L);
    }

    @Test
    @DisplayName("零结果时不去查列表页")
    void listEmptySkipsPageQuery() {
        when(users.countPage(anyString(), any(), any(), any())).thenReturn(0L);

        AdminUserService.UserPage page = service.list(3, 20, "查无此人", null, null);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
        verify(users, never()).selectPage(anyString(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("★ 关键字里的 % 与 _ 是字面量，不是通配符")
    void keywordWildcardsAreEscaped() {
        when(users.countPage(anyString(), any(), any(), any())).thenReturn(0L);

        service.list(1, 20, "100%_off", null, null);

        verify(users).countPage("t_default", null, null, "%100!%!_off%");
    }

    @Test
    @DisplayName("按组织筛选走物化路径前缀，含子树")
    void orgFilterUsesPathPrefix() {
        when(orgs.selectById(10L, "t_default"))
                .thenReturn(new OrgRow(10L, "t_default", null, "hq", "总部", "/1/10/", 0, true, null));
        when(users.countPage(anyString(), any(), any(), any())).thenReturn(0L);

        service.list(1, 20, null, null, 10L);

        verify(users).countPage("t_default", null, "/1/10/", null);
    }

    @Test
    @DisplayName("组织不存在时筛选报错，而不是静默显示全部")
    void unknownOrgFilterRejected() {
        when(orgs.selectById(404L, "t_default")).thenReturn(null);

        assertThatThrownBy(() -> service.list(1, 20, null, null, 404L))
                .isInstanceOf(IamAdminException.class)
                .hasMessageContaining("组织不存在");
    }

    @Test
    @DisplayName("非法状态筛选直接报错")
    void invalidStatusFilterRejected() {
        assertThatThrownBy(() -> service.list(1, 20, null, "DELETED", null))
                .isInstanceOf(IamAdminException.class)
                .hasMessageContaining("状态只能是");
    }
}

package com.liusongboyy.chatdata.iam.permission;

import com.liusongboyy.chatdata.iam.config.IamProperties;
import com.liusongboyy.chatdata.iam.user.UserMapper;
import com.liusongboyy.chatdata.iam.user.UserRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 功能权限的解析。
 *
 * <p>这是整套机制里最关键的一处：**界面展示与后端强制都从这里取数**。
 * 两处算法不一致会得到"界面有按钮、点了全 403"，连内置超管都一样。
 */
class EffectivePermissionResolverTest {

    private static final UserRow USER = new UserRow(7L, "zhangsan", "h", "张三", "ACTIVE", 0);

    private UserMapper users;
    private RolePermissionMapper rolePermissions;
    private IamProperties properties;
    private EffectivePermissionResolver resolver;

    @BeforeEach
    void setUp() {
        users = mock(UserMapper.class);
        rolePermissions = mock(RolePermissionMapper.class);
        properties = new IamProperties();
        properties.getFunctionalPermissions().setCacheSeconds(5);
        resolver = new EffectivePermissionResolver(users, rolePermissions, properties);
    }

    @Test
    @DisplayName("★ 多角色取并集")
    void unionsAcrossRoles() {
        when(users.selectRoleCodes(7L)).thenReturn(List.of("区域经理", "分析师"));
        when(rolePermissions.selectCodesByUser(7L))
                .thenReturn(List.of("ask.ui.charts", "ask.admin.feedback"));

        assertThat(resolver.resolve(USER))
                .containsExactlyInAnyOrder("ask.ui.charts", "ask.admin.feedback");
    }

    @Test
    @DisplayName("★ 内置超管不看库，直接全量——这是防锁死的那道保险")
    void builtinSuperSkipsDatabase() {
        when(users.selectRoleCodes(7L)).thenReturn(List.of("super_admin"));

        assertThat(resolver.resolve(USER)).isEqualTo(PermissionCatalog.allCodes());
        // 连权限表都不该查：超管的权限来自代码，不是数据
        verify(rolePermissions, never()).selectCodesByUser(anyLong());
    }

    @Test
    @DisplayName("★ 重复的权限码不会让 Set 抛异常（上游少写一个 DISTINCT 就会）")
    void duplicateCodesDoNotThrow() {
        when(users.selectRoleCodes(7L)).thenReturn(List.of("区域经理", "分析师"));
        when(rolePermissions.selectCodesByUser(7L))
                .thenReturn(List.of("ask.ui.charts", "ask.ui.charts"));

        assertThat(resolver.resolve(USER)).containsExactly("ask.ui.charts");
    }

    @Test
    @DisplayName("★ 目录里已删除的码不生效（历史数据可能残留）")
    void codesMissingFromCatalogAreIgnored() {
        when(users.selectRoleCodes(7L)).thenReturn(List.of("分析师"));
        when(rolePermissions.selectCodesByUser(7L))
                .thenReturn(List.of("ask.ui.charts", "某个早就删掉的码"));

        assertThat(resolver.resolve(USER)).containsExactly("ask.ui.charts");
    }

    @Test
    @DisplayName("没有角色 → 空集（不是全量）")
    void noRolesMeansNothing() {
        when(users.selectRoleCodes(7L)).thenReturn(List.of());

        assertThat(resolver.resolve(USER)).isEmpty();
    }

    @Test
    @DisplayName("5 秒内复用缓存，不重复查库")
    void cachesWithinTtl() {
        when(users.selectRoleCodes(7L)).thenReturn(List.of("分析师"));
        when(rolePermissions.selectCodesByUser(7L)).thenReturn(List.of("ask.ui.charts"));

        resolver.resolve(USER);
        resolver.resolve(USER);
        resolver.resolve(USER);

        verify(rolePermissions, times(1)).selectCodesByUser(7L);
    }

    @Test
    @DisplayName("TTL 为 0 时不缓存：每次现查")
    void zeroTtlDisablesCache() {
        properties.getFunctionalPermissions().setCacheSeconds(0);
        resolver = new EffectivePermissionResolver(users, rolePermissions, properties);
        when(users.selectRoleCodes(7L)).thenReturn(List.of("分析师"));
        when(rolePermissions.selectCodesByUser(7L)).thenReturn(List.of("ask.ui.charts"));

        resolver.resolve(USER);
        resolver.resolve(USER);

        verify(rolePermissions, times(2)).selectCodesByUser(7L);
    }

    @Test
    @DisplayName("按用户名解析：查不到用户返回空集，不抛")
    void unknownUsernameIsEmpty() {
        when(users.selectByUsername("t_default", "nobody")).thenReturn(null);

        assertThat(resolver.resolve("nobody")).isEmpty();
        assertThat(resolver.resolve((String) null)).isEmpty();
        assertThat(resolver.resolve("  ")).isEmpty();
    }
}

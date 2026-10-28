package com.liusongboyy.chatdata.iam.admin;

import com.liusongboyy.chatdata.iam.config.IamProperties;
import com.liusongboyy.chatdata.iam.org.OrgMapper;
import com.liusongboyy.chatdata.iam.org.OrgRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 组织树的关键不变量：**path 必须包含自己的 id**。
 *
 * <p>插入前算不出 path（那时还没有 id），所以流程必须是"先插、拿 id、再回填"。
 * 这条顺序错了不会报错，只会让所有子树的路径前缀对不上——
 * 而按组织子树过滤权限正是靠这个前缀。
 */
class AdminOrgServiceTest {

    private OrgMapper orgs;
    private AdminOrgService service;

    @BeforeEach
    void setUp() {
        orgs = mock(OrgMapper.class);
        service = new AdminOrgService(orgs, new IamProperties());
    }

    @Test
    @DisplayName("★ 子组织的 path = 父 path + 自己的 id + /")
    void childPathIncludesOwnId() {
        when(orgs.selectById(1L, "t_default")).thenReturn(new OrgRow(1L, "t_default", null, "HQ", "总部", "/1/", 0, true, null));
        doAnswer(inv -> {
            ((Map<String, Object>) inv.getArgument(0)).put("id", 12L);
            return 1;
        }).when(orgs).insertOrg(any());

        long id = service.create(new AdminInputs.OrgCreate("华南", "华南大区", 1L, 30, true));

        assertThat(id).isEqualTo(12L);
        verify(orgs).updatePath(12L, "/1/12/");
    }

    @Test
    @DisplayName("根组织：path = /id/")
    void rootPath() {
        doAnswer(inv -> {
            ((Map<String, Object>) inv.getArgument(0)).put("id", 5L);
            return 1;
        }).when(orgs).insertOrg(any());

        service.create(new AdminInputs.OrgCreate("HQ2", "第二总部", null, 0, true));

        verify(orgs).updatePath(5L, "/5/");
    }

    @Test
    @DisplayName("父组织不存在 → 拒绝，不留下半条记录")
    void rejectsMissingParent() {
        when(orgs.selectById(99L, "t_default")).thenReturn(null);

        assertThatThrownBy(() -> service.create(new AdminInputs.OrgCreate("X", "X", 99L, 0, true)))
                .isInstanceOf(IamAdminException.class)
                .hasMessageContaining("父组织不存在");

        verify(orgs, never()).insertOrg(any());
    }

    @Test
    @DisplayName("编码为空 → 拒绝")
    void rejectsBlankCode() {
        assertThatThrownBy(() -> service.create(new AdminInputs.OrgCreate("  ", "X", null, 0, true)))
                .isInstanceOf(IamAdminException.class)
                .hasMessageContaining("编码不能为空");
    }

    @Test
    @DisplayName("更新只改名称/排序/启用，不动 code 与 path")
    void updateKeepsIdentity() {
        when(orgs.selectById(10L, "t_default"))
                .thenReturn(new OrgRow(10L, "t_default", 1L, "华东", "华东大区", "/1/10/", 10, true, null));

        service.update(10L, new AdminInputs.OrgUpdate("华东区域", 20, false));

        verify(orgs).updateOrg(eq(10L), eq("华东区域"), eq(20), eq(false));
        verify(orgs, never()).updatePath(any(Long.class), any());
    }
}

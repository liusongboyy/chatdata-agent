package com.liusongboyy.chatdata.iam.serviceauth;

import com.liusongboyy.chatdata.iam.config.IamProperties;
import com.liusongboyy.chatdata.iam.policy.PolicyRoleDirectoryController;
import com.liusongboyy.chatdata.iam.policy.PolicyUserDirectoryController;
import com.liusongboyy.chatdata.iam.role.RoleMapper;
import com.liusongboyy.chatdata.iam.role.RoleRow;
import com.liusongboyy.chatdata.iam.user.UserMapper;
import com.liusongboyy.chatdata.iam.user.UserRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 服务间调用前缀的闸门。
 *
 * <p>这些前缀在 {@code SecurityConfig} 里是 {@code permitAll}——凭据是"客户端"
 * 而不是"用户"，表达不成一条 {@code hasRole} 规则。于是**拦截器是唯一的闸门**，
 * 而"唯一的闸门"必须有测试，否则它哪天被摘掉也没人知道。
 *
 * <p>这里用 {@code standaloneSetup} 并**显式注册**拦截器，与 {@code WebMvcConfig}
 * 的做法一致——注册方式本身也是被测的一部分。
 */
class ClientCredentialInterceptorTest {

    /** 与 WebMvcConfig.SERVICE_PREFIXES 一致。 */
    private static final String[] PREFIXES = {"/iam/policy/**", "/iam/identity/**"};

    private static MockMvc mvc(boolean allowed) {
        return mvc(allowed, mock(RoleMapper.class), mock(UserMapper.class));
    }

    private static MockMvc mvc(boolean allowed, RoleMapper roles, UserMapper users) {
        IamServiceClientAuth auth = mock(IamServiceClientAuth.class);
        when(auth.allowed(any(), anyString())).thenReturn(allowed);

        return MockMvcBuilders
                .standaloneSetup(
                        // 数据策略已回归业务模块（ADR-0010），本前缀下只剩只读目录：
                        // 角色目录与用户目录。外加身份解析
                        new PolicyRoleDirectoryController(roles, new IamProperties()),
                        new PolicyUserDirectoryController(users, new IamProperties()),
                        new IdentityResolveController(users, new IamProperties()))
                // 用带路径的注册方式，与 WebMvcConfig 一致：
                // 不带路径的 addInterceptors 会挂到 /**，那样"只管这个前缀"就没被验证到
                .addMappedInterceptors(PREFIXES, new ClientCredentialInterceptor(auth))
                .build();
    }

    @Test
    @DisplayName("★ 无凭据 → 401（不是 200，也不是 500）")
    void withoutCredentialIsUnauthorized() throws Exception {
        // 闸门由注册路径决定，而不是由 handler 自己写
        mvc(false).perform(get("/iam/policy/roles"))
                .andExpect(status().isUnauthorized());

        mvc(false).perform(get("/iam/policy/users"))
                .andExpect(status().isUnauthorized());

        // ★ 身份解析尤其不能裸奔：开放出去就是一个可枚举的探测接口
        mvc(false).perform(get("/iam/identity/resolve")
                        .param("provider", "feishu").param("externalId", "ou_1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("角色目录：原样返回角色码，含已停用的角色")
    void roleDirectoryReturnsCodesVerbatim() throws Exception {
        RoleMapper roles = mock(RoleMapper.class);
        when(roles.selectAll("t_default")).thenReturn(List.of(
                new RoleRow(1L, "t_default", " 分析师 ", "分析师", "DATA", null, true, 0),
                new RoleRow(2L, "t_default", "离职角色", "离职角色", "DATA", null, false, 0)));

        mvc(true, roles, mock(UserMapper.class)).perform(get("/iam/policy/roles"))
                .andExpect(status().isOk())
                // 空格保留：运行时是逐字符匹配，这里 trim 会造出"校验通过、运行时匹配不上"
                .andExpect(jsonPath("$.items[0].code").value(" 分析师 "))
                .andExpect(jsonPath("$.items[0].enabled").value(true))
                // 停用角色也返回：「不存在」与「存在但停用」要能分开报
                .andExpect(jsonPath("$.items[1].code").value("离职角色"))
                .andExpect(jsonPath("$.items[1].enabled").value(false));
    }

    @Test
    @DisplayName("凭据有效 → 进入 handler（拦截器不误伤）")
    void withCredentialReachesHandler() throws Exception {
        mvc(true).perform(get("/iam/policy/users"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("★ 已绑定 → 200 + username")
    void resolveBoundIdentity() throws Exception {
        UserMapper users = mock(UserMapper.class);
        when(users.selectByExternalIdentity(anyString(), anyString(), anyString()))
                .thenReturn(new UserRow(7L, "zhangsan", null, "张三", "ACTIVE", 0));

        mvc(true, mock(RoleMapper.class), users)
                .perform(get("/iam/identity/resolve")
                        .param("provider", "feishu").param("externalId", "ou_abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("zhangsan"))
                .andExpect(jsonPath("$.userId").value(7));
    }

    @Test
    @DisplayName("★ 未绑定 → 404 空体（不是 200 + found:false）")
    void resolveUnboundIsNotFound() throws Exception {
        UserMapper users = mock(UserMapper.class);
        when(users.selectByExternalIdentity(anyString(), anyString(), anyString()))
                .thenReturn(null);

        mvc(true, mock(RoleMapper.class), users)
                .perform(get("/iam/identity/resolve")
                        .param("provider", "feishu").param("externalId", "ou_unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("★ 账号已停用 → 也按 404（对渠道来说等同于未绑定）")
    void resolveDisabledIsNotFound() throws Exception {
        UserMapper users = mock(UserMapper.class);
        when(users.selectByExternalIdentity(anyString(), anyString(), anyString()))
                .thenReturn(new UserRow(8L, "lisi", null, "李四", "DISABLED", 0));

        mvc(true, mock(RoleMapper.class), users)
                .perform(get("/iam/identity/resolve")
                        .param("provider", "feishu").param("externalId", "ou_disabled"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("空参数 → 400，不去查库")
    void resolveBlankParamsIsBadRequest() throws Exception {
        UserMapper users = mock(UserMapper.class);

        mvc(true, mock(RoleMapper.class), users)
                .perform(get("/iam/identity/resolve")
                        .param("provider", "feishu").param("externalId", "  "))
                .andExpect(status().isBadRequest());

        org.mockito.Mockito.verify(users, org.mockito.Mockito.never())
                .selectByExternalIdentity(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("不在这些前缀下的路径不受影响")
    void otherPathsUnaffected() throws Exception {
        mvc(false).perform(get("/actuator/health"))
                .andExpect(status().isNotFound());   // 没注册这个 handler，但**不是** 401
    }
}

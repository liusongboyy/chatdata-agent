package com.liusongboyy.chatdata.iam.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link IamRoleDirectoryClient} 的行为。
 *
 * <p>挡两类错误：一是**把响应读歪**（漏掉停用角色、把 code 归一化 → 校验通过但运行时不匹配），
 * 二是**把失败吞掉**（返回空清单 → 调用方以为"一个角色都没有"，把每份配置都误报成坏配置）。
 */
class IamRoleDirectoryClientTest {

    private static final String BASE = "http://iam.test";
    private static final String TOKEN = BASE + "/oauth2/token";
    private static final String ROLES = BASE + "/iam/policy/roles";

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private void stubToken() {
        server.expect(requestTo(TOKEN)).andRespond(withSuccess(
                "{\"access_token\":\"tok\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("解析 items：保留停用角色，code 原样返回")
    void parsesItemsWithoutNormalizing() {
        IamRoleDirectoryClient client = new IamRoleDirectoryClient(builder, "ask-resource-server", "secret");
        stubToken();
        server.expect(requestTo(ROLES))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess("""
                        {"items":[
                          {"code":"chat2bi","name":"超级管理员","category":"ADMIN","enabled":true},
                          {"code":" 区域经理 ","name":"区域经理","category":"DATA","enabled":false}
                        ]}""", MediaType.APPLICATION_JSON));

        List<IamRoleDirectoryClient.RoleEntry> roles = client.roles();

        assertThat(roles).hasSize(2);
        assertThat(roles.get(0).code()).isEqualTo("chat2bi");
        assertThat(roles.get(0).category()).isEqualTo("ADMIN");
        assertThat(roles.get(1).code())
                .as("trim 会让启动校验与运行时的逐字符匹配得出不同结论")
                .isEqualTo(" 区域经理 ");
        assertThat(roles.get(1).enabled())
                .as("停用角色必须带回来：'已停用'与'不存在'要分开报")
                .isFalse();
        server.verify();
    }

    @Test
    @DisplayName("IAM 返回 401 → 抛出并带上状态码，不返回空清单")
    void unauthorizedThrows() {
        IamRoleDirectoryClient client = new IamRoleDirectoryClient(builder, "ask-resource-server", "wrong");
        stubToken();
        server.expect(requestTo(ROLES)).andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(client::roles)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401");
    }

    @Test
    @DisplayName("缺密钥 → 说清是本地没配，而不是发个空密码去换一个 401")
    void missingSecretFailsLocally() {
        IamRoleDirectoryClient client = new IamRoleDirectoryClient(builder, "ask-resource-server", "");

        assertThatThrownBy(client::roles)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client-secret");
        server.verify();
    }
}

package com.liusongboyy.chatdata.iam.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
 * {@link IamUserDirectoryClient} 的行为。
 *
 * <p>重点：username 必须原样返回（权限配置按它匹配），停用用户不能丢
 * （已保存的策略可能引用他们），失败的调用必须抛出而不是变成"没有用户"。
 */
class IamUserDirectoryClientTest {

    private static final String BASE = "http://iam.test";
    private static final String TOKEN = BASE + "/oauth2/token";
    private static final String USERS = BASE + "/iam/policy/users";

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
    @DisplayName("解析 items：保留停用用户与原始 username")
    void parsesItems() {
        IamUserDirectoryClient client = new IamUserDirectoryClient(builder, "ask-resource-server", "secret");
        stubToken();
        server.expect(requestTo(USERS + "?keyword=&limit=20"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess("""
                        {"items":[
                          {"username":"zhangsan","displayName":"张三","status":"ACTIVE"},
                          {"username":"lisi","displayName":null,"status":"DISABLED"}
                        ]}""", MediaType.APPLICATION_JSON));

        List<IamUserDirectoryClient.UserEntry> users = client.users(null, 20);

        assertThat(users).hasSize(2);
        assertThat(users.get(0).username()).isEqualTo("zhangsan");
        assertThat(users.get(0).displayName()).isEqualTo("张三");
        assertThat(users.get(0).status()).isEqualTo("ACTIVE");
        assertThat(users.get(1).username()).isEqualTo("lisi");
        assertThat(users.get(1).displayName()).isNull();
        assertThat(users.get(1).status())
                .as("停用用户必须带回来，界面要能标出引用不会生效")
                .isEqualTo("DISABLED");
        server.verify();
    }

    @Test
    @DisplayName("缺 username 的脏数据跳过，不让它变成空字符串配置")
    void skipsItemsWithoutUsername() {
        IamUserDirectoryClient client = new IamUserDirectoryClient(builder, "ask-resource-server", "secret");
        stubToken();
        server.expect(requestTo(USERS + "?keyword=&limit=20")).andRespond(withSuccess("""
                {"items":[{"displayName":"无账号"},{"username":"ok"}]}""", MediaType.APPLICATION_JSON));

        List<IamUserDirectoryClient.UserEntry> users = client.users("", 20);

        assertThat(users).extracting(IamUserDirectoryClient.UserEntry::username)
                .containsExactly("ok");
    }

    @Test
    @DisplayName("批量按 username 取：去重后一次请求，返回实际存在的那些")
    void batchByUsernames() {
        IamUserDirectoryClient client = new IamUserDirectoryClient(builder, "ask-resource-server", "secret");
        stubToken();
        server.expect(requestTo(USERS + "?usernames=zhangsan,lisi"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess("""
                        {"items":[{"username":"zhangsan","displayName":"张三","status":"ACTIVE"}]}""",
                        MediaType.APPLICATION_JSON));

        List<IamUserDirectoryClient.UserEntry> users =
                client.usersByNames(java.util.List.of("zhangsan", "lisi", "zhangsan"));

        assertThat(users).extracting(IamUserDirectoryClient.UserEntry::username)
                .containsExactly("zhangsan");
        server.verify();
    }

    @Test
    @DisplayName("IAM 返回 401 → 抛出并带上状态码，不返回空清单")
    void unauthorizedThrows() {
        IamUserDirectoryClient client = new IamUserDirectoryClient(builder, "ask-resource-server", "wrong");
        stubToken();
        server.expect(requestTo(USERS + "?keyword=%E5%BC%A0%E4%B8%89&limit=10"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.users("张三", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401");
    }
}

package com.liusongboyy.chatdata.channel.identity;

import com.liusongboyy.chatdata.channel.config.ChannelProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 渠道身份解析。
 *
 * <h2>它挡的是什么</h2>
 *
 * <p>这个类原来直接查 IAM 的库（{@code SELECT username FROM chatdata_iam.iam_user ...}）。
 * IAM 要独立成平台，那个跨库直连必须先断掉——否则 IAM 拆库或改表时，
 * 失败表现是「飞书绑定查不到」，与真实原因相距甚远。
 *
 * <p>所以这里的断言集中在两件事上：
 *
 * <ol>
 *   <li><b>走的是 HTTP 接口</b>，参数对得上</li>
 *   <li><b>「未绑定」与「IAM 不可用」分得开</b>——混成一个会让 IAM 抖动一次
 *       就骗一批已绑定的用户去重新绑定</li>
 * </ol>
 */
class ChannelIdentityServiceTest {

    private static ChannelProperties props(Duration ttl) {
        ChannelProperties p = new ChannelProperties();
        p.getIam().setBaseUrl("http://iam.test");
        p.getIam().setIdentityCacheTtl(ttl);
        // 密钥必须有值：缺失时 serviceToken() 会在本地就失败（故意的，
        // 免得发个空密码过去等 401，而 401 看起来像"IAM 拒绝了我们"）
        p.getIam().setClientSecret("test-secret");
        return p;
    }

    /** 换服务令牌的那一次往返。每个测试只要涉及查询就得先给它一段。 */
    private static void expectToken(MockRestServiceServer server) {
        server.expect(requestTo("http://iam.test/oauth2/token"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                // ★ 渠道要的是 iam.identity，不是 iam.principal。
                // 后者能读全量用户目录，而渠道只需要问「这一个 open_id 是谁」
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "scope=iam.identity")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("iam.principal"))))
                .andRespond(withSuccess(
                        "{\"access_token\":\"svc-token\",\"expires_in\":300}",
                        MediaType.APPLICATION_JSON));
    }

    private record Fixture(ChannelIdentityService service, MockRestServiceServer server) {
    }

    private static Fixture fixture() {
        return fixture(Duration.ofSeconds(60));
    }

    private static Fixture fixture(Duration ttl) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new ChannelIdentityService(props(ttl), builder), server);
    }

    @Test
    @DisplayName("★ 走 IAM 的接口，而不是查库")
    void callsIamEndpoint() {
        Fixture f = fixture();
        expectToken(f.server());
        f.server().expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://iam.test/iam/identity/resolve")))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(queryParam("provider", "feishu"))
                .andExpect(queryParam("externalId", "ou_abc"))
                .andRespond(withSuccess("{\"username\":\"zhangsan\",\"userId\":7}",
                        MediaType.APPLICATION_JSON));

        assertThat(f.service().resolveUsername("feishu", "ou_abc"))
                .contains("zhangsan");
        f.server().verify();
    }

    @Test
    @DisplayName("★ 404 → 未绑定，是 empty 而不是异常")
    void notFoundMeansUnbound() {
        Fixture f = fixture();
        expectToken(f.server());
        f.server().expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://iam.test/iam/identity/resolve")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(f.service().resolveUsername("feishu", "ou_unknown")).isEmpty();
    }

    @Test
    @DisplayName("★ IAM 5xx → 抛 IdentityUnavailable，不是「未绑定」")
    void serverErrorIsNotUnbound() {
        Fixture f = fixture();
        expectToken(f.server());
        f.server().expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://iam.test/iam/identity/resolve")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> f.service().resolveUsername("feishu", "ou_abc"))
                .as("当成未绑定会让已绑定的用户被引导去重新绑定，而本来就没坏")
                .isInstanceOf(ChannelIdentityService.IdentityUnavailableException.class);
    }

    @Test
    @DisplayName("★ 已绑定的结果会被缓存，第二次不发请求")
    void cachesPositiveResult() {
        Fixture f = fixture();
        expectToken(f.server());
        f.server().expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://iam.test/iam/identity/resolve")))
                .andRespond(withSuccess("{\"username\":\"zhangsan\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(f.service().resolveUsername("feishu", "ou_abc")).contains("zhangsan");
        // 没有第二段 expect：再发一次请求会让 verify 失败
        assertThat(f.service().resolveUsername("feishu", "ou_abc")).contains("zhangsan");
        f.server().verify();
    }

    @Test
    @DisplayName("★ 未绑定不缓存——管理员刚绑好，用户重发一条就该成功")
    void doesNotCacheUnbound() {
        Fixture f = fixture();
        expectToken(f.server());
        f.server().expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://iam.test/iam/identity/resolve")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        // 令牌有缓存，第二次查询不会再换一次——这里**不**给第二段 token 桩，
        // 发了请求就会 verify 失败
        f.server().expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://iam.test/iam/identity/resolve")))
                .andRespond(withSuccess("{\"username\":\"zhangsan\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(f.service().resolveUsername("feishu", "ou_abc")).isEmpty();
        assertThat(f.service().resolveUsername("feishu", "ou_abc")).contains("zhangsan");
        f.server().verify();
    }

    @Test
    @DisplayName("缓存过期后重新查")
    void cacheExpires() throws Exception {
        Fixture f = fixture(Duration.ZERO);
        expectToken(f.server());
        for (int i = 0; i < 2; i++) {
            f.server().expect(requestTo(org.hamcrest.Matchers.startsWith(
                            "http://iam.test/iam/identity/resolve")))
                    .andRespond(withSuccess("{\"username\":\"zhangsan\"}",
                            MediaType.APPLICATION_JSON));
        }

        f.service().resolveUsername("feishu", "ou_abc");
        f.service().resolveUsername("feishu", "ou_abc");
        f.server().verify();
    }

    @Test
    @DisplayName("evict 后重新查")
    void evictForcesRefetch() {
        Fixture f = fixture();
        expectToken(f.server());
        for (int i = 0; i < 2; i++) {
            f.server().expect(requestTo(org.hamcrest.Matchers.startsWith(
                            "http://iam.test/iam/identity/resolve")))
                    .andRespond(withSuccess("{\"username\":\"zhangsan\"}",
                            MediaType.APPLICATION_JSON));
        }

        f.service().resolveUsername("feishu", "ou_abc");
        f.service().evict("feishu", "ou_abc");
        f.service().resolveUsername("feishu", "ou_abc");
        f.server().verify();
    }

    @Test
    @DisplayName("空 externalId 直接返回空，不发请求")
    void blankExternalIdShortCircuits() {
        Fixture f = fixture();
        assertThat(f.service().resolveUsername("feishu", "")).isEmpty();
        assertThat(f.service().resolveUsername("feishu", null)).isEmpty();
        f.server().verify();   // 一段 expect 都没有，发了请求就会失败
    }

    @Test
    @DisplayName("不同渠道的同一个 id 不互相污染")
    void cacheKeyIncludesChannel() {
        Fixture f = fixture();
        expectToken(f.server());
        f.server().expect(requestTo(org.hamcrest.Matchers.containsString("provider=feishu")))
                .andRespond(withSuccess("{\"username\":\"zhangsan\"}",
                        MediaType.APPLICATION_JSON));
        f.server().expect(requestTo(org.hamcrest.Matchers.containsString("provider=dingtalk")))
                .andRespond(withSuccess("{\"username\":\"lisi\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(f.service().resolveUsername("feishu", "u1")).contains("zhangsan");
        assertThat(f.service().resolveUsername("dingtalk", "u1"))
                .as("同一个 id 在两个渠道是两个人，缓存键不能只用 id")
                .contains("lisi");
        f.server().verify();
    }
}

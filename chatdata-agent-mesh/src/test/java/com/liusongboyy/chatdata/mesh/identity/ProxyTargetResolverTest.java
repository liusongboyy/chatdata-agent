package com.liusongboyy.chatdata.mesh.identity;

import com.liusongboyy.chatdata.mesh.config.MeshProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「代表谁」的解析。
 *
 * <p>本类做的都是**早拒绝**：格式不对、客户端不在白名单的请求，
 * 在发 HTTP 之前就失败。真正的授权判定在 IAM——这里拒绝得快，
 * 但不承担"用户有没有授权"的判断。
 */
class ProxyTargetResolverTest {

    private static ProxyTargetResolver withAllowed(String... clients) {
        MeshProperties props = new MeshProperties();
        props.getIdentity().setAllowedClients(List.of(clients));
        return new ProxyTargetResolver(props);
    }

    @Test
    @DisplayName("★ 正常解析：user:zhangsan → zhangsan")
    void resolvesPrefixedUser() {
        assertThat(withAllowed("report-bot").resolve("user:zhangsan", "report-bot"))
                .map(ProxyTargetResolver.ProxyTarget::username).contains("zhangsan");
    }

    @Test
    @DisplayName("★ 缺 userId → 拒绝（本期不支持 agent 以自己的身份查数）")
    void rejectsMissingUserId() {
        ProxyTargetResolver r = withAllowed("report-bot");

        assertThat(r.resolve(null, "report-bot")).isEmpty();
        assertThat(r.resolve("", "report-bot")).isEmpty();
        assertThat(r.resolve("   ", "report-bot")).isEmpty();
    }

    @ParameterizedTest
    @DisplayName("★ 前缀不对 → 拒绝（少一种形态就少一条要保护的路径）")
    @ValueSource(strings = {
            "zhangsan",          // 裸用户名
            "agent:report-bot",  // agent 自己——本期不支持
            "User:zhangsan",     // 大小写不对
            "user:",             // 前缀后为空
            "user:   ",
    })
    void rejectsWrongPrefix(String userId) {
        assertThat(withAllowed("report-bot").resolve(userId, "report-bot")).isEmpty();
    }

    @Test
    @DisplayName("★ 客户端不在白名单 → 拒绝")
    void rejectsClientNotInWhitelist() {
        ProxyTargetResolver r = withAllowed("report-bot", "weekly-digest");

        assertThat(r.resolve("user:zhangsan", "unknown-agent")).isEmpty();
        assertThat(r.resolve("user:zhangsan", null)).isEmpty();
    }

    @Test
    @DisplayName("白名单留空 → 不校验（仅限本地开发）")
    void emptyWhitelistSkipsCheck() {
        ProxyTargetResolver r = new ProxyTargetResolver(new MeshProperties());

        assertThat(r.resolve("user:zhangsan", "anything")).map(ProxyTargetResolver.ProxyTarget::username).contains("zhangsan");
        // 但 userId 的格式仍然要校验——白名单留空不等于什么都放行
        assertThat(r.resolve("agent:xxx", "anything")).isEmpty();
    }

    @Test
    @DisplayName("前缀后带空格会被 trim")
    void trimsUsername() {
        assertThat(withAllowed("report-bot").resolve("user:  zhangsan  ", "report-bot"))
                .map(ProxyTargetResolver.ProxyTarget::username).contains("zhangsan");
    }

    /**
     * ★ 这个方法<b>不</b>判断用户是否授权了该 agent。
     *
     * <p>那是 IAM 的事（查 iam_agent_grant）。这里放行不代表能查到数据——
     * mesh 会拿它去换令牌，IAM 拒绝时整条请求才失败。
     *
     * <p>写这个用例是为了**防止将来有人在这里加授权判断**——那会让判据
     * 出现两处，而两处不一致时的表现是"这里放行、那里拒绝"，很难查。
     */
    @Test
    @DisplayName("★ 即使客户端在白名单、格式对，也不代表用户授权过（那是 IAM 的事）")
    void doesNotJudgeAuthorization() {
        // 这个"用户"从没授权过任何 agent，但解析层不拦
        Optional<ProxyTargetResolver.ProxyTarget> resolved =
                withAllowed("report-bot").resolve("user:never-granted", "report-bot");

        assertThat(resolved).map(ProxyTargetResolver.ProxyTarget::username).contains("never-granted");
    }
}

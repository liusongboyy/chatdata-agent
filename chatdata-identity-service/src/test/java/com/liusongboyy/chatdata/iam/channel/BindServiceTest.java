package com.liusongboyy.chatdata.iam.channel;

import com.liusongboyy.chatdata.iam.security.IamUserDetails;
import com.liusongboyy.chatdata.iam.security.IamUserDetailsService;
import com.liusongboyy.chatdata.iam.user.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自助绑定。
 *
 * <p>本类守的是两条线：
 * <ol>
 *   <li><b>绑定目标只来自会话</b>——票据描述的是渠道账号，不是"要绑给谁"</li>
 *   <li><b>票据一次性</b>——用过即失效</li>
 * </ol>
 *
 * <p>任一条破了，一条转发到群里的链接就会变成接管他人账号的入口。
 */
class BindServiceTest {

    private BindTicketStore tickets;
    private UserMapper users;
    private IamUserDetailsService userDetails;
    private BindService service;

    private static final Authentication ME =
            UsernamePasswordAuthenticationToken.authenticated("zhangsan", null, List.of());

    @BeforeEach
    void setUp() {
        tickets = mock(BindTicketStore.class);
        users = mock(UserMapper.class);
        userDetails = mock(IamUserDetailsService.class);
        service = new BindService(tickets, users, userDetails);

        when(userDetails.loadPrincipal(anyString())).thenReturn(new IamUserDetails(
                7L, "t_default", "zhangsan", "张三", 110L, "SUB-EAST",
                "/1/100/110/", List.of("/1/100/110/"),
                List.of("/HQ/HQ-CVS/SUB-EAST/"), "/HQ/HQ-CVS/SUB-EAST/",
                Set.of("chat2bi"), Set.of(), 0));
    }

    @Test
    @DisplayName("★ 绑定目标是会话里的用户，不是票据里的")
    void bindsToSessionUser() {
        when(tickets.consume("tk")).thenReturn(
                Optional.of(new BindTicketStore.Ticket("feishu", "ou_lisi", "李四")));

        service.confirm("tk", ME);

        // 关键断言：写的是 zhangsan（会话用户）的 id=7，
        // 而不是票据里"李四"对应的任何东西
        verify(users).updateExternalIdentity(7L, "feishu", "ou_lisi");
    }

    @Test
    @DisplayName("★ 未登录 → 拒绝，不写任何数据")
    void unauthenticatedRejected() {
        assertThatThrownBy(() -> service.confirm("tk", null))
                .isInstanceOf(BindService.BindFailedException.class)
                .hasMessageContaining("登录");

        verify(users, never()).updateExternalIdentity(anyLong(), any(), any());
    }

    @Test
    @DisplayName("★ 票据失效（已用过/过期）→ 拒绝")
    void invalidTicketRejected() {
        when(tickets.consume("old")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm("old", ME))
                .isInstanceOf(BindService.BindFailedException.class)
                .hasMessageContaining("失效");

        verify(users, never()).updateExternalIdentity(anyLong(), any(), any());
    }

    @Test
    @DisplayName("★ 该渠道账号已绑给别人 → 拒绝并说清，不覆盖")
    void duplicateRejected() {
        when(tickets.consume("tk")).thenReturn(
                Optional.of(new BindTicketStore.Ticket("feishu", "ou_taken", "别人")));
        when(users.updateExternalIdentity(7L, "feishu", "ou_taken"))
                .thenThrow(new DuplicateKeyException("uk_iam_user_external"));

        assertThatThrownBy(() -> service.confirm("tk", ME))
                .isInstanceOf(BindService.BindFailedException.class)
                .hasMessageContaining("已经绑定");
    }

    @Test
    @DisplayName("预览不消费票据（用户可能只是点开看看再返回）")
    void previewDoesNotConsume() {
        when(tickets.peek("tk")).thenReturn(
                Optional.of(new BindTicketStore.Ticket("feishu", "ou_lisi", "李四")));

        var preview = service.preview("tk", ME);

        assertThat(preview.get("valid")).isEqualTo(true);
        assertThat(preview.get("channel")).isEqualTo("feishu");
        verify(tickets, never()).consume(anyString());
    }

    @Test
    @DisplayName("预览：open_id 要掩码，完整值对人没意义")
    void previewMasksExternalId() {
        when(tickets.peek("tk")).thenReturn(Optional.of(
                new BindTicketStore.Ticket("feishu", "ou_7d8a6e6df7621556ce0d21922b676706ccs", "李四")));

        var preview = service.preview("tk", ME);

        String masked = (String) preview.get("externalIdMasked");
        assertThat(masked).doesNotContain("7d8a6e6df7621556ce0d21922b676706ccs");
        assertThat(masked).contains("…");
    }

    @Test
    @DisplayName("未登录时预览只回一个 authenticated=false，不泄露票据内容")
    void previewWithoutLoginLeaksNothing() {
        var preview = service.preview("tk", null);

        assertThat(preview.get("authenticated")).isEqualTo(false);
        assertThat(preview).doesNotContainKeys("channel", "displayName", "externalIdMasked");
        verify(tickets, never()).peek(anyString());
    }

    @Test
    @DisplayName("解绑只作用于会话用户")
    void unbindTargetsSessionUser() {
        service.unbind(ME);

        verify(users).updateExternalIdentity(7L, null, null);
    }

    @Test
    @DisplayName("★ 解绑是幂等的——重复点不报错")
    void unbindIsIdempotent() {
        // 用户点了两次、或网络重试。第二次不该得到「你还没绑定」这种错误：
        // 他的目的（解除绑定）已经达成，报错只会让人以为没成功
        service.unbind(ME);
        service.unbind(ME);

        verify(users, org.mockito.Mockito.times(2)).updateExternalIdentity(7L, null, null);
    }

    @Test
    @DisplayName("★ 未登录不能解绑")
    void unbindRequiresAuthentication() {
        assertThatThrownBy(() -> service.unbind(null))
                .isInstanceOf(BindService.BindFailedException.class)
                .hasMessageContaining("登录");

        // 未认证时**不能**落到 updateExternalIdentity——那会把某个用户的绑定清掉，
        // 而调用方根本没有身份
        verify(users, never()).updateExternalIdentity(anyLong(), any(), any());
    }

    // ── 状态查询（「我的账号」页依赖它） ──

    @Test
    @DisplayName("★ 未登录时状态返回 authenticated=false，不抛异常")
    void statusUnauthenticatedIsNotAnError() {
        // 页面在登录跳转前会先调一次。抛异常的话，用户看到的是
        // 「读取失败」而不是「去登录」
        var status = service.status(null);

        assertThat(status.get("authenticated")).isEqualTo(false);
        assertThat(status).doesNotContainKeys("username", "externalProvider");
    }

    @Test
    @DisplayName("★ 已绑定时返回渠道与**掩码**的 id")
    void statusShowsMaskedIdOnly() {
        when(users.selectExternalIdentity(7L)).thenReturn(
                java.util.Map.of("provider", "feishu", "externalId", "ou_1234567890abcdef"));

        var status = service.status(ME);

        assertThat(status.get("authenticated")).isEqualTo(true);
        assertThat(status.get("username")).isEqualTo("zhangsan");
        assertThat(status.get("externalProvider")).isEqualTo("feishu");
        // **完整 id 不能出现在响应里**：它是一份可被冒用的标识，
        // 而用户需要确认的只是「绑的是不是我」
        assertThat(String.valueOf(status.get("externalIdMasked")))
                .as("完整 open_id 不该原样返回")
                .doesNotContain("ou_1234567890abcdef");
    }

    @Test
    @DisplayName("未绑定时 provider 为空，页面据此显示「未绑定」")
    void statusUnboundHasNoProvider() {
        when(users.selectExternalIdentity(7L)).thenReturn(null);

        var status = service.status(ME);

        assertThat(status.get("authenticated")).isEqualTo(true);
        assertThat(status.get("externalProvider")).isNull();
        assertThat(status.get("externalIdMasked")).isNull();
    }
}

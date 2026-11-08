package com.liusongboyy.chatdata.channel;

import com.liusongboyy.chatdata.channel.answer.AnswerStore;
import com.liusongboyy.chatdata.channel.ask.AskClient;
import com.liusongboyy.chatdata.channel.ask.AskResult;
import com.liusongboyy.chatdata.channel.identity.BindTicketIssuer;
import com.liusongboyy.chatdata.channel.identity.ChannelIdentityService;
import com.liusongboyy.chatdata.channel.identity.ChannelTokenService;
import com.liusongboyy.chatdata.channel.inbound.InboundMessage;
import com.liusongboyy.chatdata.channel.inbound.InboundQueue;
import com.liusongboyy.chatdata.channel.session.ChannelSessionService;
import com.liusongboyy.chatdata.channel.spi.ChannelCard;
import com.liusongboyy.chatdata.channel.spi.ChannelRegistry;
import com.liusongboyy.chatdata.channel.spi.ChannelRenderer;
import com.liusongboyy.chatdata.channel.spi.ChannelSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 渠道分发的**接线验证**。
 *
 * <h2>为什么这条非写不可</h2>
 *
 * <p>阶段 1 把 {@code MessageHandler} 从「直接依赖 FeishuCardRenderer / FeishuSender」
 * 改成了「按 {@code msg.channel()} 去注册表取实现」。这个改造的**唯一价值**
 * 就是"接第二个渠道不用改这个类"，而这一点在编译期看不出来——
 * 构造器能通过、单测能通过，但分发可能是错的。
 *
 * <p>所以这里用一个假渠道（{@code testchan}）跑完整链路：
 * 如果实现里还残留着"总是用飞书"的假设，这条测试会红。
 *
 * <h2>它不验什么</h2>
 *
 * <p>不验问数逻辑、不验卡片内容——那些由 {@code MessageHandlerCommandTest}
 * 与 {@code FeishuCardRendererTest} 覆盖。这里只看"消息进了哪个渠道的实现"。
 */
class MessageHandlerChannelRoutingTest {

    /** 记录收到什么的假渠道。 */
    private static final class RecordingChannel implements ChannelRenderer, ChannelSender {
        final List<ChannelCard> sent = new ArrayList<>();
        final String id;

        RecordingChannel(String id) {
            this.id = id;
        }

        @Override public String channel() { return id; }

        // ── Renderer ──
        @Override public ChannelCard renderResult(String q, AskResult r, Long a, int p) {
            return new ChannelCard(id, id + ":result");
        }
        @Override public ChannelCard renderError(String q, String m) {
            return new ChannelCard(id, id + ":error:" + m);
        }
        @Override public ChannelCard renderBindGuide(String url) {
            return new ChannelCard(id, id + ":bind");
        }
        @Override public ChannelCard renderAssistantPicker(String q, List<AskClient.AskAssistant> c) {
            return new ChannelCard(id, id + ":picker");
        }
        @Override public ChannelCard renderCapabilities(AskClient.AskAssistant a, AskClient.Capabilities c) {
            return new ChannelCard(id, id + ":caps");
        }
        @Override public ChannelCard renderAssistantSwitched(AskClient.AskAssistant a) {
            return new ChannelCard(id, id + ":switched");
        }
        @Override public ChannelCard renderPending(String q) {
            return new ChannelCard(id, id + ":pending");
        }
        @Override public ChannelCard renderSubscription(String q, AskResult r) {
            return new ChannelCard(id, id + ":sub");
        }
        @Override public ChannelCard renderSubscriptionFailure(String q, String m) {
            return new ChannelCard(id, id + ":subfail");
        }

        // ── Sender ──
        @Override public String send(String t, String rid, ChannelCard card) {
            sent.add(card);
            return id + "-msg-1";
        }
        @Override public String reply(String mid, ChannelCard card) {
            sent.add(card);
            return id + "-msg-1";
        }
        @Override public boolean update(String mid, ChannelCard card) {
            sent.add(card);
            return true;
        }
        @Override public boolean enabled() { return true; }
    }

    private static MessageHandler handler(ChannelRegistry registry,
                                          ChannelIdentityService identities) {
        return new MessageHandler(
                mock(AnswerStore.class), identities, mock(BindTicketIssuer.class),
                mock(ChannelTokenService.class), mock(ChannelSessionService.class),
                mock(AskClient.class), registry, mock(InboundQueue.class));
    }

    private static InboundMessage msg(String channel) {
        return new InboundMessage(channel, "m1", "chat-1", null, "ou-1", "你好", false);
    }

    @Test
    @DisplayName("★ 消息进哪个渠道，就用哪个渠道的渲染器与发送器")
    void routesToChannelFromMessage() {
        var feishu = new RecordingChannel("feishu");
        var other = new RecordingChannel("testchan");
        var registry = new ChannelRegistry(List.of(feishu, other), List.of(feishu, other));

        // 未绑定 → 走"给绑定引导"这条最短路径，能验到渲染 + 发送
        ChannelIdentityService identities = mock(ChannelIdentityService.class);
        when(identities.resolveUsername("testchan", "ou-1")).thenReturn(Optional.empty());

        handler(registry, identities).handle(msg("testchan"));

        assertThat(other.sent).as("testchan 应当收到卡片").hasSize(1);
        assertThat(other.sent.get(0).channel()).isEqualTo("testchan");
        assertThat(feishu.sent).as("飞书不该收到任何东西").isEmpty();
    }

    @Test
    @DisplayName("★ 未知渠道：不发送、不抛异常，只记日志")
    void unknownChannelDoesNothing() {
        var feishu = new RecordingChannel("feishu");
        var registry = new ChannelRegistry(List.of(feishu), List.of(feishu));

        // 不该抛：一个没配置的渠道收到消息不是程序错误，
        // 让它炸掉会连累同一个队列里的其他消息
        handler(registry, mock(ChannelIdentityService.class)).handle(msg("wecom"));

        assertThat(feishu.sent).as("不能回落到飞书发出去——那会把消息发到错误的地方").isEmpty();
    }
}

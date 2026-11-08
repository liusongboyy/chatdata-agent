package com.liusongboyy.chatdata.channel.spi;

import com.liusongboyy.chatdata.channel.ask.AskClient;
import com.liusongboyy.chatdata.channel.ask.AskResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 渠道注册表。
 *
 * <p>它是「接第二个渠道不用改 MessageHandler」这件事的支点，
 * 所以两条性质必须守住：
 *
 * <ol>
 *   <li><b>按标识取实现</b>——调用方只认字符串，不认具体类型</li>
 *   <li><b>重复注册在启动时就炸</b>——而不是等到某条消息发出去、
 *       用户看到两个不同样式的卡片</li>
 * </ol>
 */
class ChannelRegistryTest {

    private static ChannelRenderer renderer(String channel) {
        return new ChannelRenderer() {
            @Override public String channel() { return channel; }
            @Override public ChannelCard renderResult(String q, AskResult r, Long a, int p) {
                return new ChannelCard(channel, "{}");
            }
            @Override public ChannelCard renderError(String q, String m) {
                return new ChannelCard(channel, "{}");
            }
            @Override public ChannelCard renderBindGuide(String url) {
                return new ChannelCard(channel, "{}");
            }
            @Override public ChannelCard renderAssistantPicker(String q, List<AskClient.AskAssistant> c) {
                return new ChannelCard(channel, "{}");
            }
            @Override public ChannelCard renderCapabilities(AskClient.AskAssistant a, AskClient.Capabilities c) {
                return new ChannelCard(channel, "{}");
            }
            @Override public ChannelCard renderAssistantSwitched(AskClient.AskAssistant a) {
                return new ChannelCard(channel, "{}");
            }
            @Override public ChannelCard renderPending(String q) {
                return new ChannelCard(channel, "{}");
            }
            @Override public ChannelCard renderSubscription(String q, AskResult r) {
                return new ChannelCard(channel, "{}");
            }
            @Override public ChannelCard renderSubscriptionFailure(String q, String m) {
                return new ChannelCard(channel, "{}");
            }
        };
    }

    private static ChannelSender sender(String channel) {
        return new ChannelSender() {
            @Override public String channel() { return channel; }
            @Override public String send(String t, String id, ChannelCard c) { return "m1"; }
            @Override public String reply(String id, ChannelCard c) { return "m1"; }
            @Override public boolean update(String id, ChannelCard c) { return true; }
            @Override public boolean enabled() { return true; }
        };
    }

    @Test
    @DisplayName("★ 按标识取实现——调用方只认字符串")
    void looksUpByChannelId() {
        var reg = new ChannelRegistry(List.of(renderer("feishu"), renderer("dingtalk")),
                List.of(sender("feishu"), sender("dingtalk")));

        assertThat(reg.renderer("feishu")).isPresent();
        assertThat(reg.renderer("dingtalk")).isPresent();
        assertThat(reg.sender("feishu")).isPresent();
        assertThat(reg.channels()).containsExactlyInAnyOrder("feishu", "dingtalk");
    }

    @Test
    @DisplayName("未知渠道返回空，不抛异常——那是配置问题，不是程序错误")
    void unknownChannelIsEmpty() {
        var reg = new ChannelRegistry(List.of(renderer("feishu")), List.of(sender("feishu")));

        assertThat(reg.renderer("wecom")).isEmpty();
        assertThat(reg.sender("wecom")).isEmpty();
        assertThat(reg.renderer(null)).isEmpty();
    }

    @Test
    @DisplayName("★ 两个实现声明同一渠道 → 启动即失败")
    void duplicateChannelFailsFast() {
        assertThatThrownBy(() -> new ChannelRegistry(
                List.of(renderer("feishu"), renderer("feishu")), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("两个渲染器");

        assertThatThrownBy(() -> new ChannelRegistry(
                List.of(), List.of(sender("feishu"), sender("feishu"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("两个发送器");
    }

    @Test
    @DisplayName("★ 卡片载荷必须带渠道标识——传错渠道要能当场发现")
    void cardRequiresChannel() {
        assertThatThrownBy(() -> new ChannelCard(null, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChannelCard("feishu", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

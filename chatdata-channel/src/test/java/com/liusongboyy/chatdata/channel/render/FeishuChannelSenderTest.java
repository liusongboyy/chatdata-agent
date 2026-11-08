package com.liusongboyy.chatdata.channel.render;

import com.liusongboyy.chatdata.channel.config.FeishuProperties;
import com.liusongboyy.chatdata.channel.spi.ChannelCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 飞书发送器的适配层。
 *
 * <p>它只做一件事：把 {@link ChannelCard} 拆成飞书要的卡片 JSON，
 * 并**在传错渠道时立刻炸掉**。
 *
 * <p>后者是这里唯一的实质逻辑。不拦的话，错误会一路传到 HTTP 请求体里，
 * 变成一句 {@code Cannot deserialize}——与真实原因（传错了渠道）相距甚远。
 */
class FeishuChannelSenderTest {

    private static FeishuChannelSender sender() {
        // 未配置 app_id/secret：FeishuSender 的所有方法都返回 null/false，不抛异常。
        // 这里要验的是适配层，不是真实发送
        return new FeishuChannelSender(new FeishuSender(new FeishuProperties()));
    }

    @Test
    @DisplayName("渠道标识是 feishu——它写进去重表与会话映射，不能随便改")
    void channelId() {
        assertThat(sender().channel()).isEqualTo("feishu");
    }

    @Test
    @DisplayName("★ 收到别的渠道的卡片 → 立刻抛，不往下传")
    void rejectsForeignCard() {
        var foreign = new ChannelCard("dingtalk", "{}");

        assertThatThrownBy(() -> sender().send("chat_id", "c1", foreign))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dingtalk");

        assertThatThrownBy(() -> sender().reply("m1", foreign))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("★ 载荷不是字符串 → 抛，而不是让 HTTP 层去报反序列化错误")
    void rejectsNonStringPayload() {
        var bad = new ChannelCard("feishu", 12345);

        assertThatThrownBy(() -> sender().send("chat_id", "c1", bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON 字符串");
    }

    @Test
    @DisplayName("未配置时 enabled=false，且发送返回 null 而不是抛异常")
    void disabledChannelFailsSoftly() {
        var s = sender();
        var card = new ChannelCard("feishu", "{\"config\":{}}");

        assertThat(s.enabled()).isFalse();
        // 上层的"尽力发送 + 失败兜底"逻辑依赖这个返回值，而不是异常
        assertThat(s.send("chat_id", "c1", card)).isNull();
    }
}

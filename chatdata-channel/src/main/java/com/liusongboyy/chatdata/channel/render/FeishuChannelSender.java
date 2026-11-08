package com.liusongboyy.chatdata.channel.render;

import com.liusongboyy.chatdata.channel.spi.ChannelCard;
import com.liusongboyy.chatdata.channel.spi.ChannelSender;
import org.springframework.stereotype.Component;

/**
 * 飞书的发送器实现：把 {@link FeishuSender} 包成 {@link ChannelSender}。
 *
 * <h2>载荷类型校验</h2>
 *
 * <p>收到的 {@link ChannelCard#payload()} 必须是 {@code String}（卡片 JSON）。
 * 不是的话说明有人把别的渠道的卡片传给了飞书 sender——那种错如果不在这里拦住，
 * 会一路传到 HTTP 请求体里，变成一句 {@code Cannot deserialize} 之类的报错，
 * 与真实原因（传错了渠道）相距甚远。
 *
 * <h2>enabled() 透传</h2>
 *
 * <p>飞书没配 {@code app_id} / {@code app_secret} 时，{@code FeishuSender}
 * 的所有方法都返回 null/false（不抛异常）。这里保持一致——
 * 上层的"尽力发送 + 失败兜底"逻辑不该因为渠道没配置而走到异常分支。
 */
@Component
public class FeishuChannelSender implements ChannelSender {

    private final FeishuSender delegate;

    public FeishuChannelSender(FeishuSender delegate) {
        this.delegate = delegate;
    }

    @Override
    public String channel() {
        return FeishuChannelRenderer.CHANNEL;
    }

    @Override
    public String send(String receiveIdType, String receiveId, ChannelCard card) {
        return delegate.sendCard(receiveIdType, receiveId, payloadOf(card));
    }

    @Override
    public String reply(String messageId, ChannelCard card) {
        return delegate.replyCard(messageId, payloadOf(card));
    }

    @Override
    public boolean update(String messageId, ChannelCard card) {
        return delegate.updateCard(messageId, payloadOf(card));
    }

    @Override
    public boolean enabled() {
        return delegate.enabled();
    }

    /**
     * 取出卡片 JSON，并校验它确实是给飞书的。
     *
     * <p>失败方向是**抛异常**而不是返回 null：传错渠道是编程错误，
     * 不是运行时偶发。让它炸在离产出点最近的地方。
     */
    private static String payloadOf(ChannelCard card) {
        if (!FeishuChannelRenderer.CHANNEL.equals(card.channel())) {
            throw new IllegalArgumentException(
                    "飞书发送器收到了「" + card.channel() + "」的卡片。"
                            + "渲染器与发送器必须是同一个渠道的实现");
        }
        if (!(card.payload() instanceof String json)) {
            throw new IllegalArgumentException(
                    "飞书卡片的载荷应当是 JSON 字符串，实际是 "
                            + card.payload().getClass().getName());
        }
        return json;
    }
}

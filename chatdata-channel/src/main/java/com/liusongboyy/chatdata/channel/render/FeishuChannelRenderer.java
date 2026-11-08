package com.liusongboyy.chatdata.channel.render;

import com.liusongboyy.chatdata.channel.ask.AskClient;
import com.liusongboyy.chatdata.channel.ask.AskResult;
import com.liusongboyy.chatdata.channel.spi.ChannelCard;
import com.liusongboyy.chatdata.channel.spi.ChannelRenderer;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 飞书的渲染器实现：把 {@link FeishuCardRenderer} 包成 {@link ChannelRenderer}。
 *
 * <h2>为什么是包装而不是改造</h2>
 *
 * <p>{@code FeishuCardRenderer} 有 28KB，里面每一行版面参数都是对着手机端
 * 调出来的（列数上限 5、行数上限 10、SQL 用折叠面板…）。
 * 为了"抽象"去重写它，等于把那些经验全部重新验证一遍。
 *
 * <p>所以这里只做一层薄包装：接口方法 → 原方法调用 → 包成 {@link ChannelCard}。
 * 行为<b>逐字节相同</b>，这是阶段 1 的验收标准。
 *
 * <h2>卡片载荷是 JSON 字符串</h2>
 *
 * <p>{@code FeishuCardRenderer} 返回的是已经序列化好的卡片 JSON。
 * 包进 {@code Object} 里不转成 Map：转一道再序列化回去，键的顺序会变，
 * 而飞书卡片的某些字段（比如 {@code elements} 数组）顺序是有意义的。
 */
@Component
public class FeishuChannelRenderer implements ChannelRenderer {

    /** 渠道标识。写进去重表、会话映射、订阅表。**改了会让历史数据对不上。** */
    public static final String CHANNEL = "feishu";

    private final FeishuCardRenderer delegate;

    public FeishuChannelRenderer(FeishuCardRenderer delegate) {
        this.delegate = delegate;
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public ChannelCard renderResult(String question, AskResult result, Long answerId, int page) {
        return wrap(delegate.renderResult(question, result, answerId, page));
    }

    @Override
    public ChannelCard renderError(String question, String message) {
        return wrap(delegate.renderError(question, message));
    }

    @Override
    public ChannelCard renderBindGuide(String bindUrl) {
        return wrap(delegate.renderBindGuide(bindUrl));
    }

    @Override
    public ChannelCard renderAssistantPicker(String question, List<AskClient.AskAssistant> choices) {
        return wrap(delegate.renderAssistantPicker(question, choices));
    }

    @Override
    public ChannelCard renderCapabilities(AskClient.AskAssistant assistant,
                                          AskClient.Capabilities caps) {
        return wrap(delegate.renderCapabilities(assistant, caps));
    }

    @Override
    public ChannelCard renderAssistantSwitched(AskClient.AskAssistant chosen) {
        return wrap(delegate.renderAssistantSwitched(chosen));
    }

    @Override
    public ChannelCard renderPending(String question) {
        return wrap(delegate.renderPending(question));
    }

    @Override
    public ChannelCard renderSubscription(String question, AskResult result) {
        return wrap(delegate.subscriptionCard(question, result));
    }

    @Override
    public ChannelCard renderSubscriptionFailure(String question, String message) {
        return wrap(delegate.subscriptionFailureCard(question, message));
    }

    private ChannelCard wrap(String cardJson) {
        return new ChannelCard(CHANNEL, cardJson);
    }
}

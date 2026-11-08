package com.liusongboyy.chatdata.channel.spi;

import com.liusongboyy.chatdata.channel.ask.AskClient;
import com.liusongboyy.chatdata.channel.ask.AskResult;

import java.util.List;

/**
 * 把问数结果变成某个渠道能显示的形态。
 *
 * <h2>为什么这些方法在接口上，而不是一个通用的 {@code render(kind, data)}</h2>
 *
 * <p>通用入口看起来更"抽象"，但它把**有哪些卡片类型**这件事藏进了一个字符串参数里。
 * 结果是：新增一种卡片时，编译器不会提醒任何实现类；而漏实现的表现是
 * 运行时才出现的「未知卡片类型」——那正是本项目反复要避免的静默失败。
 *
 * <p>列成方法之后，加一种卡片 = 接口上多一个方法 = 所有实现编译不过。
 * 这是刻意的摩擦。
 *
 * <h2>实现方注意</h2>
 *
 * <p>返回的 {@link ChannelCard#channel()} 必须与 {@link #channel()} 一致。
 * 不一致会让 {@link ChannelSender} 在发送时报错，而那时已经离产出点很远了。
 */
public interface ChannelRenderer {

    /** 渠道标识，如 {@code feishu}。写进消息去重表与会话映射。 */
    String channel();

    /**
     * 问数结果。
     *
     * @param question 原始问题，用于卡片标题
     * @param answerId 答案 id，翻页与反馈要用
     * @param page     第几页，从 0 开始
     */
    ChannelCard renderResult(String question, AskResult result, Long answerId, int page);

    /** 出错。{@code message} 是给用户看的，实现方不该再包一层"操作失败"。 */
    ChannelCard renderError(String question, String message);

    /** 尚未绑定渠道账号：给一条能点的绑定链接。 */
    ChannelCard renderBindGuide(String bindUrl);

    /** 让用户选助理（绑了多个域、或没指定时）。 */
    ChannelCard renderAssistantPicker(String question, List<AskClient.AskAssistant> choices);

    /** 这个助理能问什么。 */
    ChannelCard renderCapabilities(AskClient.AskAssistant assistant,
                                   AskClient.Capabilities caps);

    /** 切换助理后的确认。 */
    ChannelCard renderAssistantSwitched(AskClient.AskAssistant chosen);

    /**
     * 占位卡片（"正在查询…"）。
     *
     * <p>它是**原地更新的载体**：发出去之后拿回消息 id，
     * 结果回来时用 {@link ChannelSender#update} 换成结果卡片。
     * 渠道若不支持更新，实现方仍应返回它——上层会降级为再发一条。
     */
    ChannelCard renderPending(String question);

    // ── 定时推送 ──
    //
    // 推送卡片与问数卡片形态不同：没有"谁问的"，有"按什么周期推的"。
    // 放进同一个接口而不是另起一个 SubscriptionRenderer——
    // 因为它们的**消费方是同一个发送器**，拆开只会让 SubscriptionRunner
    // 多持有一个 registry。

    /**
     * 定时推送的结果卡片。
     *
     * @param question 订阅的问题原文。卡片标题要用它——订阅可能有好几条，
     *                 只写"定时报表"用户分不清是哪一条
     */
    ChannelCard renderSubscription(String question, AskResult result);

    /** 定时推送失败。 */
    ChannelCard renderSubscriptionFailure(String question, String message);
}

package com.liusongboyy.chatdata.channel.spi;

/**
 * 把卡片送到用户面前。
 *
 * <h2>三个动作，对应渠道真正不同的地方</h2>
 *
 * <p>「发一条新消息」「回复某条消息」「更新已有消息」——各家渠道对这三件事的
 * 支持程度不同，但语义是通用的。其余的东西（验签、webhook 端点格式、
 * 卡片结构）都不在这里，因为它们各不相同且无法有意义地统一。
 *
 * <h2>返回 null 表示发送失败，不抛异常</h2>
 *
 * <p>发送失败在这条链路上是<b>常见</b>的（网络抖动、渠道限流、消息过期），
 * 而不是异常情况。用返回值让调用方必须处理，用异常则会让人"顺手 catch 掉"。
 *
 * <p>但失败必须<b>显式记日志</b>——静默吞掉会让"用户说没收到"变成一个查不动的问题。
 */
public interface ChannelSender {

    /** 渠道标识，如 {@code feishu}。 */
    String channel();

    /**
     * 发一条新消息。
     *
     * @param receiveIdType 接收方 id 的类型（飞书是 {@code chat_id} / {@code open_id}）。
     *                      这个参数是飞书特有的——其他渠道可以忽略。
     *                      它在这里是因为**上层需要表达"发给谁"**，
     *                      而不同渠道对"谁"的标识不同
     * @param receiveId     接收方标识
     * @return 渠道侧消息 id（用于后续更新）；失败返回 {@code null}
     */
    String send(String receiveIdType, String receiveId, ChannelCard card);

    /**
     * 回复某条消息。
     *
     * <p>群聊里用这个而不是 {@link #send}：回复会带引用，用户能看出答的是哪句。
     * 渠道不支持回复时，实现方可退化为 {@link #send}——那是行为差异，
     * 但比"群里多一条不知道回谁的卡片"好。
     *
     * @return 渠道侧消息 id；失败返回 {@code null}
     */
    String reply(String messageId, ChannelCard card);

    /**
     * 原地更新一条已发出的消息。
     *
     * <p><b>渠道不支持时必须返回 {@code false} 而不是抛异常。</b>
     * 上层的逻辑是"尽力更新 + 失败兜底再发一条"（见 {@code MessageHandler}），
     * 抛异常会让那个兜底分支变成异常处理分支，而异常处理分支很容易写错。
     *
     * @return 更新成功为 true；渠道不支持、消息过期、接口失败都是 false
     */
    boolean update(String messageId, ChannelCard card);

    /** 这个渠道当前能不能发消息（配置是否完整）。 */
    boolean enabled();
}

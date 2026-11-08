package com.liusongboyy.chatdata.channel.spi;

/**
 * 一张已经渲染好的卡片。
 *
 * <h2>为什么是不透明包装而不是泛型卡片模型</h2>
 *
 * <p>每个渠道的卡片格式完全不同：飞书是 JSON、钉钉是 markdown + ActionCard、
 * 企微是另一种 JSON。抽一个"通用卡片模型"等于把三家的格式取交集——
 * 交集一定小于任何一家，于是每个渠道都要为自己的特有能力另开旁路。
 *
 * <p>所以这里只做一件事：<b>把渠道自己生成的载荷包起来，让上层能传但看不懂</b>。
 * 生成它的是 {@link ChannelRenderer}，消费它的是 {@link ChannelSender}，
 * 两者必须是同一个渠道的实现。
 *
 * <p>{@link #channel()} 存在的理由：上层（如 {@code MessageHandler}）可能同时
 * 持有多个渠道的卡片，传错渠道的载荷会得到一句看不懂的报错。
 * 让 sender 能断言"这是给我的"。
 *
 * @param channel  产出这张卡片的渠道标识
 * @param payload  渠道自己的载荷（飞书是卡片 JSON 字符串）
 */
public record ChannelCard(String channel, Object payload) {

    public ChannelCard {
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("ChannelCard 必须带渠道标识");
        }
        if (payload == null) {
            throw new IllegalArgumentException("ChannelCard 的载荷不能为空");
        }
    }
}

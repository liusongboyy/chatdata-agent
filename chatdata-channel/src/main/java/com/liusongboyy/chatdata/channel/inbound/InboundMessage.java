package com.liusongboyy.chatdata.channel.inbound;

/**
 * 一条入站消息。
 *
 * <p>只含飞书能提供的字段，<b>不做「通用消息模型」</b>。
 * 抽成跨渠道通用形态的成本在于：接口定错之后所有实现都要跟着改，
 * 而那时看起来是「各实现自己有问题」。等钉钉/企微真接进来再抽。
 *
 * @param channel   渠道标识，如 {@code feishu}。同时写进去重表与会话映射
 * @param messageId 飞书 {@code message_id}。<b>去重键</b>——不是 {@code event_id}：
 *                  后者标识「一次推送」，业务失败重推时会变
 * @param chatKey   会话键。私聊用 {@code open_id}，群聊用
 *                  {@code chat_id:open_id}——群里换人要开新会话，
 *                  否则李四的追问会接上张三的上下文，而那里面有张三权限下的数据
 * @param chatId    群 id。私聊时为 null
 * @param senderId  发送者的 {@code open_id}。用于查身份绑定
 * @param text      消息纯文本。非文本消息（图片、卡片）不进问数链路
 * @param fromGroup 是否来自群聊。决定回复用 reply 还是 create
 */
public record InboundMessage(
        String channel,
        String messageId,
        String chatKey,
        String chatId,
        String senderId,
        String text,
        boolean fromGroup
) {
}

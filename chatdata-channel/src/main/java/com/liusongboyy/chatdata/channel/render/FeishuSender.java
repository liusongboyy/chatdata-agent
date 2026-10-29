package com.liusongboyy.chatdata.channel.render;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody;
import com.lark.oapi.service.im.v1.model.ReplyMessageResp;
import com.liusongboyy.chatdata.channel.config.FeishuProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 发送飞书消息。
 *
 * <h2>群聊走 reply，私聊走 create</h2>
 *
 * <p>群聊里用 {@code /im/v1/messages/:message_id/reply} 引用原消息回复——
 * 对话一长，不引用就分不清在答谁。私聊没有这个问题，直接发即可。
 *
 * <h2>失败不抛给调用方</h2>
 *
 * <p>发消息失败通常意味着应用凭证过期或被移出群。这类错误已经发生过了
 * ——重试也不会成功，而把异常抛回去只会让工作线程记一条重复的错误日志。
 * 这里记 ERROR 并返回 false，由调用方决定是否需要提示。
 */
@Component
public class FeishuSender {

    private static final Logger log = LoggerFactory.getLogger(FeishuSender.class);

    private final Client client;
    private final boolean enabled;

    public FeishuSender(FeishuProperties properties) {
        this.enabled = properties.isEnabled()
                && properties.getAppId() != null && !properties.getAppId().isBlank();
        this.client = enabled
                ? Client.newBuilder(properties.getAppId(), properties.getAppSecret())
                        .requestTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                : null;
    }

    /**
     * 回复某条消息（群里用）。
     *
     * @param messageId 被回复的消息 id
     * @param cardJson  卡片 JSON
     * @return 新消息 id；失败返回 null
     */
    public String replyCard(String messageId, String cardJson) {
        if (!enabled) {
            log.debug("飞书未启用，跳过回复");
            return null;
        }
        try {
            ReplyMessageResp resp = client.im().v1().message().reply(
                    ReplyMessageReq.newBuilder()
                            .messageId(messageId)
                            .replyMessageReqBody(ReplyMessageReqBody.newBuilder()
                                    .msgType("interactive")
                                    .content(cardJson)
                                    .build())
                            .build());
            if (!resp.success()) {
                log.error("飞书回复失败：code={} msg={}", resp.getCode(), resp.getMsg());
                return null;
            }
            return resp.getData() == null ? null : resp.getData().getMessageId();
        } catch (Exception e) {
            log.error("飞书回复异常", e);
            return null;
        }
    }

    /**
     * 主动发消息（私聊或按 chat_id）。
     *
     * @param receiveIdType {@code chat_id} 或 {@code open_id}
     * @return 新消息 id；失败返回 null
     */
    public String sendCard(String receiveIdType, String receiveId, String cardJson) {
        if (!enabled) {
            log.debug("飞书未启用，跳过发送");
            return null;
        }
        try {
            CreateMessageResp resp = client.im().v1().message().create(
                    CreateMessageReq.newBuilder()
                            .receiveIdType(receiveIdType)
                            .createMessageReqBody(CreateMessageReqBody.newBuilder()
                                    .receiveId(receiveId)
                                    .msgType("interactive")
                                    .content(cardJson)
                                    .build())
                            .build());
            if (!resp.success()) {
                log.error("飞书发送失败：code={} msg={}", resp.getCode(), resp.getMsg());
                return null;
            }
            return resp.getData() == null ? null : resp.getData().getMessageId();
        } catch (Exception e) {
            log.error("飞书发送异常", e);
            return null;
        }
    }

    public boolean enabled() {
        return enabled;
    }
}

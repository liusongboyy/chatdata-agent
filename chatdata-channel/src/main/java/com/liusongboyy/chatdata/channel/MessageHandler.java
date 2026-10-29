package com.liusongboyy.chatdata.channel;

import com.liusongboyy.chatdata.channel.ask.AskClient;
import com.liusongboyy.chatdata.channel.ask.AskResult;
import com.liusongboyy.chatdata.channel.identity.ChannelIdentityService;
import com.liusongboyy.chatdata.channel.identity.ChannelTokenService;
import com.liusongboyy.chatdata.channel.inbound.InboundMessage;
import com.liusongboyy.chatdata.channel.inbound.InboundQueue;
import com.liusongboyy.chatdata.channel.render.FeishuCardRenderer;
import com.liusongboyy.chatdata.channel.render.FeishuSender;
import com.liusongboyy.chatdata.channel.session.ChannelSessionService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 一条消息的完整处理：身份 → 令牌 → 问数 → 排版 → 回复。
 *
 * <h2>它不做权限判断</h2>
 *
 * <p>这个类里没有一处「这个人能不能查」的判断，因为那全在 ask 里。
 * 渠道只负责「拿他的令牌去问，把答案排回去」。
 * 一旦这里开始出现权限逻辑，就说明边界破了——那是最危险的一种腐化，
 * 因为它看起来像是"顺手加的便利"，实际上是第二个权限判定点。
 */
@Component
public class MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);

    private final ChannelIdentityService identities;
    private final ChannelTokenService tokens;
    private final ChannelSessionService sessions;
    private final AskClient ask;
    private final FeishuCardRenderer renderer;
    private final FeishuSender sender;
    private final InboundQueue queue;

    public MessageHandler(ChannelIdentityService identities, ChannelTokenService tokens,
                          ChannelSessionService sessions, AskClient ask,
                          FeishuCardRenderer renderer, FeishuSender sender,
                          InboundQueue queue) {
        this.identities = identities;
        this.tokens = tokens;
        this.sessions = sessions;
        this.ask = ask;
        this.renderer = renderer;
        this.sender = sender;
        this.queue = queue;
    }

    /** 把本处理器挂到队列上。队列在工作线程里回调它。 */
    @PostConstruct
    void register() {
        queue.setHandler(this::handle);
    }

    void handle(InboundMessage msg) {
        String question = msg.text();
        log.info("渠道消息：channel={} user={} group={} q={}",
                msg.channel(), msg.senderId(), msg.fromGroup(), question);

        // 「新话题」「重置」：显式开新会话。放在身份校验之前——
        // 它不查数据，只动本地的会话映射
        if (isResetCommand(question)) {
            Optional<String> username = identities.resolveUsername(msg.channel(), msg.senderId());
            if (username.isEmpty()) {
                reply(msg, renderer.renderError(question, unboundMessage()));
                return;
            }
            sessions.startNew(msg.channel(), msg.chatKey(), username.get());
            tokens.evict(username.get());
            reply(msg, renderer.renderError(question, "已开始新会话，请直接提问。"));
            return;
        }

        // ① 身份。未绑定 → 给绑定引导，**不发 ask 请求**
        Optional<String> username = identities.resolveUsername(msg.channel(), msg.senderId());
        if (username.isEmpty()) {
            reply(msg, renderer.renderError(question, unboundMessage()));
            return;
        }

        // ② 令牌
        String token;
        try {
            token = tokens.tokenFor(msg.channel(), username.get());
        } catch (ChannelTokenService.TokenUnavailableException e) {
            // 消息本身就是给用户看的（见 ChannelTokenService.describe）
            reply(msg, renderer.renderError(question, e.getMessage()));
            return;
        }

        // ③ 会话。群里按发言人隔离，chatKey 里已经带了 open_id
        String conversationId = sessions.resolve(msg.channel(), msg.chatKey(), username.get());

        // ④ 占位卡片。5–15 秒的沉默会被当成没发出去
        String pendingId = reply(msg, renderer.renderPending(question));

        // ⑤ 问数
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        body.put("sessionId", conversationId);
        // 不指定 domainId/assistantId：让 ask 按默认域解析。
        // 渠道没有"选助理"这个交互，指定反而会撞上用户无权限的域

        String card;
        try {
            AskResult result = ask.ask(token, body);
            card = renderer.renderResult(question, result);
        } catch (AskClient.AskFailedException e) {
            card = renderer.renderError(question, e.getMessage());
        } catch (RuntimeException e) {
            log.error("问数处理异常：channel={} user={}", msg.channel(), username.get(), e);
            card = renderer.renderError(question, "处理失败，请稍后再试。");
        }

        // ⑥ 更新占位卡片。飞书没有"更新任意消息"的稳妥写法时，宁可再发一条——
        //    发两条最多是刷屏，漏掉一条是"用户永远等不到答案"
        sendCardUpdate(msg, pendingId, card);
    }

    private static boolean isResetCommand(String text) {
        String t = text == null ? "" : text.trim();
        return t.equals("新话题") || t.equals("重置") || t.equals("新的会话")
                || t.equals("/new") || t.equals("/reset");
    }

    private static String unboundMessage() {
        return "你还没有绑定问数账号。请在 Web 端「账号中心」让管理员为你绑定飞书身份后再试。";
    }

    /**
     * 发送卡片。
     *
     * @return 新消息 id（用于后续更新）；发送失败返回 null
     */
    private String reply(InboundMessage msg, String cardJson) {
        if (msg.fromGroup() && msg.messageId() != null) {
            return sender.replyCard(msg.messageId(), cardJson);
        }
        // 私聊：用 chat_id 发（有的话），否则 open_id
        String receiveId = msg.chatId() != null && !msg.chatId().isBlank()
                ? msg.chatId() : msg.senderId();
        String type = msg.chatId() != null && !msg.chatId().isBlank() ? "chat_id" : "open_id";
        return sender.sendCard(type, receiveId, cardJson);
    }

    /**
     * 把占位卡片换成结果。
     *
     * <p>当前实现是**再发一条**，而不是 PATCH 占位消息。
     * 理由：飞书的「更新卡片」接口（{@code PATCH /im/v1/messages/:id}）只对
     * 卡片消息生效，且对「发送后多久内可更新」有限制；失败时的失败态
     * （占位卡片永远停在"正在查询"）比多发一条更糟。
     *
     * <p>发两条的代价是可接受的：用户看到"正在查询…"然后看到结果，
     * 语义清晰。等链路稳定后再改成真正的更新。
     */
    private void sendCardUpdate(InboundMessage msg, String pendingId, String cardJson) {
        reply(msg, cardJson);
    }
}

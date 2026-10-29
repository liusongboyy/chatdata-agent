package com.liusongboyy.chatdata.channel.inbound;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.liusongboyy.chatdata.channel.config.FeishuProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * 飞书事件入口。
 *
 * <h2>为什么收 {@code byte[]} 而不是反序列化好的对象</h2>
 *
 * <p>签名是 {@code sha256(timestamp + nonce + encryptKey + rawBody)}，
 * 用的是<b>原始字节</b>。让 Spring 先反序列化再重新序列化，得到的字节
 * 与原文不同（空格、键序、Unicode 转义），签名为假失败——
 * 这是这类校验最常见的坑，见 {@link FeishuCrypto}。
 *
 * <p>因此这里从 {@code request.getInputStream()} 直接读字节，<b>不用
 * {@code @RequestBody}</b>。{@code @RequestBody byte[]} 虽然能工作
 * （{@code ByteArrayHttpMessageConverter} 排在 Jackson 之前且接受 {@code *\/*}），
 * 但那依赖转换器的注册顺序——读代码的人不该被迫去查这件事。
 *
 * <h2>3 秒约束</h2>
 *
 * <p>飞书要求 3 秒内返回 200（TCP 建连超 2 秒），否则重推。
 * 本方法只做「验签 → 解密 → 去重 → 入队」，然后在工作线程里问数。
 */
@RestController
@RequestMapping("/channel/feishu")
public class FeishuEventController {

    private static final Logger log = LoggerFactory.getLogger(FeishuEventController.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final FeishuCrypto crypto;
    private final FeishuProperties properties;
    private final InboundDedup dedup;
    private final InboundQueue queue;
    private final CardActionHandler cardActions;

    public FeishuEventController(FeishuCrypto crypto, FeishuProperties properties,
                                 InboundDedup dedup, InboundQueue queue,
                                 CardActionHandler cardActions) {
        this.crypto = crypto;
        this.properties = properties;
        this.dedup = dedup;
        this.queue = queue;
        this.cardActions = cardActions;
    }

    /**
     * 配置缺失时**启动失败**，而不是跑起来再慢慢出错。
     *
     * <p>没有 Encrypt Key 时事件是明文传输的，而 body 里有用户提问原文与 open_id。
     * 让它在启动时就报出来，比事后从日志里发现"原来一直是明文的"要好。
     */
    @EventListener(ApplicationReadyEvent.class)
    void requireEncryptKey() {
        if (!properties.isEnabled()) {
            log.warn("飞书渠道已禁用（channel.feishu.enabled=false）");
            return;
        }
        if (isBlank(properties.getEncryptKey())) {
            throw new IllegalStateException(
                    "缺少 channel.feishu.encrypt-key（环境变量 FEISHU_ENCRYPT_KEY）。"
                            + "不配则事件明文传输，body 含用户提问原文与 open_id。");
        }
        log.info("飞书渠道已启用：appId={} groupReplyMode={}",
                properties.getAppId(), properties.getGroupReplyMode());
    }

    /**
     * 事件入口。
     *
     * <p>返回 200 是<b>协议要求</b>，不代表业务成功——业务结果通过回复消息告知。
     */
    @PostMapping("/event")
    public ResponseEntity<Map<String, Object>> event(
            @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Lark-Signature", required = false) String signature,
            HttpServletRequest request) {

        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (IOException e) {
            log.warn("读取飞书事件请求体失败", e);
            return ResponseEntity.badRequest().body(Map.of("message", "读取请求体失败"));
        }

        if (!properties.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "渠道未启用"));
        }

        JsonNode envelope;
        try {
            envelope = JSON.readTree(rawBody);
        } catch (RuntimeException e) {
            // Jackson 3 的异常继承 RuntimeException（Jackson 2 是受检的 IOException）
            log.warn("飞书事件不是合法 JSON：{}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("message", "非法 JSON"));
        }

        // ① 先解密。**必须在判 url_verification 之前**——配了 Encrypt Key 时
        //    飞书连 challenge 探测都是加密的（body 是 {"encrypt":"..."}），
        //    type 字段在密文里。解密前判 type 永远判不出来，
        //    于是会掉进下面的验签分支——那正是"Challenge code 没有返回"的真因。
        //
        //    解密本身也是一道认证：能解出明文说明对端持有 Encrypt Key。
        JsonNode body = envelope;
        if (envelope.hasNonNull("encrypt")) {
            try {
                byte[] plain = crypto.decrypt(envelope.get("encrypt").asText());
                body = JSON.readTree(plain);
            } catch (RuntimeException e) {
                log.warn("飞书事件解密失败：remote={} {}", request.getRemoteAddr(), e.getMessage());
                return ResponseEntity.badRequest().body(Map.of("message", "解密失败"));
            }
        }

        // ② 回调地址校验。飞书这个请求**不带签名头**——文档原话是
        //    「接收到开放平台推送的事件时（**不包括请求网址校验**）」。
        //    所以这一路用 Verification Token 校验来源，而不是签名。
        if ("url_verification".equals(body.path("type").asText())) {
            String challenge = body.path("challenge").asString("");
            if (challenge.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("message", "缺少 challenge"));
            }
            String expected = properties.getVerificationToken();
            if (expected != null && !expected.isBlank()) {
                String actual = body.path("token").asString("");
                // Verification Token 不是密钥，比的是"是不是同一个应用"，
                // 不是"有没有破解难度"——常量时间比较在这里没有意义
                if (!expected.equals(actual)) {
                    log.warn("回调地址校验失败：Verification Token 不匹配 remote={}",
                            request.getRemoteAddr());
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(Map.of("message", "Verification Token 不匹配"));
                }
            } else {
                log.debug("未配置 verification-token，跳过来源校验（建议配置）");
            }
            log.info("回调地址校验通过：remote={}", request.getRemoteAddr());
            return ResponseEntity.ok(Map.of("challenge", challenge));
        }

        // ③ 普通事件才验签。签名算在**原始（加密后的）body** 上。
        if (!crypto.verify(timestamp, nonce, signature, rawBody)) {
            log.warn("飞书事件验签失败：remote={} ts={} nonce={}",
                    request.getRemoteAddr(), timestamp, nonce);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "签名校验失败"));
        }

        String eventType = body.path("header").path("event_type").asText();

        // ④ 卡片回调：翻页与反馈。
        //
        //    **必须同步处理**，不能入队：飞书的回调响应体决定客户端行为
        //    （弹 toast / 原地更新卡片）。异步了就只能先弹 toast 再延时更新，
        //    那要多一条链路和它的失败态。翻页是查库、反馈是一次 HTTP，够快。
        //
        //    另外它**不能走下面的去重**：翻页按钮被点多次是正常的
        //    （翻过去再翻回来），按 message_id 去重会挡住这些合法操作。
        if ("card.action.trigger".equals(eventType)) {
            return ResponseEntity.ok(cardActions.handle(body));
        }

        // ⑤ 只处理接收消息事件，其余 200 忽略（订阅里可能有别的事件）
        if (!"im.message.receive_v1".equals(eventType)) {
            log.debug("忽略非消息事件：{}", eventType);
            return ResponseEntity.ok(Map.of("code", 0));
        }

        InboundMessage inbound = parse(body);
        if (inbound == null) {
            // 非文本消息（图片/语音）或自己发的消息：回 200，不回任何内容
            return ResponseEntity.ok(Map.of("code", 0));
        }

        // ⑤ 去重。重推直接 200，不重复处理
        if (!dedup.claim(inbound.channel(), inbound.messageId(), inbound.chatKey())) {
            return ResponseEntity.ok(Map.of("code", 0));
        }

        // ⑥ 入队。满了也返回 200——见 InboundQueue 的说明
        queue.offer(inbound);
        return ResponseEntity.ok(Map.of("code", 0));
    }

    /**
     * 解析事件体。
     *
     * @return {@code null} 表示这条不该进问数链路（非文本、自己发的）
     */
    private InboundMessage parse(JsonNode body) {
        JsonNode event = body.path("event");
        JsonNode message = event.path("message");
        String messageId = message.path("message_id").asText();
        if (isBlank(messageId)) {
            return null;
        }
        // 机器人自己发的消息必须跳过，否则会自问自答形成死循环
        if ("app".equalsIgnoreCase(message.path("sender_type").asText())) {
            return null;
        }

        String chatId = message.path("chat_id").asText();
        String chatType = message.path("chat_type").asText();
        String senderId = event.path("sender").path("sender_id").path("open_id").asText();

        String text = extractText(message);
        if (isBlank(text) || isBlank(senderId)) {
            return null;
        }

        boolean fromGroup = "group".equals(chatType);
        // 群里必须按发言人隔离会话：张三问完李四接着问，不能嫁接——
        // 那条上下文里有张三权限下的数据
        String chatKey = fromGroup && !isBlank(chatId) ? chatId + ":" + senderId : senderId;

        return new InboundMessage("feishu", messageId, chatKey, chatId, senderId, text.trim(), fromGroup);
    }

    /**
     * 取消息纯文本。
     *
     * <p>{@code content} 是一个 JSON 字符串（如 {@code {"text":"你好"}}），
     * 而消息体本身也是 JSON——所以这里要二次解析。
     * 群里 @ 机器人时文本里会带 {@code @_user_1} 占位，去掉它再送进问数，
     * 否则模型会把占位符当成提问的一部分。
     */
    private String extractText(JsonNode message) {
        String type = message.path("message_type").asText();
        if (!"text".equals(type)) {
            log.debug("跳过非文本消息：type={}", type);
            return null;
        }
        String content = message.path("content").asText("");
        try {
            JsonNode parsed = JSON.readTree(content);
            String text = parsed.path("text").asText("");
            return text.replaceAll("@_user_\\d+", "").trim();
        } catch (RuntimeException e) {
            log.debug("消息 content 不是合法 JSON：{}", content);
            return null;
        }
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}

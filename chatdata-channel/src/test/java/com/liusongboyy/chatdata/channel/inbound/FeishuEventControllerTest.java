package com.liusongboyy.chatdata.channel.inbound;

import com.liusongboyy.chatdata.channel.config.FeishuProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 飞书事件入口。
 *
 * <p>重点覆盖三类最容易写错的地方：
 * <ol>
 *   <li>验签用的是原始 body 字节（重放必须仍然通过）</li>
 *   <li>{@code url_verification} 必须在验签之后、且原样回 challenge</li>
 *   <li>机器人自己发的消息不能进链路——否则会自问自答死循环</li>
 * </ol>
 */
class FeishuEventControllerTest {

    private static final String KEY = "test-encrypt-key-32bytes-long!!";

    private FeishuProperties properties;
    private InboundDedup dedup;
    private InboundQueue queue;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        properties = new FeishuProperties();
        properties.setEncryptKey(KEY);
        // 测试里不走真实队列：只断言"有没有投递"
        queue = mock(InboundQueue.class);
        when(queue.offer(any())).thenReturn(true);

        dedup = mock(InboundDedup.class);
        when(dedup.claim(anyString(), anyString(), anyString())).thenReturn(true);

        FeishuCrypto crypto = new FeishuCrypto(properties);
        mvc = MockMvcBuilders.standaloneSetup(new FeishuEventController(
                crypto, properties, dedup, queue, mock(CardActionHandler.class))).build();
    }

    private static String sign(String ts, String nonce, byte[] body) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update((ts + nonce + KEY).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(sha.digest(body));
    }

    /** 按飞书文档的算法加密，构造「飞书推过来」的请求体。 */
    private static String encryptBody(String key, String plain) throws Exception {
        byte[] aesKey = java.security.MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(StandardCharsets.UTF_8));
        byte[] iv = java.util.Arrays.copyOf(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest("fixed-iv".getBytes(StandardCharsets.UTF_8)), 16);
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(aesKey, "AES"),
                new javax.crypto.spec.IvParameterSpec(iv));
        byte[] enc = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + enc.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(enc, 0, out, iv.length, enc.length);
        return java.util.Base64.getEncoder().encodeToString(out);
    }

    private static String messageEvent(String messageId, String text, String senderType,
                                       String chatType, String openId) {
        return """
                {"header":{"event_type":"im.message.receive_v1"},
                 "event":{"sender":{"sender_id":{"open_id":"%s"}},
                          "message":{"message_id":"%s","chat_id":"oc_1","chat_type":"%s",
                                     "message_type":"text","sender_type":"%s",
                                     "content":"{\\"text\\":\\"%s\\"}"}}}
                """.formatted(openId, messageId, chatType, senderType, text);
    }

    @Test
    @DisplayName("★ 未签名 → 401，且不处理事件")
    void unsignedRequestRejected() throws Exception {
        mvc.perform(post("/channel/feishu/event")
                        .contentType("application/json")
                        .content(messageEvent("om_1", "你好", "user", "p2p", "ou_a")))
                .andExpect(status().isUnauthorized());

        verify(queue, never()).offer(any());
    }

    @Test
    @DisplayName("★ 签名错误 → 401，且不处理事件")
    void badSignatureRejected() throws Exception {
        String body = messageEvent("om_1", "你好", "user", "p2p", "ou_a");
        mvc.perform(post("/channel/feishu/event")
                        .contentType("application/json")
                        .header("X-Lark-Request-Timestamp", "1700000000")
                        .header("X-Lark-Request-Nonce", "n1")
                        .header("X-Lark-Signature", "deadbeef")
                        .content(body))
                .andExpect(status().isUnauthorized());

        verify(queue, never()).offer(any());
    }

    /**
     * ★ 回调地址校验**不需要签名**，这是飞书的约定。
     *
     * <p>文档原话：「接收到开放平台推送的事件时（**不包括请求网址校验**）」。
     * 曾经把验签放在它前面，结果飞书填回调地址时永远收到 401，
     * 界面上表现为「Challenge code 没有返回」——实测踩过。
     */
    @Test
    @DisplayName("★ url_verification 无签名头也返回 challenge")
    void urlVerificationWorksWithoutSignature() throws Exception {
        mvc.perform(post("/channel/feishu/event")
                        .contentType("application/json")
                        .content("{\"type\":\"url_verification\",\"challenge\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").value("abc123"));
    }

    @Test
    @DisplayName("★ 配了 verification-token 时，token 不匹配 → 401")
    void urlVerificationChecksToken() throws Exception {
        properties.setVerificationToken("expected-token");

        mvc.perform(post("/channel/feishu/event")
                        .contentType("application/json")
                        .content("{\"type\":\"url_verification\",\"challenge\":\"abc\",\"token\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/channel/feishu/event")
                        .contentType("application/json")
                        .content("{\"type\":\"url_verification\",\"challenge\":\"abc\",\"token\":\"expected-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").value("abc"));
    }

    /**
     * ★ 加密的 challenge 探测——这是飞书的**真实**行为。
     *
     * <p>配了 Encrypt Key 后，连 url_verification 都是加密的：
     * body 是 {@code {"encrypt":"..."}}，{@code type} 在密文里。
     *
     * <p>曾经把「判 type」放在解密之前，于是加密探测永远判不出是
     * url_verification，掉进验签分支被 401 掉——界面上就是
     * 「Challenge code 没有返回」。这个用例专门钉住顺序。
     */
    @Test
    @DisplayName("★ 加密的 url_verification 也能返回 challenge（解密必须在判 type 之前）")
    void encryptedUrlVerificationWorks() throws Exception {
        String plain = "{\"type\":\"url_verification\",\"challenge\":\"enc-123\",\"token\":\"t\"}";
        String bodyJson = "{\"encrypt\":\"" + encryptBody(KEY, plain) + "\"}";

        mvc.perform(post("/channel/feishu/event")
                        .contentType("application/json")
                        .content(bodyJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").value("enc-123"));
    }

    @Test
    @DisplayName("url_verification 缺 challenge → 400，不是回一个空的 200")
    void urlVerificationRequiresChallenge() throws Exception {
        mvc.perform(post("/channel/feishu/event")
                        .contentType("application/json")
                        .content("{\"type\":\"url_verification\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("★ 机器人自己发的消息 → 不投递（否则自问自答死循环）")
    void botMessageIgnored() throws Exception {
        String ts = "1700000000";
        String nonce = "n1";
        byte[] body = messageEvent("om_bot", "我是机器人的回复", "app", "p2p", "ou_a")
                .getBytes(StandardCharsets.UTF_8);

        mvc.perform(post("/channel/feishu/event")
                        .contentType("application/json")
                        .header("X-Lark-Request-Timestamp", ts)
                        .header("X-Lark-Request-Nonce", nonce)
                        .header("X-Lark-Signature", sign(ts, nonce, body))
                        .content(body))
                .andExpect(status().isOk());

        verify(queue, never()).offer(any());
    }

    @Test
    @DisplayName("★ 用户消息 → 投递，且群里 chatKey 含发言人（按人隔离会话）")
    void userMessageQueued() throws Exception {
        String ts = "1700000000";
        String nonce = "n1";
        byte[] groupBody = messageEvent("om_g", "上周订单", "user", "group", "ou_zhangsan")
                .getBytes(StandardCharsets.UTF_8);

        mvc.perform(post("/channel/feishu/event")
                        .contentType("application/json")
                        .header("X-Lark-Request-Timestamp", ts)
                        .header("X-Lark-Request-Nonce", nonce)
                        .header("X-Lark-Signature", sign(ts, nonce, groupBody))
                        .content(groupBody))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(InboundMessage.class);
        verify(queue).offer(captor.capture());
        InboundMessage msg = captor.getValue();
        assertThat(msg.text()).isEqualTo("上周订单");
        assertThat(msg.fromGroup()).isTrue();
        // 群聊必须带上发言人：张三问完李四接着问，不能嫁接上下文
        assertThat(msg.chatKey()).isEqualTo("oc_1:ou_zhangsan");
    }

    @Test
    @DisplayName("★ 同一 message_id 重推 → 不重复处理")
    void duplicateMessageSkipped() throws Exception {
        when(dedup.claim(anyString(), anyString(), anyString())).thenReturn(false);

        String ts = "1700000000";
        String nonce = "n1";
        byte[] body = messageEvent("om_dup", "你好", "user", "p2p", "ou_a")
                .getBytes(StandardCharsets.UTF_8);

        mvc.perform(post("/channel/feishu/event")
                        .contentType("application/json")
                        .header("X-Lark-Request-Timestamp", ts)
                        .header("X-Lark-Request-Nonce", nonce)
                        .header("X-Lark-Signature", sign(ts, nonce, body))
                        .content(body))
                .andExpect(status().isOk());

        verify(queue, never()).offer(any());
    }

    @Test
    @DisplayName("队列满 → 仍然 200（返回 5xx 会触发重推，把队列压得更死）")
    void fullQueueStillReturns200() throws Exception {
        when(queue.offer(any())).thenReturn(false);

        String ts = "1700000000";
        String nonce = "n1";
        byte[] body = messageEvent("om_full", "你好", "user", "p2p", "ou_a")
                .getBytes(StandardCharsets.UTF_8);

        mvc.perform(post("/channel/feishu/event")
                        .contentType("application/json")
                        .header("X-Lark-Request-Timestamp", ts)
                        .header("X-Lark-Request-Nonce", nonce)
                        .header("X-Lark-Signature", sign(ts, nonce, body))
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("群聊 @ 机器人的占位符被清掉，不混进提问")
    void mentionPlaceholderStripped() throws Exception {
        String ts = "1700000000";
        String nonce = "n1";
        byte[] body = messageEvent("om_at", "@_user_1 上周订单金额", "user", "group", "ou_a")
                .getBytes(StandardCharsets.UTF_8);

        mvc.perform(post("/channel/feishu/event")
                        .contentType("application/json")
                        .header("X-Lark-Request-Timestamp", ts)
                        .header("X-Lark-Request-Nonce", nonce)
                        .header("X-Lark-Signature", sign(ts, nonce, body))
                        .content(body))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(InboundMessage.class);
        verify(queue).offer(captor.capture());
        assertThat(captor.getValue().text()).isEqualTo("上周订单金额");
    }
}

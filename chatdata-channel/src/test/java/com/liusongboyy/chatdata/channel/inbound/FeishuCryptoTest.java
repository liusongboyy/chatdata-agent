package com.liusongboyy.chatdata.channel.inbound;

import com.liusongboyy.chatdata.channel.config.FeishuProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 飞书事件的验签与解密。
 *
 * <p>这两个方法是整条入站链路的信任起点：验签错了，任何人都能伪造事件
 * 冒充任意飞书用户问数；解密错了，事件处理不了。
 */
class FeishuCryptoTest {

    private static final String KEY = "test-encrypt-key-32bytes-long!!";

    private FeishuCrypto crypto;

    @BeforeEach
    void setUp() {
        FeishuProperties props = new FeishuProperties();
        props.setEncryptKey(KEY);
        crypto = new FeishuCrypto(props);
    }

    /** 按飞书文档的算法算签名，用来构造正确的测试向量。 */
    private static String sign(String timestamp, String nonce, String key, byte[] body) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update((timestamp + nonce + key).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(sha.digest(body));
    }

    /** 按飞书文档的算法加密，构造「飞书推过来」的请求体。 */
    private static String encrypt(String key, String plain) throws Exception {
        byte[] aesKey = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        byte[] iv = java.util.Arrays.copyOf(
                MessageDigest.getInstance("SHA-256").digest("iv-fixed".getBytes()), 16);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
        byte[] enc = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + enc.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(enc, 0, out, iv.length, enc.length);
        return Base64.getEncoder().encodeToString(out);
    }

    @Test
    @DisplayName("★ 同一个 body 验签两次都通过（证明用的是原始字节）")
    void signatureIsStableAcrossReplays() throws Exception {
        byte[] body = "{\"encrypt\":\"abc\"}".getBytes(StandardCharsets.UTF_8);
        String ts = "1700000000";
        String nonce = "n1";
        String sig = sign(ts, nonce, KEY, body);

        assertThat(crypto.verify(ts, nonce, sig, body)).isTrue();
        // 重放同一条（飞书会重推）必须仍然通过——否则重推全被当成伪造
        assertThat(crypto.verify(ts, nonce, sig, body)).isTrue();
    }

    @Test
    @DisplayName("★ body 里含中文时验签仍通过（UTF-8 字节必须一致）")
    void signatureWorksWithChinese() throws Exception {
        // 用「重新序列化后的字符串」算签名会在这里失败：中文可能被转成 \\uXXXX
        byte[] body = "{\"question\":\"上周华东订单金额\"}".getBytes(StandardCharsets.UTF_8);
        String sig = sign("1700000000", "n2", KEY, body);

        assertThat(crypto.verify("1700000000", "n2", sig, body)).isTrue();
    }

    @Test
    @DisplayName("★ body 被改动一个字节 → 验签失败")
    void tamperedBodyFails() throws Exception {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        String sig = sign("1700000000", "n3", KEY, body);
        byte[] tampered = "{\"a\":2}".getBytes(StandardCharsets.UTF_8);

        assertThat(crypto.verify("1700000000", "n3", sig, tampered)).isFalse();
    }

    @Test
    @DisplayName("时间戳或 nonce 变了 → 验签失败（防重放）")
    void changedTimestampFails() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String sig = sign("1700000000", "n4", KEY, body);

        assertThat(crypto.verify("1700000001", "n4", sig, body)).isFalse();
        assertThat(crypto.verify("1700000000", "other", sig, body)).isFalse();
    }

    @Test
    @DisplayName("没配 encrypt-key → 一律验签失败，不静默放行")
    void missingKeyRejectsEverything() {
        FeishuProperties noKey = new FeishuProperties();
        FeishuCrypto c = new FeishuCrypto(noKey);

        assertThat(c.verify("1", "n", "whatever", "{}".getBytes())).isFalse();
    }

    @Test
    @DisplayName("解密：加解密往返正确")
    void decryptRoundTrip() throws Exception {
        String plain = "{\"challenge\":\"abc123\"}";

        byte[] decoded = crypto.decrypt(encrypt(KEY, plain));

        assertThat(new String(decoded, StandardCharsets.UTF_8)).isEqualTo(plain);
    }

    @Test
    @DisplayName("★ 解密：用错密钥 → 抛异常，不返回 null")
    void decryptWithWrongKeyThrows() throws Exception {
        String cipherText = encrypt("another-key", "{\"a\":1}");

        // 返回 null 会让调用方当成"空事件"静默丢弃，而真实原因是密钥配错了
        assertThatThrownBy(() -> crypto.decrypt(cipherText))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解密失败");
    }

    @Test
    @DisplayName("解密：密文长度不足 → 抛异常")
    void shortCipherTextThrows() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[8]);

        assertThatThrownBy(() -> crypto.decrypt(tooShort))
                .isInstanceOf(IllegalStateException.class);
    }
}

package com.liusongboyy.chatdata.channel.inbound;

import com.liusongboyy.chatdata.channel.config.FeishuProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 飞书事件的安全校验与解密。
 *
 * <h2>签名必须用<b>原始 body 字节</b></h2>
 *
 * <p>签名算法是 {@code sha256(timestamp + nonce + encryptKey + rawBody)}，
 * 其中 {@code rawBody} 是<b>请求体的原始字节</b>。
 *
 * <p>这里曾经最容易踩的坑：Spring 的 {@code @RequestBody} 已经把 body
 * 反序列化成对象，再 {@code toString()} 得到的字节与原文不同（空格、键序、
 * Unicode 转义都会被改写），签名为假失败。所以控制器必须先把
 * {@code InputStream} 读成 {@code byte[]} 缓存下来，签名校验与解密都用这份字节。
 *
 * <p>加解密都是无状态的，做成工具类方法，调用方传字节进来。
 */
@Component
public class FeishuCrypto {

    private static final Logger log = LoggerFactory.getLogger(FeishuCrypto.class);

    /** AES-256-CBC 的 IV 长度。飞书固定 16 字节。 */
    private static final int IV_BYTES = 16;

    private final FeishuProperties properties;

    public FeishuCrypto(FeishuProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验签名。
     *
     * @param timestamp {@code X-Lark-Request-Timestamp}
     * @param nonce     {@code X-Lark-Request-Nonce}
     * @param signature {@code X-Lark-Signature}
     * @param rawBody   <b>原始</b>请求体字节
     * @return 是否合法。调用方应把 {@code false} 当成 401，且不处理事件
     */
    public boolean verify(String timestamp, String nonce, String signature, byte[] rawBody) {
        String key = properties.getEncryptKey();
        if (key == null || key.isBlank()) {
            // 没配密钥就不可能正确验签。这里返回 false 而不是 true——
            // 放行等于承认任何来源都能伪造事件
            log.error("未配置 channel.feishu.encrypt-key，无法验签");
            return false;
        }
        if (timestamp == null || nonce == null || signature == null) {
            return false;
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update((timestamp + nonce + key).getBytes(StandardCharsets.UTF_8));
            byte[] expected = sha256.digest(rawBody);
            String actual = HexFormat.of().formatHex(expected);
            // 大小写不敏感比较：飞书文档的示例是 hex 小写，但不保证
            return actual.equalsIgnoreCase(signature);
        } catch (Exception e) {
            log.error("验签失败", e);
            return false;
        }
    }

    /**
     * 解密事件体。
     *
     * <p>算法：{@code key = sha256(encryptKey)}，密文为
     * {@code base64(iv(16 字节) || AES-256-CBC(明文))}，PKCS7 填充。
     *
     * @param encrypt 请求体里的 {@code encrypt} 字段
     * @return 明文字节。密钥缺失或密文损坏时抛异常——不返回 null，
     *         那会让调用方把 null 当成"空事件"而静默丢弃
     */
    public byte[] decrypt(String encrypt) {
        String key = properties.getEncryptKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("未配置 channel.feishu.encrypt-key");
        }
        try {
            byte[] raw = Base64.getDecoder().decode(encrypt);
            if (raw.length <= IV_BYTES) {
                throw new IllegalArgumentException("密文长度不足");
            }
            byte[] aesKey = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            IvParameterSpec iv = new IvParameterSpec(raw, 0, IV_BYTES);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), iv);
            return cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
        } catch (Exception e) {
            throw new IllegalStateException("飞书事件解密失败：密文损坏或 encrypt-key 与后台不一致", e);
        }
    }
}

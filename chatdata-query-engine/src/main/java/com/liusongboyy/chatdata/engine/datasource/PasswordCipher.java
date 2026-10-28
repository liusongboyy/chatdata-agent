package com.liusongboyy.chatdata.engine.datasource;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 数据源密码的加解密。
 *
 * <h2>为什么用 AES-GCM 而不是厂商那套 DES</h2>
 *
 * <p>GCM 是**带认证**的加密：密文被篡改会解密失败，而不是解出一段垃圾再拿去连库。
 * 厂商用的是 DES + 固定 IV，密钥还硬编码在字节码里——那等于没加密，
 * 只是让密码不能直接读出来。
 *
 * <h2>密钥必须有，没有默认值</h2>
 *
 * <p>与 {@code LoginTokenService} 同样的取舍：共用默认密钥等于任何人都能解密。
 * 密钥缺失时**构造失败**，让应用起不来，而不是用个默认值悄悄降级。
 *
 * <h2>格式</h2>
 *
 * <p>{@code base64(IV(12 字节) || 密文 || GCM tag)}。IV 每次随机——
 * 固定 IV 在 GCM 下是致命的（会泄露明文异或值），不是风格问题。
 */
public final class PasswordCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param secret 密钥材料。任意长度字符串，内部经 SHA-256 派生成 256 位密钥
     */
    public PasswordCipher(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "缺少 chatdata.engine.datasource.secret。请通过环境变量提供——"
                            + "该密钥不设默认值，共用默认密钥等于任何人都能解密数据源密码。");
        }
        this.key = new SecretKeySpec(sha256(secret), "AES");
    }

    /** 加密。每次调用产生不同密文（IV 随机）。 */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher c = Cipher.getInstance(TRANSFORMATION);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("加密数据源密码失败", e);
        }
    }

    /**
     * 解密。
     *
     * <p>密文被篡改或密钥不对时抛异常——**不返回 null 或空串**。
     * 返回 null 会让调用方拿它去连库，报出的是"认证失败"，
     * 而真实原因是密钥配错了，错因离现场很远。
     */
    public String decrypt(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            if (all.length <= IV_BYTES) {
                throw new IllegalArgumentException("密文长度不足");
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);

            Cipher c = Cipher.getInstance(TRANSFORMATION);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = c.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "解密数据源密码失败：密文被篡改，或 chatdata.engine.datasource.secret 与加密时不一致", e);
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

package com.liusongboyy.chatdata.engine.datasource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据源密码加解密的测试。
 *
 * <p>重点是**失败必须显式**：密钥缺失要起不来，密文被篡改要抛异常，
 * 而不是解出垃圾或返回 null 让调用方拿它去连库。
 */
class PasswordCipherTest {

    private static final String SECRET = "test-secret-for-unit-test-only";

    @Test
    @DisplayName("加解密往返")
    void roundTrip() {
        PasswordCipher c = new PasswordCipher(SECRET);

        String plain = "p@ssw0rd!中文密码";
        assertThat(c.decrypt(c.encrypt(plain))).isEqualTo(plain);
    }

    @Test
    @DisplayName("★ 同一明文两次加密结果不同 —— IV 随机，固定 IV 在 GCM 下是致命的")
    void encryptionIsNonDeterministic() {
        PasswordCipher c = new PasswordCipher(SECRET);

        String a = c.encrypt("same-password");
        String b = c.encrypt("same-password");

        assertThat(a)
                .as("固定 IV 会泄露明文异或值，这不是风格问题是安全问题")
                .isNotEqualTo(b);
        // 但都能解回原文
        assertThat(c.decrypt(a)).isEqualTo("same-password");
        assertThat(c.decrypt(b)).isEqualTo("same-password");
    }

    @Test
    @DisplayName("★ 密文被篡改时抛异常，而不是解出垃圾")
    void tamperedCiphertextThrows() {
        PasswordCipher c = new PasswordCipher(SECRET);
        String enc = c.encrypt("secret");

        // 翻转密文中的一个字符
        char[] chars = enc.toCharArray();
        chars[chars.length - 2] = chars[chars.length - 2] == 'A' ? 'B' : 'A';
        String tampered = new String(chars);

        assertThatThrownBy(() -> c.decrypt(tampered))
                .as("GCM 带认证，篡改必须被检出。返回 null 会让调用方拿它去连库，"
                        + "报出的是'认证失败'而真实原因是密文被改过")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("★ 密钥不同则解密失败 —— 不返回 null")
    void wrongKeyThrows() {
        String enc = new PasswordCipher("secret-A").encrypt("pwd");

        assertThatThrownBy(() -> new PasswordCipher("secret-B").decrypt(enc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secret");
    }

    @Test
    @DisplayName("★ 密钥缺失时构造失败 —— 不设默认值")
    void missingSecretFailsFast() {
        assertThatThrownBy(() -> new PasswordCipher(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chatdata.engine.datasource.secret");
        assertThatThrownBy(() -> new PasswordCipher("  "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("空密码往返为空，不抛异常")
    void nullAndBlankHandled() {
        PasswordCipher c = new PasswordCipher(SECRET);

        assertThat(c.encrypt(null)).isNull();
        assertThat(c.decrypt(null)).isNull();
        assertThat(c.decrypt("")).isNull();
        assertThat(c.decrypt(c.encrypt(""))).isEmpty();
    }
}

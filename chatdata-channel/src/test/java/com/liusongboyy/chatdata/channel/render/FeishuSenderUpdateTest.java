package com.liusongboyy.chatdata.channel.render;

import com.liusongboyy.chatdata.channel.config.FeishuProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 卡片原地更新。
 *
 * <p>测的是<b>失败路径</b>：更新不可用时必须返回 false 让调用方回退成
 * "再发一条"，而不是静默什么都不做——后者会让占位卡片永远停在"正在查询"。
 */
class FeishuSenderUpdateTest {

    /** 未启用时直接返回 false，不抛异常。 */
    private static FeishuSender disabled() {
        FeishuProperties p = new FeishuProperties();
        p.setEnabled(false);
        return new FeishuSender(p);
    }

    @Test
    @DisplayName("★ 未启用时返回 false，调用方据此回退")
    void disabledReturnsFalse() {
        assertThat(disabled().updateCard("om_xxx", "{}")).isFalse();
    }

    @Test
    @DisplayName("★ 空 messageId 返回 false，不发无意义请求")
    void blankMessageIdReturnsFalse() {
        assertThat(disabled().updateCard(null, "{}")).isFalse();
        assertThat(disabled().updateCard("", "{}")).isFalse();
        assertThat(disabled().updateCard("  ", "{}")).isFalse();
    }

    @Test
    @DisplayName("★ 更新失败不能抛异常——抛了会把已经拿到的答案丢掉")
    void neverThrows() {
        // 如果 updateCard 抛异常，MessageHandler 里那个 catch 会把整张卡片
        // 换成错误卡片，用户看不到已经查到的数据
        org.assertj.core.api.Assertions
                .assertThatCode(() -> disabled().updateCard("om_xxx", "not json"))
                .doesNotThrowAnyException();
    }
}

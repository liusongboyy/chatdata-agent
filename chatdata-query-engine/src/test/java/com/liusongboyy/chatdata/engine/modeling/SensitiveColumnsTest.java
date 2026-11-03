package com.liusongboyy.chatdata.engine.modeling;

import com.liusongboyy.chatdata.engine.datasource.MetaReader.ColumnMeta;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 敏感列识别。
 *
 * <p>这个类测的是<b>失败方向</b>：漏判 = 数据泄露，所以每条都要断言。
 */
class SensitiveColumnsTest {

    @Nested
    @DisplayName("应该命中")
    class ShouldMatch {

        @Test
        @DisplayName("★ 英文敏感列：证件、联系方式、凭据、金融")
        void englishSensitive() {
            assertThat(SensitiveColumns.match("idcard")).isEqualTo("idcard");
            assertThat(SensitiveColumns.match("id_card")).isEqualTo("id_card");
            assertThat(SensitiveColumns.match("phone")).isEqualTo("phone");
            assertThat(SensitiveColumns.match("mobile")).isEqualTo("mobile");
            assertThat(SensitiveColumns.match("email")).isEqualTo("email");
            assertThat(SensitiveColumns.match("password")).isEqualTo("password");
            assertThat(SensitiveColumns.match("bank_account")).isNotNull();
        }

        @Test
        @DisplayName("★ 中文敏感列")
        void chineseSensitive() {
            assertThat(SensitiveColumns.match("身份证号")).isEqualTo("身份证");
            assertThat(SensitiveColumns.match("客户手机")).isEqualTo("手机");
            assertThat(SensitiveColumns.match("联系邮箱")).isEqualTo("邮箱");
        }

        @Test
        @DisplayName("驼峰与下划线都能识别")
        void namingVariants() {
            assertThat(SensitiveColumns.match("CustomerPhone")).isNotNull();
            assertThat(SensitiveColumns.match("customer_phone")).isNotNull();
            assertThat(SensitiveColumns.match("CUSTOMER_PHONE")).isNotNull();
        }

        @Test
        @DisplayName("复合词：email_verified 该命中")
        void compoundWord() {
            assertThat(SensitiveColumns.match("email_verified")).isNotNull();
            assertThat(SensitiveColumns.match("phone_number")).isNotNull();
        }
    }

    @Nested
    @DisplayName("不该命中")
    class ShouldNotMatch {

        @Test
        @DisplayName("★ 普通业务列不误伤")
        void normalBusinessColumns() {
            assertThat(SensitiveColumns.match("order_id")).isNull();
            assertThat(SensitiveColumns.match("amount")).isNull();
            assertThat(SensitiveColumns.match("store_name")).isNull();
            assertThat(SensitiveColumns.match("created_at")).isNull();
        }

        @Test
        @DisplayName("★ hotel 不能被 tel 误伤")
        void telDoesNotMatchHotel() {
            // 用 contains 的话 hotel 会被 tel 击中。这正是改成整词比对的原因
            assertThat(SensitiveColumns.match("hotel")).isNull();
            assertThat(SensitiveColumns.match("hotel_name")).isNull();
        }

        @Test
        @DisplayName("空值不崩")
        void nullAndBlank() {
            assertThat(SensitiveColumns.match(null)).isNull();
            assertThat(SensitiveColumns.match("")).isNull();
            assertThat(SensitiveColumns.match("   ")).isNull();
        }
    }

    @Test
    @DisplayName("reason 要说清为什么关，管理员才能判断")
    void reasonExplains() {
        String reason = SensitiveColumns.reason("customer_phone");
        assertThat(reason).contains("phone").contains("默认关闭");
    }

    @Test
    @DisplayName("普通列没有 reason")
    void normalColumnHasNoReason() {
        assertThat(SensitiveColumns.reason("order_amount")).isNull();
    }
}

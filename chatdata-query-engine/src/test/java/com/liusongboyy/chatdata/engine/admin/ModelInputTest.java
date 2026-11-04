package com.liusongboyy.chatdata.engine.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型入参的校验。
 *
 * <p>这里守的几条，共同点是<b>违反了不会报错、只会算出错的数</b>。
 * 单价配一半、角色列表里塞空串、键里带空格——没有一条会让程序崩溃，
 * 每一条都会让某个数字或某个选项悄悄不对。
 */
class ModelInputTest {

    private static ModelInput input(String key, BigDecimal in, BigDecimal cached, BigDecimal out) {
        return new ModelInput(key, "显示名", "说明", "https://api.example.com/v1", "sk-x",
                "model-1", List.of(), in, cached, out, false, true);
    }

    // ── 模型键 ──

    @Test
    @DisplayName("★ 键的字符集受限——它会进审计表、URL 和 SQL 查询")
    void keyCharsetIsRestricted() {
        // 允许的几种
        for (String ok : List.of("flash", "gpt-4o", "deepseek_v4", "a1")) {
            assertThat(input(ok, null, null, null).validated(true).key()).isEqualTo(ok);
        }
        // 不允许的：空格、中文、大写、以符号开头
        for (String bad : List.of("my model", "快速模型", "Flash", "-lead", "a".repeat(33))) {
            assertThatThrownBy(() -> input(bad, null, null, null).validated(true))
                    .as("键「%s」应被拒", bad)
                    .isInstanceOf(AdminException.class);
        }
    }

    @Test
    @DisplayName("更新时不校验键——它从路径来，不在入参里")
    void updateSkipsKeyCheck() {
        // 编辑时前端会把 key 一起传回来（回显用），但服务端以路径为准。
        // 若这里也校验，一个合法的更新会因为"入参里那个怪值"而失败
        assertThat(input("带 空格", null, null, null).validated(false)).isNotNull();
    }

    // ── 单价 ──

    @Test
    @DisplayName("★ 全不配是合法的——自建模型没有单价这个概念")
    void allPricesEmptyIsValid() {
        ModelInput v = input("free", null, null, null).validated(true);

        assertThat(v.priceInput()).isNull();
        assertThat(v.priceOutput()).isNull();
    }

    @Test
    @DisplayName("★ 配一半要拒——它会算得出钱，但算错，且不报错")
    void halfConfiguredPricesRejected() {
        assertThatThrownBy(() -> input("x", new BigDecimal("1"), null, null).validated(true))
                .isInstanceOf(AdminException.class)
                .hasMessageContaining("全配");
        assertThatThrownBy(() -> input("x", null, new BigDecimal("0.1"), new BigDecimal("2"))
                .validated(true))
                .isInstanceOf(AdminException.class)
                .hasMessageContaining("全配");
    }

    @Test
    @DisplayName("★ 0 元要拒——0 元是「免费」，而它其实是「不知道」")
    void zeroPriceRejected() {
        assertThatThrownBy(() -> input("x", BigDecimal.ZERO, null, new BigDecimal("2"))
                .validated(true))
                .isInstanceOf(AdminException.class)
                .hasMessageContaining("大于 0");
        assertThatThrownBy(() -> input("x", new BigDecimal("1"), BigDecimal.ZERO,
                new BigDecimal("2")).validated(true))
                .isInstanceOf(AdminException.class);
    }

    @Test
    @DisplayName("只配输入与输出、不配缓存价 → 合法（缓存按全价算，宁可高估）")
    void cachedPriceOptional() {
        ModelInput v = input("x", new BigDecimal("2"), null, new BigDecimal("8")).validated(true);

        assertThat(v.priceCached()).isNull();
        assertThat(v.priceInput()).as("配了就是配了，不因为缓存价缺失而清空").isNotNull();
    }

    // ── 地址与必填 ──

    @Test
    @DisplayName("地址必须以 http(s):// 开头")
    void baseUrlMustBeHttp() {
        var bad = new ModelInput("x", "名", null, "api.example.com/v1", "k", "m",
                List.of(), null, null, null, false, true);

        assertThatThrownBy(() -> bad.validated(true))
                .isInstanceOf(AdminException.class)
                .hasMessageContaining("http");
    }

    @Test
    @DisplayName("创建时键、显示名、地址、模型名都必填")
    void requiredFieldsOnCreate() {
        for (String blank : new String[]{null, "", "  "}) {
            assertThatThrownBy(() -> new ModelInput(blank, "名", null, "https://x/v1", null,
                    "m", List.of(), null, null, null, false, true).validated(true))
                    .isInstanceOf(AdminException.class);
        }
    }

    // ── 角色 ──

    @Test
    @DisplayName("★ 角色列表里的空串要被去掉——空串会在白名单里变成永不匹配的项")
    void blankRolesDropped() {
        var v = new ModelInput("x", "名", null, "https://x/v1", "k", "m",
                java.util.Arrays.asList("admin", " ", "", null, "admin"),
                null, null, null, false, true).validated(true);

        assertThat(v.allowedRoles()).containsExactly("admin");
    }

    @Test
    @DisplayName("★ 空角色列表保持空 = 不限制，不被填成占位值")
    void emptyRolesStayEmpty() {
        ModelInput v = new ModelInput("x", "名", null, "https://x/v1", "k", "m",
                List.of(), null, null, null, false, true).validated(true);

        assertThat(v.allowedRoles()).isEmpty();
    }

    // ── 密钥 ──

    @Test
    @DisplayName("密钥空串归一成 null = 「不改」，与「清空」区分开")
    void emptyApiKeyMeansUnchanged() {
        var empty = new ModelInput("x", "名", null, "https://x/v1", "", "m",
                List.of(), null, null, null, false, true).validated(true);
        assertThat(empty.apiKey()).isNull();

        var nullKey = new ModelInput("x", "名", null, "https://x/v1", null, "m",
                List.of(), null, null, null, false, true).validated(true);
        assertThat(nullKey.apiKey()).isNull();
    }

    @Test
    @DisplayName("★ 密钥不 trim——有些供应商的密钥里确实带空白")
    void apiKeyNotTrimmed() {
        var v = new ModelInput("x", "名", null, "https://x/v1", " sk-with-space ", "m",
                List.of(), null, null, null, false, true).validated(true);

        assertThat(v.apiKey()).as("trim 掉连不上，而报错会是 401——查不到根因")
                .isEqualTo(" sk-with-space ");
    }
}

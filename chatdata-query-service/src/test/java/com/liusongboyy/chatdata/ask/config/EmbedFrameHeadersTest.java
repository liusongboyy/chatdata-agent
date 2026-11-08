package com.liusongboyy.chatdata.ask.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 嵌入页的 frame 白名单。
 *
 * <h2>这是安全头，错了就是钓鱼入口</h2>
 *
 * <p>问数页面一旦允许被 iframe 嵌入，任何网站都能嵌——包括钓鱼站。
 * 攻击者做一个像银行登录页的页面，中间嵌一个真的问数框，
 * 用户以为在跟钓鱼站交互，而问题真的进了我们的系统。
 *
 * <p>所以两条必须守住：
 *
 * <ol>
 *   <li><b>只对 /app/embed 放开</b>——主问数页与管理台一律禁止</li>
 *   <li><b>没配白名单 = 谁都不能嵌</b>——失败方向必须安全</li>
 * </ol>
 */
class EmbedFrameHeadersTest {

    private static MockHttpServletResponse run(EmbedFrameHeaders filter, String uri)
            throws Exception {
        var req = new MockHttpServletRequest("GET", uri);
        var res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    @DisplayName("★ 没配白名单 → 嵌入页也是 'none'（不能因为没配就放开）")
    void emptyAllowlistMeansNone() throws Exception {
        var filter = new EmbedFrameHeaders("");

        var res = run(filter, "/app/embed");

        assertThat(res.getHeader("Content-Security-Policy"))
                .as("配置疏漏的失败方向是灾难性的：任何网站都能嵌")
                .isEqualTo("frame-ancestors 'none'");
    }

    @Test
    @DisplayName("★ 配了白名单 → 嵌入页按白名单放开")
    void allowlistIsApplied() throws Exception {
        var filter = new EmbedFrameHeaders("https://crm.example.com,https://oa.example.com");

        var res = run(filter, "/app/embed");

        assertThat(res.getHeader("Content-Security-Policy"))
                .isEqualTo("frame-ancestors https://crm.example.com https://oa.example.com");
    }

    @Test
    @DisplayName("★ 主问数页一律禁止被嵌——它没有理由出现在 iframe 里")
    void mainAppIsNeverFramable() throws Exception {
        var filter = new EmbedFrameHeaders("https://crm.example.com");

        for (String path : new String[]{"/app/ask", "/app/admin/models", "/app/login"}) {
            var res = run(filter, path);
            assertThat(res.getHeader("Content-Security-Policy"))
                    .as("路径 %s 不该被嵌", path)
                    .isEqualTo("frame-ancestors 'none'");
            assertThat(res.getHeader("X-Frame-Options")).isEqualTo("DENY");
        }
    }

    @Test
    @DisplayName("★ 嵌入页**不设** X-Frame-Options——设了会覆盖 frame-ancestors")
    void embedPathHasNoXFrameOptions() throws Exception {
        var filter = new EmbedFrameHeaders("https://crm.example.com");

        var res = run(filter, "/app/embed");

        // X-Frame-Options 只认 DENY / SAMEORIGIN（ALLOW-FROM 已废弃）。
        // 设了 DENY 会让白名单形同虚设，表现为「配了但就是嵌不进去」
        assertThat(res.getHeader("X-Frame-Options")).isNull();
    }

    @Test
    @DisplayName("空白项被忽略，不会产生空 token 让 CSP 失效")
    void blankEntriesIgnored() throws Exception {
        var filter = new EmbedFrameHeaders(" https://crm.example.com , , https://oa.example.com ");

        var res = run(filter, "/app/embed");

        assertThat(res.getHeader("Content-Security-Policy"))
                .isEqualTo("frame-ancestors https://crm.example.com https://oa.example.com");
    }
}

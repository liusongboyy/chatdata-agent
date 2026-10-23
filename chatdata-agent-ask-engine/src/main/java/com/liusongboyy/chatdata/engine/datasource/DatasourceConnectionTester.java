package com.liusongboyy.chatdata.engine.datasource;

import com.liusongboyy.chatdata.engine.datasource.dialect.SqlDialects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * 数据源连接测试。
 *
 * <h2>这个类是为修三个具体的缺陷而写的</h2>
 *
 * <p>厂商那条链路实测有三个问题，本项目逐个修掉：
 *
 * <table>
 *   <tr><th>缺陷</th><th>厂商行为</th><th>本项目</th></tr>
 *   <tr>
 *     <td>驱动缺失的提示</td>
 *     <td>报一行裸类名 {@code 连接失败：org.postgresql.Driver}，
 *         用户完全看不出是"缺驱动"</td>
 *     <td>前置校验 + 明确说"未安装该数据库驱动"</td>
 *   </tr>
 *   <tr>
 *     <td>没有超时</td>
 *     <td>没有 {@code setLoginTimeout}，打黑洞地址实测 30 秒才返回，
 *         期间占着一个请求线程</td>
 *     <td>显式设登录超时</td>
 *   </tr>
 *   <tr>
 *     <td>方言不受支持</td>
 *     <td>下拉列出 14 种数据库，但 jar 内只有 MySQL 驱动</td>
 *     <td>先查方言注册表，配了渲染不了的库立刻说清楚</td>
 *   </tr>
 * </table>
 */
public final class DatasourceConnectionTester {

    private static final Logger log = LoggerFactory.getLogger(DatasourceConnectionTester.class);

    /** 登录超时。用户等在页面前，卡住比失败更糟。 */
    private static final int LOGIN_TIMEOUT_SECONDS = 10;

    private DatasourceConnectionTester() {
    }

    /**
     * 测试结果。
     *
     * @param ok      是否连通
     * @param message 给人看的结果。成功时是简短说明，失败时**必须能指导怎么改**
     */
    public record Probe(boolean ok, String message) {

        static Probe success() {
            return new Probe(true, "连接成功");
        }

        static Probe fail(String message) {
            return new Probe(false, message);
        }
    }

    /**
     * 用给定参数试连。
     *
     * <p>**不抛异常**——连接失败是这类接口的正常结果，不是程序错误。
     * 抛异常会让调用方把它当成 bug 处理，而不是当成一条要展示给用户的提示。
     */
    public static Probe test(String dbType, String driverClass, String url,
                             String username, String password) {

        // ① 方言先查。配了一个渲染不了的库，测通了也没用——
        //    与其让用户在问数时才撞墙，不如在配置这一步就说清楚
        if (!SqlDialects.supports(dbType)) {
            return Probe.fail("暂不支持的数据源类型「%s」。已支持：%s"
                    .formatted(dbType, SqlDialects.supportedTypes()));
        }

        if (url == null || url.isBlank()) {
            return Probe.fail("请填写 JDBC URL");
        }

        // ② 驱动前置校验。
        //    **这一步是关键**：不先查的话，ClassNotFoundException 的消息就是那个类名本身，
        //    而"连接失败：org.postgresql.Driver"这种提示等于没说
        if (driverClass != null && !driverClass.isBlank()) {
            try {
                Class.forName(driverClass.trim());
            } catch (ClassNotFoundException e) {
                return Probe.fail(
                        "未安装该数据库驱动（%s）。请把对应的 JDBC 驱动加入依赖后重启服务。"
                                .formatted(driverClass.trim()));
            }
        }

        // ③ 连接。设登录超时——见类注释里的第二个缺陷
        int previousTimeout = DriverManager.getLoginTimeout();
        try {
            DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
            try (Connection ignored = DriverManager.getConnection(url, username, password)) {
                return Probe.success();
            }
        } catch (SQLException e) {
            return Probe.fail(explain(e, LOGIN_TIMEOUT_SECONDS));
        } catch (Exception e) {
            return Probe.fail("连接失败：" + e.getMessage());
        } finally {
            // 恢复全局状态。DriverManager 的登录超时是**静态的**，
            // 不改回去会影响应用里其它地方的连接行为
            DriverManager.setLoginTimeout(previousTimeout);
        }
    }

    /**
     * 把 JDBC 的报错翻成能指导行动的说明。
     *
     * <p>只翻**最常见且原始信息容易误导**的几类。其余的原文透出——
     * 数据库自己的报错通常比我们的猜测更准确，硬翻反而会掩盖真实原因。
     */
    private static String explain(SQLException e, int timeoutSeconds) {
        String raw = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String lower = raw.toLowerCase();

        if (lower.contains("access denied")) {
            // 这条最常见也最容易被误判成"密码错"——实际上常常是**来源 IP 没授权**，
            // 而用户在本地客户端连得上，所以坚信密码没错
            return "连接失败：账号或密码不正确，或该账号不允许从本服务所在机器连接。\n"
                    + "（你在本地客户端能连上不代表这里能——MySQL 的授权是分来源主机的）\n"
                    + "原始信息：" + raw;
        }
        if (lower.contains("communications link failure") || lower.contains("connection refused")) {
            return "连接失败：网络不通，或目标端口被防火墙拦截。\n原始信息：" + raw;
        }
        if (lower.contains("unknown database")) {
            return "连接失败：数据库不存在。\n原始信息：" + raw;
        }
        if (lower.contains("public key retrieval")) {
            // MySQL 8 的 caching_sha2_password 在未启用 SSL 时要求显式允许取公钥。
            // 报错看起来与密码无关，实际就是它——URL 里加 allowPublicKeyRetrieval=true 即可
            return "连接失败：MySQL 8 需要允许获取公钥。请在 JDBC URL 中加上 "
                    + "allowPublicKeyRetrieval=true（若同时用 useSSL=false）。\n原始信息：" + raw;
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "连接失败：%d 秒内没有响应，已放弃。请确认目标地址可达。\n原始信息：%s"
                    .formatted(timeoutSeconds, raw);
        }
        return "连接失败：" + raw;
    }

    /** 记录一次测试（供审计与排查）。不记录密码。 */
    public static void logProbe(String dbType, String url, Probe probe) {
        if (probe.ok()) {
            log.info("数据源连接测试成功：type={} url={}", dbType, url);
        } else {
            log.warn("数据源连接测试失败：type={} url={} 原因={}", dbType, url, probe.message());
        }
    }
}

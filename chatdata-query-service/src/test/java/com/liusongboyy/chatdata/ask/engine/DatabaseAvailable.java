package com.liusongboyy.chatdata.ask.engine;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.sql.Connection;
import java.sql.DriverManager;

/**
 * 本地有 MySQL 才跑这个测试类；没有就整类跳过，**不是失败**。
 *
 * <h2>为什么不能用 {@code Assumptions}</h2>
 *
 * <p>{@code Assumptions.assumeTrue} 写在方法体里，只能拦"方法开始执行之后"。
 * 而 {@code @SpringBootTest} 的上下文是在**类加载阶段**建的——库连不上时，
 * 上下文构建就抛异常，测试还没跑到方法体就已经红了。
 *
 * <p>表现：本地有库时全绿，CI 上必红。开源项目最不该有的体验——
 * 别人 clone 下来一看是红的，以为代码有问题。
 *
 * <h2>判据</h2>
 *
 * <p>用测试里那套固定的连接参数探一次。连不上就跳过，并打印原因，
 * 让人知道"不是没跑，是环境不具备"。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(DatabaseAvailable.Condition.class)
public @interface DatabaseAvailable {

    /** 数据库地址。与各 *RealDbTest 用的那套保持一致，可用系统属性覆盖。 */
    String URL = "jdbc:mysql://127.0.0.1:3306/chatdata_ask"
            + "?useUnicode=true&characterEncoding=UTF-8&useSSL=false"
            + "&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";

    class Condition implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            // 允许用**环境变量**覆盖（系统属性在 jacoco 覆盖 argLine 后传不进来）
            String url = env("CHATDATA_TEST_JDBC_URL", URL);
            String user = env("CHATDATA_TEST_JDBC_USER", "root");
            String password = env("CHATDATA_TEST_JDBC_PASSWORD", "root");
            try (Connection ignored = DriverManager.getConnection(url, user, password)) {
                return ConditionEvaluationResult.enabled("本地 MySQL 可用");
            } catch (Exception e) {
                return ConditionEvaluationResult.disabled(
                        "跳过：本地没有可用的 MySQL（" + e.getClass().getSimpleName()
                                + "）。这类测试验的是 Spring 上下文装配，需要真实数据库；"
                                + "docker compose up -d 后即可运行");
            }
        }

        private static String env(String key, String fallback) {
            String v = System.getenv(key);
            return (v == null || v.isBlank()) ? fallback : v;
        }
    }
}

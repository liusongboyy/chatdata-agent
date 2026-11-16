package com.liusongboyy.chatdata.engine.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型故障分类。
 *
 * <h2>它挡的是「用户看到误导性提示」</h2>
 *
 * <p>实测踩到：火山方舟订阅过期，所有问数都失败，而用户看到的是
 * 「处理失败，请稍后重试」——那句话让人以为是我们的系统坏了。
 * 排查时先去看应用日志、数据库、权限，花了不少时间才翻到真正的
 * {@code InvalidSubscription}。
 *
 * <h2>分类错了两个方向都糟</h2>
 *
 * <ul>
 *   <li><b>该分的没分</b>：用户继续被误导（就是上面那个场景）</li>
 *   <li><b>不该分的分了</b>：正常的业务失败被说成"供应商挂了"，
 *       用户去找管理员，而管理员查半天发现是我们自己的 bug</li>
 * </ul>
 *
 * <p>所以认不出来时必须返回空——**不要猜**。
 */
class ModelFailureClassifierTest {

    private static RuntimeException err(String message) {
        return new RuntimeException(message);
    }

    @Nested
    @DisplayName("不可重试：配置问题")
    class Fatal {

        @Test
        @DisplayName("★ 订阅过期 —— 实测踩到的那个")
        void subscriptionExpired() {
            // 真实错误消息（火山方舟）
            String real = "HTTP transport error during streaming: HTTP request failed "
                    + "with status 400 | {\"error\":{\"code\":\"InvalidSubscription\","
                    + "\"message\":\"Your account (2106368688) does not have a valid "
                    + "CodingPlan subscription, or your subscription has expired.\"}}";

            var got = ModelFailureClassifier.classify(err(real));

            assertThat(got).isPresent();
            assertThat(got.get().retryable())
                    .as("续订阅之前重试一万次也不会成功")
                    .isFalse();
            assertThat(got.get().getMessage())
                    .as("要能指导行动，而不是一句「不可用」")
                    .contains("续订");
        }

        @Test
        @DisplayName("★ 密钥无效")
        void invalidApiKey() {
            var got = ModelFailureClassifier.classify(
                    err("401 Unauthorized: Incorrect API key provided"));

            assertThat(got).isPresent();
            assertThat(got.get().retryable()).isFalse();
            assertThat(got.get().getMessage()).contains("密钥");
        }

        @Test
        @DisplayName("★ 模型不存在")
        void modelNotFound() {
            var got = ModelFailureClassifier.classify(
                    err("404 model_not_found: The model `gpt-9` does not exist"));

            assertThat(got).isPresent();
            assertThat(got.get().retryable()).isFalse();
            assertThat(got.get().getMessage()).contains("模型不存在");
        }

        @Test
        @DisplayName("★ 余额不足")
        void insufficientBalance() {
            var got = ModelFailureClassifier.classify(
                    err("insufficient_quota: You exceeded your current quota"));

            assertThat(got).isPresent();
            assertThat(got.get().retryable()).isFalse();
            assertThat(got.get().getMessage()).contains("续订");
        }

        @Test
        @DisplayName("★ 同时含 500 与订阅错误 → 判成不可重试")
        void fatalWinsOverRetryable() {
            // 方舟的错误体里可能同时出现状态码与业务码。
            // 先判不可重试是刻意的——那种情况显然是配置问题
            var got = ModelFailureClassifier.classify(
                    err("500 server error | InvalidSubscription: subscription has expired"));

            assertThat(got).isPresent();
            assertThat(got.get().retryable())
                    .as("含 500 但真实原因是订阅过期，不该让用户白等")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("可重试：临时故障")
    class Retryable {

        @Test
        @DisplayName("★ 限流")
        void rateLimited() {
            var got = ModelFailureClassifier.classify(err("429 Too Many Requests"));

            assertThat(got).isPresent();
            assertThat(got.get().retryable()).isTrue();
            assertThat(got.get().getMessage()).contains("稍后重试");
        }

        @Test
        @DisplayName("★ 供应商 5xx")
        void serverError() {
            var got = ModelFailureClassifier.classify(err("503 Service Unavailable"));

            assertThat(got).isPresent();
            assertThat(got.get().retryable()).isTrue();
        }

        @Test
        @DisplayName("★ 超时")
        void timeout() {
            var got = ModelFailureClassifier.classify(err("Request timed out after 30s"));

            assertThat(got).isPresent();
            assertThat(got.get().retryable()).isTrue();
        }
    }

    @Nested
    @DisplayName("认不出来 → 不猜")
    class Unknown {

        @Test
        @DisplayName("★ 无关的错误 → 返回空，让上层走原来的兜底")
        void unrelatedErrorIsEmpty() {
            assertThat(ModelFailureClassifier.classify(
                    err("NullPointerException at Foo.bar"))).isEmpty();
            assertThat(ModelFailureClassifier.classify(
                    err("没能理解这个问题"))).isEmpty();
        }

        @Test
        @DisplayName("空消息也不猜")
        void emptyMessage() {
            assertThat(ModelFailureClassifier.classify(err(null))).isEmpty();
            assertThat(ModelFailureClassifier.classify(err("   "))).isEmpty();
            assertThat(ModelFailureClassifier.classify(null)).isEmpty();
        }

        @Test
        @DisplayName("异常链里才有真实原因时也能认出来")
        void findsCauseInChain() {
            var wrapped = new RuntimeException("outer wrapper",
                    new IllegalStateException("401 Unauthorized: invalid token"));

            var got = ModelFailureClassifier.classify(wrapped);

            assertThat(got).as("外层包装不该遮住真实原因").isPresent();
            assertThat(got.get().retryable()).isFalse();
        }
    }
}

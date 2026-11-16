package com.liusongboyy.chatdata.engine.agent;

import java.util.Locale;
import java.util.Optional;

/**
 * 判断一个异常是不是「模型服务不可用」，并区分可重试与否。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>供应商的错误五花八门，但用户只需要知道三件事之一：
 *
 * <ol>
 *   <li><b>等一会儿再来</b>（限流、临时故障）</li>
 *   <li><b>找管理员</b>（密钥失效、订阅过期、模型不存在）</li>
 *   <li><b>换一种问法</b>（模型答得不对——那不是本类管的）</li>
 * </ol>
 *
 * <p>不做这个区分的话，全部落到兜底的「处理失败，请稍后重试」。
 * 而实测踩到过：订阅过期时报的就是那句，让人以为是我们的系统坏了。
 *
 * <h2>按什么判</h2>
 *
 * <p>**按错误消息里的关键字**，不按异常类型——因为 agentscope 把供应商的
 * 错误包成了统一的 {@code BadRequestException} / {@code RuntimeException}，
 * 类型上分不出来。而各家供应商的消息里都带着 HTTP 状态码或错误码。
 *
 * <p>这是**启发式判断**，不保证覆盖所有供应商。判断不出来时返回
 * {@link Optional#empty()}，让上层走原来的兜底——**不要猜**。
 *
 * <h2>换供应商要复核</h2>
 *
 * <p>下面这些关键字来自火山方舟与 OpenAI 兼容协议的实测。换一家可能不适用，
 * 那时表现是「又变回处理失败了」——不会更糟，只是没那么好。
 */
public final class ModelFailureClassifier {

    /**
     * 可重试的信号：限流、临时故障、超时。
     *
     * <p>用户等一会儿再来可能就好了。
     */
    private static final String[] RETRYABLE = {
            "429", "rate limit", "too many requests", "quota exceeded",
            "500", "502", "503", "504", "internal server error",
            "bad gateway", "service unavailable", "gateway timeout",
            "timeout", "timed out", "connection reset",
            "overloaded", "server is busy",
    };

    /**
     * 不可重试的信号：配置问题。
     *
     * <p>重试一万次也不会成功——必须有人去改密钥、续订阅、换模型。
     */
    private static final String[] FATAL = {
            // 密钥
            "401", "unauthorized", "invalid api key", "invalid_api_key",
            "authentication", "incorrect api key", "invalid token",
            // 订阅 / 权限
            "invalid subscription", "invalid_subscription", "subscription has expired",
            "no valid", "not activated", "arrears", "欠费",
            // 模型不存在 / 无权使用
            "404", "model not found", "model_not_found", "does not exist",
            "no such model", "model is not supported", "not authorized to use",
            // 余额
            "insufficient balance", "insufficient_quota", "insufficient quota",
    };

    private ModelFailureClassifier() {
    }

    /**
     * 分类。
     *
     * @param e 模型调用抛出的异常
     * @return 分类结果；判断不出来时返回空
     */
    public static Optional<ModelUnavailableException> classify(Throwable e) {
        String msg = deepestMessage(e);
        if (msg == null || msg.isBlank()) {
            return Optional.empty();
        }
        String lower = msg.toLowerCase(Locale.ROOT);

        // **先判不可重试**：一条消息里可能同时出现 "500" 与 "invalid subscription"
        // （比如方舟的错误体里带 request id 和状态码），而那种情况显然是配置问题
        for (String k : FATAL) {
            if (lower.contains(k)) {
                return Optional.of(new ModelUnavailableException(
                        fatalMessage(k, msg), k, false, e));
            }
        }
        for (String k : RETRYABLE) {
            if (lower.contains(k)) {
                return Optional.of(new ModelUnavailableException(
                        "模型服务暂时繁忙，请稍后重试", k, true, e));
            }
        }
        return Optional.empty();
    }

    /**
     * 不可重试时给用户的话。
     *
     * <p>**必须能指导行动**：「模型服务不可用」是废话，用户不知道该找谁、做什么。
     * 按原因分三类，每类给不同的下一步。
     */
    private static String fatalMessage(String keyword, String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("subscription") || lower.contains("no valid")
                || lower.contains("arrears") || lower.contains("欠费")
                || lower.contains("insufficient")) {
            return "模型服务已欠费或订阅过期，请联系管理员续订";
        }
        if (lower.contains("401") || lower.contains("unauthorized")
                || lower.contains("api key") || lower.contains("authentication")
                || lower.contains("invalid token")) {
            return "模型的密钥无效或已过期，请联系管理员";
        }
        if (lower.contains("404") || lower.contains("model not found")
                || lower.contains("does not exist") || lower.contains("no such model")
                || lower.contains("not supported") || lower.contains("not authorized")) {
            return "配置的模型不存在或无权使用，请联系管理员";
        }
        // 认得出是配置问题、但认不出是哪一种
        return "模型服务不可用，请联系管理员";
    }

    /**
     * 取异常链最里层那句。
     *
     * <p>外层通常是 agentscope 的包装（{@code HTTP transport error during streaming}），
     * 而供应商的真实错误在最里层。
     */
    private static String deepestMessage(Throwable e) {
        if (e == null) {
            return null;
        }
        // **优先用最外层**：agentscope 把供应商的错误体拼在了外层消息里
        // （"HTTP transport error ... | {error:{code:InvalidSubscription...}}"），
        // 而最里层可能只有一句 "400 Bad Request"
        String outer = e.getMessage();
        if (outer != null && outer.length() > 40) {
            return outer;
        }
        Throwable t = e;
        String best = outer;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
            if (t.getMessage() != null && !t.getMessage().isBlank()) {
                best = t.getMessage();
            }
        }
        return best;
    }
}

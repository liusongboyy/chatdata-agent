package com.liusongboyy.chatdata.channel.ask;

import com.liusongboyy.chatdata.channel.config.ChannelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 调 ask 的问数接口。
 *
 * <h2>为什么仍然走 SSE</h2>
 *
 * <p>ask 只有 SSE 形态（{@code /api/engine/engine}）。这里读流但<b>只取
 * {@code result} 与 {@code error} 事件</b>，其余（{@code started} /
 * {@code progress} / {@code heartbeat}）直接丢弃。
 *
 * <p>不给 ask 新加一个同步接口：少一个接口就少一处与 ask 行为不一致的地方。
 * 而 {@code progress} 的内容是「正在生成 DSL」「正在执行 SQL」，
 * 对业务人员是噪音，转发到 IM 里只会刷屏。
 *
 * <h2>超时必须大于 ask 自己的超时</h2>
 *
 * <p>ask 的 SSE 超时是 180 秒。这里设 200 秒——若比它短，我们会在 ask
 * 还在算的时候先断开，用户看到「超时」而实际查询成功了，
 * 那会让人反复重试，把负载放大。
 */
@Component
public class AskClient {

    private static final Logger log = LoggerFactory.getLogger(AskClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ChannelProperties properties;
    private final RestClient client;

    public AskClient(ChannelProperties properties) {
        this.properties = properties;
        String base = properties.getAsk().getBaseUrl();
        this.client = RestClient.builder()
                .baseUrl(base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                .build();
    }

    /**
     * 问一个问题。
     *
     * @param token   该用户自己的 IAM 令牌。ask 用它做内省——权限完全按他本人算
     * @param body    请求体：问题、助理、会话 id
     * @return 结果；若 ask 返回 error 事件则抛 {@link AskFailedException}
     */
    public AskResult ask(String token, Map<String, Object> body) {
        try {
            return client.post()
                    .uri("/api/engine/engine")
                    .headers(h -> {
                        h.setBearerAuth(token);
                        h.setAccept(java.util.List.of(MediaType.TEXT_EVENT_STREAM));
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    // 用 exchange 拿原始流：RestClient 的 body() 会把 SSE 当成
                    // 一个完整响应体去反序列化，那样既拿不到事件边界也等不到流结束
                    .exchange((req, res) -> {
                        if (res.getStatusCode().isError()) {
                            throw new AskFailedException(
                                    "问数服务返回 " + res.getStatusCode().value());
                        }
                        return readEvents(res.getBody());
                    });
        } catch (AskFailedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("调用问数失败：{}", e.getMessage());
            throw new AskFailedException("问数服务暂不可用，请稍后再试");
        }
    }

    /**
     * 逐行读 SSE，只关心 {@code event:} 与 {@code data:} 两类行。
     *
     * <p>SSE 的格式是「事件块之间以空行分隔」：
     * <pre>
     * event:result
     * data:{"columns":[...],"rows":[...]}
     * </pre>
     * 这里不需要完整的 SSE 解析器——只认这两种前缀，其余行忽略。
     */
    private AskResult readEvents(java.io.InputStream stream) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String eventName = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    AskResult done = dispatch(eventName, data.toString());
                    if (done != null) {
                        return done;
                    }
                    eventName = null;
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith("event:")) {
                    eventName = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()));
                }
                // 其余（id:、retry:、注释行 :xxx）忽略
            }
            // 流结束但没有终态事件——问数没给出结果，不能当成功
            throw new AskFailedException("问数没有返回结果，请重试");
        } catch (AskFailedException e) {
            throw e;
        } catch (Exception e) {
            log.error("读取问数流失败：{}", e.getMessage());
            throw new AskFailedException("读取问数结果失败，请重试");
        }
    }

    private AskResult dispatch(String eventName, String rawData) {
        if (eventName == null || rawData.isBlank()) {
            return null;
        }
        JsonNode payload;
        try {
            payload = JSON.readTree(rawData);
        } catch (RuntimeException e) {
            log.warn("问数事件不是合法 JSON，忽略：{}", rawData);
            return null;
        }
        return switch (eventName) {
            case "result" -> AskResult.from(payload);
            case "error" -> throw new AskFailedException(
                    payload.path("message").asString("问数失败，请重试"));
            default -> null;   // started / progress / heartbeat：丢弃
        };
    }

    /** 问数失败。消息是**给最终用户看的**。 */
    public static class AskFailedException extends RuntimeException {
        public AskFailedException(String message) {
            super(message);
        }
    }
}

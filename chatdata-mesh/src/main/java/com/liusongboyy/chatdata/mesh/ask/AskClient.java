package com.liusongboyy.chatdata.mesh.ask;

import com.liusongboyy.chatdata.mesh.config.MeshProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调 ask 的问数接口。
 *
 * <h2>只消费 result / error</h2>
 *
 * <p>ask 的 SSE 是**事件级**的（ADR-0003）：{@code started} / {@code progress} /
 * {@code heartbeat} / {@code result} / {@code error}。A2A 调用方要的是终态，
 * 中间进度对它没有意义——而且 A2A 的 artifact 只在任务完成时产出。
 *
 * <p>所以这里读完整的流，只取第一个 {@code result} 或 {@code error} 就返回。
 *
 * <h2>超时必须大于 ask 自己的超时</h2>
 *
 * <p>ask 的 SSE 超时是 180 秒。配置里设 200 秒——比它短的话，
 * 我们会在 ask 还在算的时候先断开，调用方看到超时而实际查询成功了，
 * 那会让它反复重试，把负载放大。
 */
@Component
public class AskClient {

    private static final Logger log = LoggerFactory.getLogger(AskClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * 问数端点。
     *
     * <p>真身在 {@code AskEngineStreamController}：类级
     * {@code @RequestMapping("/api/ask")} + 方法级 {@code @PostMapping("/engine")}。
     * **不要凭印象写 /api/engine/engine**——那个路径不存在，
     * 而 404 会被误读成"权限问题"（channel 那边踩过）。
     */
    static final String ASK_PATH = "/api/ask/engine";

    private final MeshProperties properties;
    private final RestClient client;

    /**
     * 读超时必须显式配。
     *
     * <p><b>不配的后果不是「慢」，是「卡到别人的超时」。</b>RestClient 默认无限等待，
     * 于是 ask 卡住时我们一直挂着，直到控制面的 conversation deadline（5 分钟）
     * 到点，用户看到的是 {@code conversation_timeout} 而不是我们的错误。
     *
     * <p>而 {@code mesh.ask.timeout} 早就定了 200 秒（比 ask 自己的 180 秒长），
     * 但从来没被使用过——配置写了不生效比没写更糟，因为看配置的人会以为它是准的。
     *
     * <p>连接超时也得设：不设的话 ask 挂掉时连接会挂在那里，
     * 而那是「服务不可用」，我们应该几秒内就告诉调用方。
     */
    public AskClient(MeshProperties properties) {
        this.properties = properties;
        String base = properties.getAsk().getBaseUrl();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration connect = properties.getAsk().getConnectTimeout();
        factory.setConnectTimeout(connect);
        factory.setReadTimeout(properties.getAsk().getTimeout());
        this.client = RestClient.builder()
                .baseUrl(base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                .requestFactory(factory)
                .build();
    }

    /**
     * 问一个问题。
     *
     * @param token     被代理用户的 IAM 令牌。ask 内省后按**他本人**的权限判
     * @param question  问题
     * @param sessionId 会话 id，可空。非空时 ask 会接上多轮上下文
     * @param analyze   是否走分析编排（多步取数 + 确定性结论）
     */
    public AskResult ask(String token, String question, String sessionId, boolean analyze) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);

        // **必须显式指定域。**
        //
        // 不传的话 ask 用「第一个启用中的域」——通常是保险域。
        // 于是 AgentCard 声明「门店分析」，调用方问门店问题，拿到的是保费数字。
        // 这类错误不报错：SQL 合法、数据真实，只是答的不是问的那个问题。
        String domainId = properties.getA2a().getDomainId();
        if (domainId == null || domainId.isBlank()) {
            // 没配域就不发请求——宁可报错，也不要悄悄落到错误的域上
            throw new AskFailedException(
                    "mesh.a2a.domain-id 未配置。不指定域时 ask 会落到第一个启用中的域，"
                            + "而那通常不是 AgentCard 声明的那个");
        }
        body.put("domainId", domainId);

        if (sessionId != null && !sessionId.isBlank()) {
            body.put("sessionId", sessionId);
        }
        if (analyze) {
            body.put("analyze", true);
        }

        try {
            return client.post()
                    .uri(ASK_PATH)
                    .headers(h -> {
                        h.setBearerAuth(token);
                        h.setAccept(java.util.List.of(MediaType.TEXT_EVENT_STREAM));
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((req, res) -> {
                        if (res.getStatusCode().isError()) {
                            // 带上状态码：403 与 500 的处理方式完全不同，
                            // 而调用方只能看到我们这条消息
                            throw new AskFailedException(
                                    "问数服务返回 " + res.getStatusCode().value());
                        }
                        return readEvents(res.getBody());
                    });
        } catch (AskFailedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("调用问数失败：{}", e.getMessage());
            throw new AskFailedException("问数服务暂不可用");
        }
    }

    /** 逐行读 SSE，只认 {@code event:} 与 {@code data:} 两类行。 */
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
            }
            // 流结束但没拿到终态——问数没给出结果，不能当成功
            throw new AskFailedException("问数没有返回结果");
        } catch (AskFailedException e) {
            throw e;
        } catch (Exception e) {
            log.error("读取问数流失败：{}", e.getMessage());
            throw new AskFailedException("读取问数结果失败");
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
            case "error" -> {
                // 把 ask 的错误码带出来。吞掉它会让「模型额度用完」
                // 显示成模糊的失败，排查时先怀疑错地方（channel 那边踩过）
                String code = payload.path("code").asString("");
                String message = payload.path("message").asString("问数失败");
                throw new AskFailedException(code.isBlank() ? message : message + "（" + code + "）");
            }
            default -> null;   // started / progress / heartbeat：丢弃
        };
    }

    /** 问数失败。消息给**调用方 agent 的开发者**看。 */
    public static class AskFailedException extends RuntimeException {
        public AskFailedException(String message) {
            super(message);
        }
    }
}

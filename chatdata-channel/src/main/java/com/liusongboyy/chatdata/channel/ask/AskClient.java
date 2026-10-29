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
import java.util.List;
import java.util.Map;

/**
 * 调 ask 的问数接口。
 *
 * <h2>为什么仍然走 SSE</h2>
 *
 * <p>ask 只有 SSE 形态（{@code /api/ask/engine}）。这里读流但<b>只取
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

    /**
     * 问数端点。
     *
     * <p>写错这个路径的代价是 403——不，是 <b>404 被误读成权限问题</b>。
     * 曾经写成 {@code /api/engine/engine}（其实是 {@code /api/ask/engine}），
     * 前端只看到「问数服务返回 403/404」，而 ask 的日志里干干净净——
     * 因为请求根本没进到任何 handler。
     *
     * <p>所以这里单独提成常量并注明来源：它的真身在
     * {@code AskEngineStreamController} 的类级 {@code @RequestMapping("/api/ask")}
     * 加方法上的 {@code @PostMapping("/engine")}。
     */
    static final String ASK_PATH = "/api/ask/engine";

    /** 当前用户可用的助理列表。**未登录也返回 200 + 空列表**，不是 401。 */
    static final String ASSISTANTS_PATH = "/api/ask/assistants";

    /** 当前域能问哪些字段。**不需要登录**，但带上令牌才能按助理解析。 */
    static final String CAPABILITIES_PATH = "/api/ask/capabilities";

    /** 反馈。提交后一律 PENDING，是否采纳由管理台审核。 */
    static final String FEEDBACK_PATH = "/api/ask/feedback";

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
                    .uri(ASK_PATH)
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
     * 当前用户能用哪些助理。
     *
     * <p>channel 必须知道这个：不指定助理时 ask 会落到「第一个启用中的域」
     * （通常是保险域），问门店的问题就会得到「没能理解」——
     * 而用户明明有门店分析的权限。实测踩过。
     */
    public List<AskAssistant> assistants(String token) {
        try {
            AskAssistant[] list = client.get()
                    .uri(ASSISTANTS_PATH)
                    .headers(h -> h.setBearerAuth(token))
                    .retrieve()
                    .body(AskAssistant[].class);
            return list == null ? List.of() : List.of(list);
        } catch (RuntimeException e) {
            log.warn("取助理列表失败：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 提交反馈。
     *
     * <p>用<b>提问者本人</b>的令牌调——不是 channel 自己的身份。否则审计里
     * 所有渠道反馈都记在 bot 名下，"谁觉得这条答案不对"就查不出来了。
     */
    public void submitFeedback(String token, Map<String, Object> payload) {
        client.post()
                .uri(FEEDBACK_PATH)
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    throw new AskFailedException("反馈提交失败：" + res.getStatusCode().value());
                })
                .toBodilessEntity();
    }

    /**
     * 这个助理能问哪些字段。
     *
     * <p>存在的理由：用户会问「你能问哪些数据」。那是<b>能力问题，不是数据问题</b>——
     * 让模型把它翻译成查询，只会得到一个语法正确但毫无意义的查询，然后失败或
     * 回一堆不相干的行。ask 侧早就有这个接口，channel 之前没接。
     */
    public Capabilities capabilities(String token, String assistantId) {
        try {
            return client.get()
                    .uri(u -> u.path(CAPABILITIES_PATH)
                            .queryParam("assistantId", assistantId)
                            .build())
                    .headers(h -> h.setBearerAuth(token))
                    .retrieve()
                    .body(Capabilities.class);
        } catch (RuntimeException e) {
            log.warn("取能力清单失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 一个域的能力清单。
     *
     * @param domainName 域的中文名，如「门店分析域」
     * @param fields     可问的字段（已是中文别名）
     */
    public record Capabilities(String domainName, List<String> fields) {
        public Capabilities {
            fields = fields == null ? List.of() : List.copyOf(fields);
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
            case "error" -> {
                // 把 ask 的错误码带出来，而不是一律说「问数失败，请重试」。
                // 曾经因为模板丢掉了 code，把一个「模型额度用完」显示成模糊的失败，
                // 排查时先怀疑了渠道、路径、权限——绕了一圈才在 ask 日志里看到 429。
                String code = payload.path("code").asString("");
                String message = payload.path("message").asString("问数失败，请重试");
                if (code.isBlank()) {
                    throw new AskFailedException(message);
                }
                // 只对**用户能采取行动**的几类给出补充说明，其余保持原样，
                // 不把内部错误码堆到用户脸上
                String hint = switch (code) {
                    case "DSL_FAILED" -> "（模型服务不可用，请联系管理员）";
                    default -> "";
                };
                throw new AskFailedException(message + hint);
            }
            default -> null;   // started / progress / heartbeat：丢弃
        };
    }

    /**
     * 一个可用助理。
     *
     * <p>字段与 ask 的 {@code UsableAssistant} 对应，但只取 channel 用得上的：
     * 多一个字段就多一处要跟着改的地方。
     */
    public record AskAssistant(String id, String name, String welcome, List<String> questions) {
        public AskAssistant {
            questions = questions == null ? List.of() : List.copyOf(questions);
        }
    }

    /** 问数失败。消息是**给最终用户看的**。 */
    public static class AskFailedException extends RuntimeException {
        public AskFailedException(String message) {
            super(message);
        }
    }
}

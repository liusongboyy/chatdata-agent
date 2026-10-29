package com.liusongboyy.chatdata.channel.inbound;

import com.liusongboyy.chatdata.channel.answer.AnswerStore;
import com.liusongboyy.chatdata.channel.ask.AskClient;
import com.liusongboyy.chatdata.channel.ask.AskResult;
import com.liusongboyy.chatdata.channel.identity.ChannelIdentityService;
import com.liusongboyy.chatdata.channel.identity.ChannelTokenService;
import com.liusongboyy.chatdata.channel.render.FeishuCardRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 卡片回调：翻页与反馈。
 *
 * <h2>3 秒硬约束与同步处理</h2>
 *
 * <p>飞书要求回调在 3 秒内响应，且响应体决定客户端行为
 * （弹 toast / 更新卡片）。所以这条路<b>不能</b>像消息那样入队异步处理——
 * 异步了就没法"更新卡片"，只能先弹个 toast 再慢慢改，那要多一条
 * 「延时更新」链路和它的失败态。
 *
 * <p>翻页只是查库 + 重渲染，反馈是一次 HTTP 调用，都在 3 秒内做得完。
 *
 * <h2>点击者必须是提问者本人</h2>
 *
 * <p>回调带 {@code operator.open_id}。不校验的话，群里任何人都能替别人点
 * 「有用」或「答错了」——而反馈要进审核、沉淀成 SQL 范例，
 * 被污染的反馈比没有反馈更糟。
 */
@Component
public class CardActionHandler {

    private static final Logger log = LoggerFactory.getLogger(CardActionHandler.class);

    private final AnswerStore answers;
    private final FeishuCardRenderer renderer;
    private final ChannelIdentityService identities;
    private final ChannelTokenService tokens;
    private final AskClient ask;

    public CardActionHandler(AnswerStore answers, FeishuCardRenderer renderer,
                             ChannelIdentityService identities, ChannelTokenService tokens,
                             AskClient ask) {
        this.answers = answers;
        this.renderer = renderer;
        this.identities = identities;
        this.tokens = tokens;
        this.ask = ask;
    }

    /**
     * 处理一次卡片回调。
     *
     * @param body 解密后的回调事件体
     * @return 直接作为 HTTP 响应体的 map。**必须是 200 + 这个结构**，
     *         不能返回 3xx（飞书明确禁止，用户端会显示交互错误）
     */
    public Map<String, Object> handle(JsonNode body) {
        JsonNode event = body.path("event");
        JsonNode action = event.path("action");

        String openId = event.path("operator").path("open_id").asString("");
        JsonNode value = action.path("value");
        String type = value.path("action").asString("");
        long answerId;
        try {
            answerId = Long.parseLong(value.path("answerId").asString("0"));
        } catch (NumberFormatException e) {
            return toast("error", "这张卡片的数据已失效");
        }

        Optional<AnswerStore.StoredAnswer> found = answers.find(answerId);
        if (found.isEmpty()) {
            return toast("error", "找不到这条结果了，请重新提问");
        }
        AnswerStore.StoredAnswer answer = found.get();

        // ★ 点击者必须是提问者本人。否则群里任何人都能替别人反馈
        Optional<String> actor = identities.resolveUsername("feishu", openId);
        if (actor.isEmpty() || !actor.get().equals(answer.username())) {
            log.warn("卡片回调被拒：不是本人操作。actor={} owner={} answerId={}",
                    openId, answer.username(), answerId);
            return toast("error", "只有提问者本人可以操作");
        }

        return switch (type) {
            case "page" -> handlePage(answer, value.path("page").asInt(0));
            case "helpful" -> handleFeedback(answer, actor.get(), "HELPFUL");
            case "wrong" -> handleFeedback(answer, actor.get(), "WRONG_ANSWER");
            default -> {
                log.warn("未知的卡片动作：{}", type);
                yield toast("error", "不支持的操作");
            }
        };
    }

    /** 翻页：查库重渲染，直接把新卡片放回响应体里（客户端原地替换）。 */
    private Map<String, Object> handlePage(AnswerStore.StoredAnswer answer, int page) {
        AskResult result = new AskResult(
                answer.columns(), answer.rows(), answer.rowCount(),
                answer.sql(), answer.maskedColumns(), null, 0, answer.domainId(), null, null);
        String card = renderer.renderResult(answer.question(), result, answer.id(),
                Math.max(0, page));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("card", Map.of("type", "raw", "data", parseCard(card)));
        return resp;
    }

    /**
     * 反馈：调 ask 的 /api/ask/feedback。
     *
     * <p>要**代表提问者本人**去提交——所以换一张他的令牌，
     * 而不是用 channel 自己的身份。否则审计里所有反馈都记在 bot 名下。
     */
    private Map<String, Object> handleFeedback(AnswerStore.StoredAnswer answer,
                                               String username, String type) {
        try {
            String token = tokens.tokenFor("feishu", username);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", type);
            payload.put("question", answer.question());
            payload.put("generatedSql", answer.sql());
            payload.put("assistantId", answer.assistantId());
            payload.put("domainId", answer.domainId());
            ask.submitFeedback(token, payload);
            log.info("渠道反馈已提交：user={} answerId={} type={}", username, answer.id(), type);
            return toast("success", "HELPFUL".equals(type) ? "谢谢反馈" : "已记录，会有人跟进");
        } catch (RuntimeException e) {
            log.warn("渠道反馈提交失败：answerId={} {}", answer.id(), e.getMessage());
            return toast("error", "提交失败，请稍后再试");
        }
    }

    private static Map<String, Object> toast(String type, String content) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", type);
        t.put("content", content);
        return Map.of("toast", t);
    }

    /** 卡片字符串转成响应体里的对象结构。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseCard(String json) {
        try {
            return (Map<String, Object>) tools.jackson.databind.json.JsonMapper.builder()
                    .build().readValue(json, Map.class);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}

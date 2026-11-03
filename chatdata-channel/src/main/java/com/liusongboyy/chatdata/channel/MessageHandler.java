package com.liusongboyy.chatdata.channel;

import com.liusongboyy.chatdata.channel.answer.AnswerStore;
import com.liusongboyy.chatdata.channel.ask.AskClient;
import com.liusongboyy.chatdata.channel.ask.AskResult;
import com.liusongboyy.chatdata.channel.identity.BindTicketIssuer;
import com.liusongboyy.chatdata.channel.identity.ChannelIdentityService;
import com.liusongboyy.chatdata.channel.identity.ChannelTokenService;
import com.liusongboyy.chatdata.channel.inbound.InboundMessage;
import com.liusongboyy.chatdata.channel.inbound.InboundQueue;
import com.liusongboyy.chatdata.channel.render.FeishuCardRenderer;
import com.liusongboyy.chatdata.channel.render.FeishuSender;
import com.liusongboyy.chatdata.channel.session.ChannelSessionService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 一条消息的完整处理：身份 → 令牌 → 问数 → 排版 → 回复。
 *
 * <h2>它不做权限判断</h2>
 *
 * <p>这个类里没有一处「这个人能不能查」的判断，因为那全在 ask 里。
 * 渠道只负责「拿他的令牌去问，把答案排回去」。
 * 一旦这里开始出现权限逻辑，就说明边界破了——那是最危险的一种腐化，
 * 因为它看起来像是"顺手加的便利"，实际上是第二个权限判定点。
 */
@Component
public class MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);

    private final AnswerStore answers;
    private final ChannelIdentityService identities;
    private final BindTicketIssuer bindTickets;
    private final ChannelTokenService tokens;
    private final ChannelSessionService sessions;
    private final AskClient ask;
    private final FeishuCardRenderer renderer;
    private final FeishuSender sender;
    private final InboundQueue queue;

    public MessageHandler(AnswerStore answers,
                          ChannelIdentityService identities, BindTicketIssuer bindTickets,
                          ChannelTokenService tokens,
                          ChannelSessionService sessions, AskClient ask,
                          FeishuCardRenderer renderer, FeishuSender sender,
                          InboundQueue queue) {
        this.answers = answers;
        this.identities = identities;
        this.bindTickets = bindTickets;
        this.tokens = tokens;
        this.sessions = sessions;
        this.ask = ask;
        this.renderer = renderer;
        this.sender = sender;
        this.queue = queue;
    }

    /** 把本处理器挂到队列上。队列在工作线程里回调它。 */
    @PostConstruct
    void register() {
        queue.setHandler(this::handle);
    }

    void handle(InboundMessage msg) {
        String question = msg.text();
        log.info("渠道消息：channel={} user={} group={} q={}",
                msg.channel(), msg.senderId(), msg.fromGroup(), question);

        // 「新话题」「重置」：显式开新会话。放在身份校验之前——
        // 它不查数据，只动本地的会话映射
        if (isResetCommand(question)) {
            Optional<String> username = identities.resolveUsername(msg.channel(), msg.senderId());
            if (username.isEmpty()) {
                String ticket = bindTickets.issue(msg.channel(), msg.senderId(), question);
                reply(msg, renderer.renderBindGuide(bindTickets.bindUrl(ticket)));
                return;
            }
            sessions.startNew(msg.channel(), msg.chatKey(), username.get());
            tokens.evict(username.get());
            reply(msg, renderer.renderError(question, "已开始新会话，请直接提问。"));
            return;
        }

        // ① 身份。未绑定 → 给绑定引导，**不发 ask 请求**
        Optional<String> username = identities.resolveUsername(msg.channel(), msg.senderId());
        if (username.isEmpty()) {
            // 自助绑定：给一条能点的链接，而不是「请联系管理员」——
            // 后者会让每一次新用户接入都变成一次人工工单
            String ticket = bindTickets.issue(msg.channel(), msg.senderId(), msg.text());
            reply(msg, renderer.renderBindGuide(bindTickets.bindUrl(ticket)));
            return;
        }

        // ② 令牌
        String token;
        try {
            token = tokens.tokenFor(msg.channel(), username.get());
        } catch (ChannelTokenService.TokenUnavailableException e) {
            // 消息本身就是给用户看的（见 ChannelTokenService.describe）
            reply(msg, renderer.renderError(question, e.getMessage()));
            return;
        }

        // ③ 助理。**必须先确定走哪个助理**——不指定的话 ask 会落到
        //    「第一个启用中的域」（通常是保险域），问门店的问题会得到
        //    「没能理解这个问题」，而用户明明有门店分析的权限。实测踩过。
        List<AskClient.AskAssistant> available = ask.assistants(token);
        if (available.isEmpty()) {
            // 没有可用助理（或列表取不到）——说清楚，而不是拿默认域硬答
            reply(msg, renderer.renderError(question,
                    "你没有可用的问数助理，请联系管理员分配权限。"));
            return;
        }

        // 「#助手 2」：显式选助理。选中后请用户重新提问——
        // 直接拿当前这句去查是错的，那句话本来是在回答"选哪个"，不是真问题
        Optional<Integer> pick = parseAssistantPick(question);
        if (pick.isPresent()) {
            int idx = pick.get();
            if (idx < 1 || idx > available.size()) {
                // 包括「只打了 #助手 没给编号」：直接出列表比报一句错有用
                reply(msg, renderer.renderAssistantPicker(question, available));
                return;
            }
            AskClient.AskAssistant chosen = available.get(idx - 1);
            // 换助理必须换会话：多轮上下文绑在 conversationId 上，
            // 两个助理的域不同，接上文的追问会落到错误的域里
            String conv = sessions.startNew(msg.channel(), msg.chatKey(), username.get());
            sessions.selectAssistant(msg.channel(), msg.chatKey(), username.get(), chosen.id(), conv);
            reply(msg, renderer.renderAssistantSwitched(chosen));
            return;
        }

        Optional<AskClient.AskAssistant> assistant = pickAssistant(msg, username.get(), available);
        if (assistant.isEmpty()) {
            // 多个助理、还没选过——让他选，**不替他猜**
            reply(msg, renderer.renderAssistantPicker(question, available));
            return;
        }
        String assistantId = assistant.get().id();

        // ③b 能力询问：「我能问哪些数据」不是数据问题，是能力问题。
        //     放行给 ask 的话，模型会把它翻译成一个查询，回一堆不相干的行——
        //     那正是用户反馈"不太灵活"的那个现象。ask 侧有 /capabilities 专门回答它。
        if (isCapabilityQuestion(question)) {
            AskClient.Capabilities caps = ask.capabilities(token, assistantId);
            reply(msg, renderer.renderCapabilities(assistant.get(), caps));
            return;
        }

        // ③c 深入分析：「#分析 为什么华东销量下降」→ 走 ask 的分析编排
        //     （拆成多次查询 + 确定性结论），而不是单次查询。
        //     分析会多花几次查询与模型调用，所以**必须显式要求**，不由系统自动判定。
        Optional<String> analysisQuestion = parseAnalysisCommand(question);
        boolean wantsAnalysis = analysisQuestion.isPresent();
        if (wantsAnalysis) {
            question = analysisQuestion.get();
        }

        // ④ 会话。群里按发言人隔离，chatKey 里已经带了 open_id
        String conversationId = sessions.resolve(msg.channel(), msg.chatKey(), username.get());
        // 换过助理就要换会话：多轮上下文绑在 conversationId 上，
        // 两个助理的域不同，接上文的追问会落到错误的域里
        Optional<String> last = sessions.selectedAssistant(msg.channel(), msg.chatKey(), username.get());
        if (last.isPresent() && !last.get().equals(assistantId)) {
            conversationId = sessions.startNew(msg.channel(), msg.chatKey(), username.get());
        }
        sessions.selectAssistant(msg.channel(), msg.chatKey(), username.get(),
                assistantId, conversationId);

        // ⑤ 占位卡片。5–15 秒的沉默会被当成没发出去
        String pendingId = reply(msg, renderer.renderPending(question));

        // ⑥ 问数
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        body.put("sessionId", conversationId);
        // 带上助理：它决定落哪个域，也决定审计里"这次走的是哪个助理"
        body.put("assistantId", assistantId);
        if (wantsAnalysis) {
            body.put("analyze", true);
        }

        String card;
        try {
            AskResult result = ask.ask(token, body);
            // 先落库再渲染：卡片里的翻页与反馈按钮要带 answerId。
            // 存失败不阻断发送——用户先看到数据最重要，代价只是不能翻页/反馈
            Long answerId = answers.save(msg.channel(), msg.chatKey(), username.get(),
                            assistantId, result.domainId(), question, result)
                    .orElse(null);
            card = renderer.renderResult(question, result, answerId, 0);
        } catch (AskClient.AskFailedException e) {
            card = renderer.renderError(question, e.getMessage());
        } catch (RuntimeException e) {
            log.error("问数处理异常：channel={} user={}", msg.channel(), username.get(), e);
            card = renderer.renderError(question, "处理失败，请稍后再试。");
        }

        // ⑥ 更新占位卡片。飞书没有"更新任意消息"的稳妥写法时，宁可再发一条——
        //    发两条最多是刷屏，漏掉一条是"用户永远等不到答案"
        sendCardUpdate(msg, pendingId, card);
    }

    /**
     * 在已取到的可用列表里挑一个。
     *
     * <p>规则：
     * <ol>
     *   <li>会话里已经选过且仍可用 → 用它</li>
     *   <li>只有一个可用 → 自动选它，不打扰用户</li>
     *   <li>多个可用、没选过（或已选的失效）→ 返回空，调用方出选择卡片</li>
     * </ol>
     *
     * <p>第 3 条是刻意的：<b>不替用户猜</b>。猜错的代价是"答非所问"，
     * 而用户没法分辨那是猜错还是数据本身如此——比让他先选一下糟糕得多。
     */
    private Optional<AskClient.AskAssistant> pickAssistant(
            InboundMessage msg, String username, List<AskClient.AskAssistant> available) {

        Optional<String> chosen = sessions.selectedAssistant(msg.channel(), msg.chatKey(), username);
        if (chosen.isPresent()) {
            // 已选的助理可能被停用或撤权——那时要重新选，而不是继续用
            Optional<AskClient.AskAssistant> still = available.stream()
                    .filter(a -> a.id().equals(chosen.get())).findFirst();
            if (still.isPresent()) {
                return still;
            }
            log.info("已选助理不再可用，重新选择：user={} old={}", username, chosen.get());
        }
        if (available.size() == 1) {
            return Optional.of(available.get(0));
        }
        return Optional.empty();
    }

    /**
     * 解析「#助手 2」「#助理 2」这类选择命令。
     *
     * <p>用编号而不是名字：名字是配置内容，用户记不住、也容易打错，
     * 而打错的表现是"没找到该助理"——又是一个本可避免的失败。
     */
    /**
     * 解析「#分析 xxx」前缀。
     *
     * @return 去掉前缀后的真问题；不是分析命令则为空
     */
    static Optional<String> parseAnalysisCommand(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String t = text.trim().replaceFirst("^[#＃]\\s*", "").trim();
        if (!t.startsWith("分析") && !t.startsWith("深入分析")) {
            return Optional.empty();
        }
        String rest = t.startsWith("深入分析")
                ? t.substring("深入分析".length())
                : t.substring("分析".length());
        rest = rest.trim();
        // 太短的尾巴不当命令：「分析报废」更像"分析一下报废"的口语，
        // 而分析编排会多花几次查询与模型调用——不该因为一个动词就触发。
        // 真要走分析，用户会说得更完整（「#分析 报废金额为什么涨了」）。
        return rest.length() < 4 ? Optional.empty() : Optional.of(rest);
    }

    static Optional<Integer> parseAssistantPick(String text) {
        if (text == null) {
            return Optional.empty();
        }
        // 去掉前缀**连同空格**：用户会打成「# 助手 4」——只去 # 的话
        // split 出来第一段是空串，匹配不上「助手」，命令被当成普通提问送去了 ask
        String t = text.trim().replaceFirst("^[#＃]\\s*", "").trim();
        String[] parts = t.split("\\s+");
        if (parts.length == 0) {
            return Optional.empty();
        }
        String head = parts[0];
        if (!head.equals("助手") && !head.equals("助理")) {
            return Optional.empty();
        }
        if (parts.length < 2) {
            return Optional.of(-1);   // 只说了「#助手」没给编号 → 由调用方提示
        }
        try {
            return Optional.of(Integer.parseInt(parts[1]));
        } catch (NumberFormatException e) {
            return Optional.of(-1);
        }
    }

    /**
     * 判断一句话是在问「你能干什么」，而不是在问数据。
     *
     * <h2>为什么要专门判这一条</h2>
     *
     * <p>用户问「我可以问你哪些方面的数据」时，如果不拦，这句话会被当成普通问数：
     * 模型把它翻译成一个查询，回一堆不相干的行——而用户真正想知道的是**能力边界**。
     * ask 侧早就为此提供了 {@code /capabilities}，注释里写的正是这个场景。
     *
     * <h2>判得保守，宁可漏判</h2>
     *
     * <p>误判的代价是：真问题被当成能力询问，用户得不到答案、还得换个说法再问一遍。
     * 漏判的代价是：能力询问被当成真问题，回一堆数据——用户同样能看出不对，重问即可。
     *
     * <p>所以这里要求「能力词 + 疑问词」<b>同时</b>出现，而不是见到「能问」就拦。
     * 「订单能问出多少金额」不该命中——它有「能问」，但没有疑问结构。
     */
    static boolean isCapabilityQuestion(String text) {
        if (text == null) {
            return false;
        }
        // 去掉命令前缀与空白，纯问句才判
        String t = text.trim().replaceAll("^[#＃]\\s*", "");
        if (t.isEmpty() || t.length() > 40) {
            // 太长的大概率是真问题（「帮我看看哪些门店的销量比上个月下降超过一成」）
            return false;
        }
        // 明确的能力问法：本身就是一个完整的"你在问能力"的短语，
        // 不需要再配名词。「你能干什么」「这个助手会什么」都属这类
        if (t.contains("干什么") || t.contains("做什么") || t.contains("会什么")
                || t.contains("有什么用") || t.contains("能干嘛") || t.contains("会干嘛")) {
            return true;
        }

        boolean asksWhat = t.contains("哪些") || t.contains("什么")
                || t.contains("支持") || t.contains("能问") || t.contains("可以问");
        if (!asksWhat) {
            return false;
        }
        // 「数据/指标/字段/内容/问题/方面/东西」之一出现，才算在问能力范围。
        // 这条是防误判的闸——「订单能问出多少金额」有「能问」，但没有这些名词
        return t.contains("数据") || t.contains("指标") || t.contains("字段") || t.contains("内容")
                || t.contains("问题") || t.contains("方面") || t.contains("东西")
                || t.contains("能力") || t.contains("功能");
    }

    static boolean isResetCommand(String text) {
        String t = text == null ? "" : text.trim();
        return t.equals("新话题") || t.equals("重置") || t.equals("新的会话")
                || t.equals("/new") || t.equals("/reset");
    }

    /**
     * 发送卡片。
     *
     * @return 新消息 id（用于后续更新）；发送失败返回 null
     */
    private String reply(InboundMessage msg, String cardJson) {
        if (msg.fromGroup() && msg.messageId() != null) {
            return sender.replyCard(msg.messageId(), cardJson);
        }
        // 私聊：用 chat_id 发（有的话），否则 open_id
        String receiveId = msg.chatId() != null && !msg.chatId().isBlank()
                ? msg.chatId() : msg.senderId();
        String type = msg.chatId() != null && !msg.chatId().isBlank() ? "chat_id" : "open_id";
        return sender.sendCard(type, receiveId, cardJson);
    }

    /**
     * 把占位卡片换成结果。
     *
     * <h2>先尝试原地更新，失败才再发一条</h2>
     *
     * <p>原地更新（{@code PATCH /im/v1/messages/:id}）是用户能感知到的差别：
     * 卡片"变了"而不是聊天记录被撑开。问数要几秒到十几秒，这个差别很明显。
     *
     * <p><b>但不能只依赖更新。</b>飞书的更新接口有限制（卡片消息才支持、
     * 发送后有时间窗），失败时如果什么都不做，占位卡片会永远停在
     * "正在查询…"——那个失败态比多发一条糟得多。
     *
     * <p>所以是"尽力更新 + 失败兜底发送"：更新成功是最好，失败退回到
     * 原来的行为，最多是刷屏。两个方向的失败代价都不致命。
     */
    private void sendCardUpdate(InboundMessage msg, String pendingId, String cardJson) {
        if (pendingId != null && !pendingId.isBlank() && sender.updateCard(pendingId, cardJson)) {
            log.debug("占位卡片已原地更新：messageId={}", pendingId);
            return;
        }
        // 更新不可用（未启用 / 消息过期 / 接口失败）——退回到"再发一条"
        log.debug("原地更新不可用，改为发送新消息：pendingId={}", pendingId);
        reply(msg, cardJson);
    }
}

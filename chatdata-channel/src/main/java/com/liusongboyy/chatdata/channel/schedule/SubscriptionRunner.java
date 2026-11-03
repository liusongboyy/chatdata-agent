package com.liusongboyy.chatdata.channel.schedule;

import com.liusongboyy.chatdata.channel.ask.AskClient;
import com.liusongboyy.chatdata.channel.ask.AskResult;
import com.liusongboyy.chatdata.channel.identity.ChannelTokenService;
import com.liusongboyy.chatdata.channel.render.FeishuCardRenderer;
import com.liusongboyy.chatdata.channel.render.FeishuSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行一条订阅：查数 → 发卡片 → 记结果。
 *
 * <h2>推送用谁的令牌</h2>
 *
 * <p><b>订阅创建人的。</b>不是机器人自己的。
 *
 * <p>用机器人自己的令牌是越权：审计里看不出是谁要的数据，而且创建人被收回
 * 权限之后订阅还会继续发——那等于给了一个绕过权限的长期通道。
 * 用创建人的令牌，权限变化会自然生效。
 *
 * <h2>为什么失败也要发消息</h2>
 *
 * <p>「今天没收到」比「收到了报错」更难排查——用户不知道是被权限拒了、
 * 查空了、还是服务挂了。所以除 EMPTY 以外都发一条说明。
 * EMPTY 不发：用户配的是"每天发销售额"，当天确实没有销售额时发一条
 * "没有数据"反而是噪音。
 */
@Component
public class SubscriptionRunner {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionRunner.class);

    /** 订阅执行结果。**要落库**，排查「为什么今天没发」靠它 */
    public enum Status {
        /** 查到数据并发出 */
        OK,
        /** 权限被拒——通常是创建人被收回了某个域的权限 */
        DENIED,
        /** 查到 0 行。不发消息，但记下来 */
        EMPTY,
        /** 其他错误（IAM 不可用、飞书发送失败） */
        ERROR
    }

    public record Outcome(Status status, int rowCount, String message) {
    }

    private final JdbcTemplate jdbc;
    private final ChannelTokenService tokens;
    private final AskClient ask;
    private final FeishuCardRenderer renderer;
    private final FeishuSender sender;

    public SubscriptionRunner(JdbcTemplate jdbc, ChannelTokenService tokens,
                              AskClient ask, FeishuCardRenderer renderer,
                              FeishuSender sender) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.ask = ask;
        this.renderer = renderer;
        this.sender = sender;
    }

    /** 一条订阅的只读视图。 */
    public record Subscription(long id, String channel, String chatKey, String username,
                               String question, String assistantId, String cron) {
    }

    /** 列出启用中的订阅。 */
    public List<Subscription> enabledSubscriptions() {
        return jdbc.query("""
                        SELECT id, channel, chat_key, username, question, assistant_id, cron
                        FROM channel_subscription WHERE enabled = 1
                        """,
                (rs, i) -> new Subscription(
                        rs.getLong("id"),
                        rs.getString("channel"),
                        rs.getString("chat_key"),
                        rs.getString("username"),
                        rs.getString("question"),
                        rs.getString("assistant_id"),
                        rs.getString("cron")));
    }

    /**
     * cron 是否在该时间点触发。
     *
     * <p>用 Spring 的 {@link CronExpression} 而不是自己算：cron 的边界
     * （月末、闰年、跨时区）自己算一定会错。
     *
     * <p><b>非法 cron 返回 false 而不是抛异常</b>——一条配错的订阅不该让
     * 整个调度器停摆，其他订阅还要照常跑。
     */
    /**
     * cron 是否在该时间点触发。
     *
     * <p><b>必须传 ZonedDateTime 给 {@link CronExpression#next}，不能传 Instant。</b>
     * 实测：传 Instant 会抛 {@code UnsupportedTemporalTypeException: Unsupported
     * field: DayOfWeek}——因为 Instant 没有"星期几"这个概念。异常被 catch 成
     * false 之后，表现是「订阅永远不触发」，而且一点日志都没有。
     *
     * <p><b>cron 按本地时区解释。</b>用户配「每天 9 点」指的是他自己的 9 点。
     *
     * <p>非法 cron 返回 false 而不是抛异常——一条配错的订阅不该让整个调度器
     * 停摆，其他订阅还要照常跑。
     */
    public static boolean firesAt(String cron, Instant at, ZoneId zone) {
        try {
            ZonedDateTime minuteStart = at.atZone(zone).truncatedTo(ChronoUnit.MINUTES);
            // next() 是**严格大于**传入时刻的：
            //   next(09:00:00) -> 明天 09:00   ← 今天这次被跳过
            //   next(08:59:59) -> 今天 09:00   ← 对
            // 所以从"本分钟起点减一秒"往前推，再比对是否落在本分钟内
            ZonedDateTime next = CronExpression.parse(cron).next(minuteStart.minusSeconds(1));
            if (next == null) {
                return false;
            }
            return next.truncatedTo(ChronoUnit.MINUTES).equals(minuteStart);
        } catch (RuntimeException e) {
            log.debug("cron「{}」解析或计算失败：{}", cron, e.getMessage());
            return false;
        }
    }

    /** 按系统时区判定。生产与测试都用这个重载，避免两处时区不一致。 */
    public static boolean firesAt(String cron, Instant at) {
        return firesAt(cron, at, ZoneId.systemDefault());
    }

    /**
     * 执行一条订阅。
     *
     * <p>不抛异常——单条失败只影响它自己。
     */
    public Outcome run(Subscription sub) {
        Instant started = Instant.now();
        Outcome outcome;
        try {
            String token = tokens.tokenFor(sub.channel(), sub.username());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("question", sub.question());
            if (sub.assistantId() != null && !sub.assistantId().isBlank()) {
                body.put("assistantId", sub.assistantId());
            }
            AskResult result = ask.ask(token, body);

            if (result.empty()) {
                outcome = new Outcome(Status.EMPTY, 0, "没有符合条件的数据");
            } else {
                String card = renderer.subscriptionCard(sub.question(), result);
                sender.sendCard(receiveIdType(sub.chatKey()), sub.chatKey(), card);
                outcome = new Outcome(Status.OK, result.rowCount(), null);
            }
        } catch (ChannelTokenService.TokenUnavailableException e) {
            // 创建人被收回权限或账号停用。**这是最需要说清楚的失败**——
            // 否则用户只知道"今天没收到"，不知道是自己的权限变了
            outcome = new Outcome(Status.DENIED, 0, e.getMessage());
        } catch (AskClient.AskFailedException e) {
            outcome = new Outcome(Status.ERROR, 0, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("订阅 {} 执行失败", sub.id(), e);
            outcome = new Outcome(Status.ERROR, 0, e.getMessage());
        }

        record(sub, started, outcome);
        notifyOnFailure(sub, outcome);
        return outcome;
    }

    /**
     * 失败时给订阅人发一条说明。
     *
     * <p>EMPTY 不发（问的是"每天发销售额"，当天真没有时发"没有数据"是噪音）；
     * 其余失败都发——沉默的失败比报错难查得多。
     */
    private void notifyOnFailure(Subscription sub, Outcome outcome) {
        if (outcome.status() == Status.OK || outcome.status() == Status.EMPTY) {
            return;
        }
        try {
            String card = renderer.subscriptionFailureCard(sub.question(), outcome.message());
            sender.sendCard(receiveIdType(sub.chatKey()), sub.chatKey(), card);
        } catch (RuntimeException e) {
            // 连失败通知都发不出去，只能记日志
            log.warn("订阅 {} 的失败通知发送失败：{}", sub.id(), e.getMessage());
        }
    }

    private void record(Subscription sub, Instant started, Outcome outcome) {
        try {
            jdbc.update("""
                            INSERT INTO channel_subscription_run
                              (subscription_id, started_at, finished_at, status, row_count, message)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                    sub.id(), Timestamp.from(started), Timestamp.from(Instant.now()),
                    outcome.status().name(), outcome.rowCount(),
                    truncate(outcome.message(), 500));
            jdbc.update("""
                            UPDATE channel_subscription
                            SET last_run_at = ?, last_status = ?, last_error = ?
                            WHERE id = ?
                            """,
                    Timestamp.from(started), outcome.status().name(),
                    truncate(outcome.message(), 500), sub.id());
        } catch (RuntimeException e) {
            // 记录失败不该影响已经发出的消息
            log.warn("记录订阅 {} 的执行结果失败：{}", sub.id(), e.getMessage());
        }
    }

    /** 群聊用 chat_id，私聊用 open_id。前缀区分，避免两种 id 混用 */
    static String receiveIdType(String chatKey) {
        return chatKey != null && chatKey.startsWith("oc_") ? "chat_id" : "open_id";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 给管理台用的视图：最近 N 次执行。 */
    public List<Map<String, Object>> recentRuns(long subscriptionId, int limit) {
        return jdbc.queryForList("""
                        SELECT started_at, finished_at, status, row_count, message
                        FROM channel_subscription_run
                        WHERE subscription_id = ?
                        ORDER BY started_at DESC LIMIT ?
                        """,
                subscriptionId, limit);
    }
}


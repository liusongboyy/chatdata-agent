package com.liusongboyy.chatdata.channel.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 每分钟检查一次有没有订阅该触发。
 *
 * <h2>为什么是"轮询 + 判断"而不是给每条订阅建一个 cron 任务</h2>
 *
 * <p>{@code TaskScheduler} 的动态注册在订阅增删时要同步，而同步漏掉的表现是
 * <b>「新建的订阅不生效，要重启服务」</b>——那是最难被发现的失败之一。
 *
 * <p>轮询的代价是 cron 精度只有 1 分钟。对「每天 9 点发销售额」这类需求，
 * 1 分钟误差完全可以接受，换来的是"改完配置立刻生效"。
 *
 * <h2>为什么要截断到分钟</h2>
 *
 * <p>调度器每 30 秒跑一次，不截断的话同一个 cron 会在同一分钟内命中两次，
 * 用户收到两条一样的卡片。
 */
@Component
public class SubscriptionScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionScheduler.class);

    private final SubscriptionRunner runner;

    /** 订阅推送的开关。默认开，但飞书没配时 runner 自己会失败并记日志 */
    @org.springframework.beans.factory.annotation.Value(
            "${channel.subscription.enabled:true}")
    private boolean enabled;

    /** 上一轮的时间点（截到分钟），用于避免同一分钟重复触发 */
    private volatile Instant lastTick;

    public SubscriptionScheduler(SubscriptionRunner runner) {
        this.runner = runner;
    }

    @Scheduled(fixedDelayString = "${channel.subscription.check-interval-ms:30000}",
            initialDelayString = "${channel.subscription.initial-delay-ms:60000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        // 截到分钟：不截断的话 30 秒一次的轮询会让同一个 cron 命中两次
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        if (now.equals(lastTick)) {
            return;
        }
        lastTick = now;

        var subscriptions = runner.enabledSubscriptions();
        if (subscriptions.isEmpty()) {
            return;
        }

        ZoneId zone = ZoneId.systemDefault();
        for (var sub : subscriptions) {
            if (!SubscriptionRunner.firesAt(sub.cron(), now)) {
                continue;
            }
            try {
                var outcome = runner.run(sub);
                log.info("订阅 {} 执行完成：{}（{} 行）", sub.id(), outcome.status(), outcome.rowCount());
            } catch (RuntimeException e) {
                // run() 内部已兜底，这里再保一层：**一条订阅的异常不能让整轮停摆**
                log.error("订阅 {} 执行时抛出未预期异常", sub.id(), e);
            }
        }
        log.debug("订阅检查完成：{} 条启用中的订阅，时区 {}", subscriptions.size(), zone);
    }
}

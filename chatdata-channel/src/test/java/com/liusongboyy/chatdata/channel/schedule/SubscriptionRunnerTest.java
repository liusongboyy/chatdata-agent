package com.liusongboyy.chatdata.channel.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订阅调度。
 *
 * <p>测的是「该触发的时候触发、不该触发的时候不触发」——
 * 漏触发是用户收不到，重复触发是收到两条一样的卡片，两个都得防。
 */
class SubscriptionRunnerTest {

    /**
     * 本地时区的某个时刻。
     *
     * <p><b>cron 按本地时区解释</b>——用户配「每天 9 点」指的是他自己的 9 点，
     * 不是 UTC 的 9 点。测试也必须按本地时区构造，否则测的是错的语义。
     */
    private static Instant local(String isoLocal) {
        return LocalDateTime.parse(isoLocal).atZone(ZoneId.systemDefault()).toInstant();
    }

    private static final Instant T = local("2026-11-03T09:00:00");

    @Test
    @DisplayName("★ 每天 9 点：9:00 触发")
    void dailyNineHitsAtNine() {
        assertThat(SubscriptionRunner.firesAt("0 0 9 * * *", T)).isTrue();
    }

    @Test
    @DisplayName("★ 每天 9 点：9:01 不触发")
    void dailyNineMissesAtNineOhOne() {
        assertThat(SubscriptionRunner.firesAt("0 0 9 * * *", T.plusSeconds(60))).isFalse();
    }

    @Test
    @DisplayName("★ 每分钟：任何分钟都触发")
    void everyMinuteAlwaysHits() {
        assertThat(SubscriptionRunner.firesAt("0 * * * * *", T)).isTrue();
        assertThat(SubscriptionRunner.firesAt("0 * * * * *", T.plusSeconds(3600))).isTrue();
    }

    @Test
    @DisplayName("★ 非法 cron 返回 false，不抛异常")
    void invalidCronDoesNotThrow() {
        // 一条配错的订阅不该让整个调度器停摆——其他订阅还要照常跑
        assertThat(SubscriptionRunner.firesAt("not a cron", T)).isFalse();
        assertThat(SubscriptionRunner.firesAt("", T)).isFalse();
        assertThat(SubscriptionRunner.firesAt(null, T)).isFalse();
    }

    @Test
    @DisplayName("★ 群聊用 chat_id，私聊用 open_id")
    void receiveIdTypeByKey() {
        // 飞书群 id 以 oc_ 开头；用错类型会发不出去
        assertThat(SubscriptionRunner.receiveIdType("oc_abc123")).isEqualTo("chat_id");
        assertThat(SubscriptionRunner.receiveIdType("ou_user123")).isEqualTo("open_id");
        assertThat(SubscriptionRunner.receiveIdType("abc")).isEqualTo("open_id");
    }

    @Test
    @DisplayName("★ 必须按 ZonedDateTime 算，传 Instant 会让订阅永远不触发")
    void zonedDateTimeIsRequired() {
        // 实测：CronExpression.next(Instant) 会抛
        // UnsupportedTemporalTypeException: Unsupported field: DayOfWeek
        // 而这个异常被 catch 成 false 之后，表现是「订阅永远不触发」且无日志。
        //
        // 这个测试保证实现走的是 atZone(zone) 那条路：如果哪天有人把
        // at.atZone(zone) 改成直接传 at，这里会立刻红。
        ZoneId utc = ZoneId.of("UTC");
        Instant nineUtc = LocalDateTime.parse("2026-11-03T09:00:00")
                .atZone(utc).toInstant();
        assertThat(SubscriptionRunner.firesAt("0 0 9 * * *", nineUtc, utc)).isTrue();
    }

    @Test
    @DisplayName("工作日 9 点：周一到周五触发，周六不触发")
    void weekdayCron() {
        // 2026-11-03 是周二
        assertThat(SubscriptionRunner.firesAt("0 0 9 * * MON-FRI", T)).isTrue();
        // 2026-11-07 是周六
        assertThat(SubscriptionRunner.firesAt("0 0 9 * * MON-FRI",
                local("2026-11-07T09:00:00"))).isFalse();
    }
}

package com.liusongboyy.chatdata.channel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 渠道服务的部署配置。
 *
 * <p><b>密钥没有默认值。</b>缺失时启动失败，而不是用一个开发默认值悄悄跑起来——
 * 与 IAM 的 {@code PasswordCipher}、{@code seedClients} 同一取舍。
 */
@ConfigurationProperties(prefix = "channel")
public class ChannelProperties {

    private final Iam iam = new Iam();
    private final Ask ask = new Ask();
    private final Queue queue = new Queue();
    private final Dedup dedup = new Dedup();
    private final Session session = new Session();

    /**
     * 绑定页的对外地址（浏览器访问的网关地址）。
     *
     * <p>用户在飞书里点「立即绑定」，链接要指向<b>浏览器能打开</b>的地址，
     * 而不是渠道服务自己的内网地址——那在用户手机上打不开。
     */
    private String bindBaseUrl = "http://127.0.0.1:8080";

    public Iam getIam() {
        return iam;
    }

    public Ask getAsk() {
        return ask;
    }

    public Queue getQueue() {
        return queue;
    }

    public Dedup getDedup() {
        return dedup;
    }

    public Session getSession() {
        return session;
    }

    public String getBindBaseUrl() {
        return bindBaseUrl;
    }

    public void setBindBaseUrl(String bindBaseUrl) {
        this.bindBaseUrl = bindBaseUrl;
    }

    /** IAM：换「代表某用户」的令牌。 */
    public static class Iam {

        private String baseUrl = "http://127.0.0.1:9000";

        /** 与 IAM 里注册的 channel 客户端一致。 */
        private String clientId = "channel-bot";

        private String clientSecret;

        /**
         * 令牌提前刷新量。
         *
         * <p>令牌 TTL 只有 5 分钟。卡在到期那一刻去换，会因为时钟偏差与网络往返
         * 换到一张已经过期的令牌——请求随即 401，而错误信息看起来像"权限问题"。
         */
        private Duration refreshAhead = Duration.ofSeconds(30);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public Duration getRefreshAhead() {
            return refreshAhead;
        }

        public void setRefreshAhead(Duration refreshAhead) {
            this.refreshAhead = refreshAhead;
        }
    }

    /** ask：问数接口。 */
    public static class Ask {

        private String baseUrl = "http://127.0.0.1:8081";

        /**
         * 单次问数的超时。
         *
         * <p>必须大于 ask 自己的 SSE 超时（180 秒），否则我们在 ask 还在算的时候先断开，
         * 用户看到"超时"而实际查询成功了——那会让人反复重试，把负载放大。
         */
        private Duration timeout = Duration.ofSeconds(200);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    /**
     * 有界队列。
     *
     * <p>飞书要求 3 秒内返回 200。问数要 5–15 秒，所以必须异步。
     * 队列有界是为了「宁可明确拒绝，也不无限堆积」——堆积到 OOM 时，
     * 所有请求一起失败，包括本来能成功的。
     */
    public static class Queue {

        private int capacity = 200;

        private int workers = 8;

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getWorkers() {
            return workers;
        }

        public void setWorkers(int workers) {
            this.workers = workers;
        }
    }

    /** 入站去重。 */
    public static class Dedup {

        /**
         * 去重记录保留时长。
         *
         * <p><b>必须 ≥ 6 小时。</b>飞书的重推间隔是 15 秒 → 5 分钟 → 1 小时 → 6 小时，
         * 最晚一次在 6 小时后。窗口短于它，那条重推会被当成新消息——
         * 用户会收到一条 6 小时前问题的重复答案。
         *
         * <p>默认 24 小时留余量。用表不用 Redis：6 小时已超出「缓存」语义，
         * 而丢失去重记录会导致重复回答。
         */
        private Duration retention = Duration.ofHours(24);

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            this.retention = retention;
        }
    }

    /** 会话映射。 */
    public static class Session {

        /**
         * 空闲多久后开新会话。
         *
         * <p>用户说「那上个月呢」时，超过这个时长的上文已经不在了，
         * 硬接上去反而让模型困惑。
         */
        private Duration idle = Duration.ofMinutes(60);

        public Duration getIdle() {
            return idle;
        }

        public void setIdle(Duration idle) {
            this.idle = idle;
        }
    }
}

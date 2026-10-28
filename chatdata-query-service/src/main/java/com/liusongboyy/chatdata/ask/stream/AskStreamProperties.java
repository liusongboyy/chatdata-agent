package com.liusongboyy.chatdata.ask.stream;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 问数流式接口的可调参数。 */
@ConfigurationProperties(prefix = "chatdata.ask.stream")
public class AskStreamProperties {

    /**
     * SSE 连接的最长存活时间（毫秒）。
     *
     * <p>问数是一次多步 LLM 调用，正常 5–15 秒，但长问题或大结果集会明显更久。
     * 超时后连接被关闭，客户端应能收到超时事件而非静默断开。
     */
    private long timeoutMs = 120_000L;

    /**
     * 心跳间隔（毫秒）。
     *
     * <p>心跳的唯一目的是防止中间的网关/反向代理把"长时间没有数据"的连接掐掉。
     * 应显著小于链路中最短的空闲超时（常见为 60 秒）。
     */
    private long heartbeatIntervalMs = 10_000L;

    /** 执行问数的工作线程数上限。每个占用一个线程直到问数返回。 */
    private int workerThreads = 8;

    /** 等待队列长度。队列满时拒绝新请求，而不是无限堆积。 */
    private int queueCapacity = 32;

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public long getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public void setHeartbeatIntervalMs(long heartbeatIntervalMs) {
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }
}

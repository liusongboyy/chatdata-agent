package com.liusongboyy.chatdata.ask.stream;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableConfigurationProperties(AskStreamProperties.class)
public class AskStreamConfig {

    /**
     * 执行问数的工作线程池。
     *
     * <p>刻意**不使用** {@code CallerRunsPolicy}：SSE 的语义是"立刻返回 emitter、随后异步推送"，
     * 若队列满时让请求线程直接执行，就会在返回 emitter 之前把整件事做完，
     * 连接层表现为"卡住 N 秒后一次性返回"，与流式的目的相悖。
     * 这里改用拒绝策略，由控制层返回明确的服务繁忙响应。
     */
    @Bean("askWorkerExecutor")
    public AsyncTaskExecutor askWorkerExecutor(AskStreamProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getWorkerThreads());
        executor.setMaxPoolSize(properties.getWorkerThreads());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("ask-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 心跳调度器。单线程足够——心跳只是往连接里写一个空事件。
     */
    @Bean(name = "askHeartbeatScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService askHeartbeatScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ask-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }
}

package com.liusongboyy.chatdata.ask.stream;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;

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
     *
     * <h2>为什么必须传 SecurityContext</h2>
     *
     * <p>{@code SecurityContextHolder} 默认**不跨线程**。问数的权限判定跑在这个池子里，
     * 而角色只有请求线程的 SecurityContext 里才有——不传过去的话，
     * 每个异步任务看到的都是"未认证主体"，{@code roles} 恒为空。
     *
     * <p>失败形态极具误导性：日志里 {@code userId=zhangsan}（从入参带过来的）
     * 配着 {@code roles=[]}，看起来像"这个用户确实没角色"，
     * 于是会去查 IAM 的角色配置，而真正的原因是线程切换丢了上下文。
     *
     * <p>用 Spring Security 自带的 {@link DelegatingSecurityContextRunnable}，
     * 它会在提交时捕获上下文、执行完再清理，不会把线程池复用成"上一个用户的上下文"。
     */
    @Bean("askWorkerExecutor")
    public AsyncTaskExecutor askWorkerExecutor(AskStreamProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getWorkerThreads());
        executor.setMaxPoolSize(properties.getWorkerThreads());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("ask-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setTaskDecorator(securityContextPropagating());
        executor.initialize();
        return executor;
    }

    /** 让提交到工作线程池的任务带上当前线程的 SecurityContext。 */
    private static TaskDecorator securityContextPropagating() {
        return runnable -> new DelegatingSecurityContextRunnable(runnable);
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

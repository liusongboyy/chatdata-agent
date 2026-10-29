package com.liusongboyy.chatdata.channel.inbound;

import com.liusongboyy.chatdata.channel.config.ChannelProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 入站消息的有界队列。
 *
 * <h2>为什么必须有界</h2>
 *
 * <p>飞书要求 3 秒内返回 200，而问数要 5–15 秒——所以必须异步。
 * 但异步不能是"无界地收下"：堆积到 OOM 时所有请求一起失败，
 * 包括那些本来能成功的。有界 + 明确拒绝，至少让一部分人得到答案。
 *
 * <h2>队列满时仍然回 200</h2>
 *
 * <p>这看着反直觉，但正确：返回非 200 会触发飞书重推 4 次
 * （15 秒后、5 分钟后、1 小时后、6 小时后），那些重推同样会失败，
 * 只会把队列压得更死。正确做法是吞下 200，然后<b>异步告诉用户</b>「现在忙」。
 * 见 {@link #offer} 的返回语义。
 */
@Component
public class InboundQueue {

    private static final Logger log = LoggerFactory.getLogger(InboundQueue.class);

    private final BlockingQueue<InboundMessage> queue;
    private final ExecutorService workers;
    private final InboundDedup dedup;

    public InboundQueue(ChannelProperties properties, InboundDedup dedup) {
        this.queue = new ArrayBlockingQueue<>(properties.getQueue().getCapacity());
        this.dedup = dedup;
        int n = Math.max(1, properties.getQueue().getWorkers());
        this.workers = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "channel-worker");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < n; i++) {
            workers.submit(this::loop);
        }
        log.info("渠道工作线程已启动：{} 个，队列容量 {}", n, properties.getQueue().getCapacity());
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            InboundMessage msg;
            try {
                msg = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (msg == null) {
                continue;
            }
            try {
                handler.accept(msg);
            } catch (RuntimeException e) {
                // 单个消息处理失败不能拖垮工作线程——那会让整条渠道停摆。
                // 具体错误处理在 handler 里（它会回错误卡片给用户）
                log.error("处理入站消息失败：channel={} messageId={}",
                        msg.channel(), msg.messageId(), e);
            }
        }
    }

    private volatile Consumer<InboundMessage> handler = msg -> {
    };

    /** 装配处理函数。工作线程可能在启动瞬间就取到消息，故用 volatile。 */
    public void setHandler(Consumer<InboundMessage> handler) {
        this.handler = handler;
    }

    /**
     * 入队。
     *
     * @return {@code true} 已入队；{@code false} 队列满——调用方应回 200
     *         并异步告知用户「当前繁忙」
     */
    public boolean offer(InboundMessage msg) {
        boolean accepted = queue.offer(msg);
        if (!accepted) {
            // 关键：队列入不进去就**释放去重记录**。
            // 不释放的话，用户重发或飞书重推会被当成重复而永远得不到回答
            dedup.release(msg.channel(), msg.messageId());
            log.warn("渠道队列已满，丢弃：channel={} messageId={}", msg.channel(), msg.messageId());
        }
        return accepted;
    }

    /** 当前积压。健康检查与告警用。 */
    public int backlog() {
        return queue.size();
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }
}

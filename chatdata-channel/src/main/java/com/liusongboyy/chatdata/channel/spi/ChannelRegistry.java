package com.liusongboyy.chatdata.channel.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 按渠道标识查实现。
 *
 * <h2>为什么要一个注册表，而不是注入具体实现</h2>
 *
 * <p>{@code MessageHandler} 处理的是<b>一条已经进来的消息</b>，它知道自己属于哪个渠道
 * （{@code msg.channel()}）。而它不该认识任何具体渠道的类型——
 * 否则接第二个渠道时又要改它，那就说明抽象没做成。
 *
 * <p>构造器注入所有实现，按 {@link ChannelRenderer#channel()} 建索引。
 * 实现类自己声明渠道标识，注册表不维护映射表——两处各写一遍迟早对不上。
 *
 * <h2>找不到实现时返回 Optional 而不是抛异常</h2>
 *
 * <p>"这个渠道没有实现"是配置问题（比如飞书没启用），不是程序错误。
 * 调用方要能区分它和"实现内部出错"，并给用户不同的提示。
 */
@Component
public class ChannelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChannelRegistry.class);

    private final Map<String, ChannelRenderer> renderers = new HashMap<>();
    private final Map<String, ChannelSender> senders = new HashMap<>();

    public ChannelRegistry(List<ChannelRenderer> rendererList, List<ChannelSender> senderList) {
        for (ChannelRenderer r : rendererList) {
            ChannelRenderer prev = renderers.put(r.channel(), r);
            if (prev != null) {
                // 两个实现声明同一个渠道 = 配置错了。启动时就该发现，
                // 而不是等到某条消息发出去、用户看到两个不同样式的卡片
                throw new IllegalStateException(
                        "渠道「" + r.channel() + "」有两个渲染器实现：" 
                                + prev.getClass().getName() + " 与 " + r.getClass().getName());
            }
        }
        for (ChannelSender s : senderList) {
            ChannelSender prev = senders.put(s.channel(), s);
            if (prev != null) {
                throw new IllegalStateException(
                        "渠道「" + s.channel() + "」有两个发送器实现：" 
                                + prev.getClass().getName() + " 与 " + s.getClass().getName());
            }
        }
        log.info("已注册渠道：渲染器 {} 个、发送器 {} 个，渠道={}",
                renderers.size(), senders.size(), renderers.keySet());
    }

    public Optional<ChannelRenderer> renderer(String channel) {
        return Optional.ofNullable(channel == null ? null : renderers.get(channel));
    }

    public Optional<ChannelSender> sender(String channel) {
        return Optional.ofNullable(channel == null ? null : senders.get(channel));
    }

    /** 已注册的渠道标识。诊断用。 */
    public java.util.Set<String> channels() {
        return java.util.Set.copyOf(renderers.keySet());
    }
}

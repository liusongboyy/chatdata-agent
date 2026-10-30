package com.liusongboyy.chatdata.mesh.a2a;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.liusongboyy.chatdata.mesh.config.MeshProperties;
import io.a2a.spec.AgentCard;
import io.agentscope.core.a2a.server.registry.AgentRegistry;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import io.agentscope.core.nacos.a2a.registry.NacosAgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 把 AgentCard 注册到 Nacos。
 *
 * <h2>为什么用 {@code ApplicationRunner} 而不是 {@code @PostConstruct}</h2>
 *
 * <p>注册要等 Web 端口真正监听之后再发生——否则其他 agent 拿到地址立刻调，
 * 会撞上「连接被拒绝」。{@code ApplicationRunner} 在上下文就绪、端口已监听之后执行。
 *
 * <h2>注册失败不阻断启动</h2>
 *
 * <p>Nacos 挂了不应该让 A2A 端点也起不来：其他 agent 仍可以按已知地址直接调，
 * 只是发现不了新的地址。所以这里记 ERROR 但继续。
 *
 * <p>反过来（注册成功但端点没起）更糟——那会让别人拿到一个连不上的地址，
 * 而且因为注册成功了，排查时不会先怀疑它。
 */
@Configuration
@ConditionalOnProperty(prefix = "mesh.nacos", name = "enabled", havingValue = "true")
public class NacosRegistrationConfig {

    private static final Logger log = LoggerFactory.getLogger(NacosRegistrationConfig.class);

    @Bean
    public ApplicationRunner registerAgentCard(MeshProperties properties,
                                               AgentCardFactory cardFactory) {
        return args -> {
            MeshProperties.Nacos nacos = properties.getNacos();
            MeshProperties.A2a a2a = properties.getA2a();

            Properties p = new Properties();
            p.put(PropertyKeyConst.SERVER_ADDR, nacos.getServerAddr());
            p.put(PropertyKeyConst.NAMESPACE, nacos.getNamespace());

            AgentCard card = cardFactory.build();
            // 签名：(transport, host, port, path, supportTls, extra)
            // 协议放在最后的 Map 里，不是独立参数——凭印象会写错
            TransportProperties tp = new TransportProperties(
                    "JSONRPC", a2a.getAdvertisedHost(), a2a.getAdvertisedPort(),
                    a2a.getPath(), false, Map.of("protocol", "http"));

            try {
                AgentRegistry registry = NacosAgentRegistry.builder(p).build();
                registry.register(card, List.of(tp));
                log.info("AgentCard 已注册到 Nacos：name={} url={} nacos={}",
                        card.name(), card.url(), nacos.getServerAddr());
            } catch (Exception e) {
                // 不阻断启动：端点仍能按已知地址被直接调用，只是发现不了
                log.error("注册到 Nacos 失败（A2A 端点仍可用，但其他 agent 发现不了）：{}",
                        e.getMessage(), e);
            }
        };
    }
}

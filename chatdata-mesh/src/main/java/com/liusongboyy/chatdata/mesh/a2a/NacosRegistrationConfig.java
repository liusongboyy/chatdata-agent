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

            // 注册前先自检：注册出去的地址必须真的能调通。
            //
            // 为什么要有这一步：agentscope 的 A2A 端点是 @RequestMapping("/")，
            // 路径写死在注解里不可配；而 AgentCard 的 url 由我们自己拼。
            // 两者不一致时的表现是「Nacos 里能发现、按地址调 404」——
            // 发现方会以为自己拼错了，而真实原因是注册方写了错的路径。
            // 在这里拦住，比让别人在几十个 agent 里排查便宜得多。
            verifySelfReachable(card.url());

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

    /**
     * 自检：注册出去的 A2A 地址真能调通吗。
     *
     * <p>用 {@code GET} 探测是不够的（端点只接受 POST），所以发一个
     * JSON-RPC 的 {@code message/get} 之类无副作用的请求太难构造——
     * 退一步：只要**不是 404** 就说明路径对了。405/400 都算通。
     *
     * <p>失败只记 WARN 不阻断：可能是注册时端口还没完全就绪（启动顺序问题），
     * 也可能是这张卡是给别人手工填的。真正的判断留给调用方。
     */
    private void verifySelfReachable(String url) {
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"id\":\"self-check\","
                                    + "\"method\":\"ping\",\"params\":{}}"))
                    .build();
            int code = java.net.http.HttpClient.newHttpClient()
                    .send(req, java.net.http.HttpResponse.BodyHandlers.discarding())
                    .statusCode();
            if (code == 404) {
                log.error("自检失败：注册的地址 {} 返回 404。"
                        + "多半是 mesh.a2a.path 与 agentscope starter 的端点路径不一致"
                        + "（端点是 @RequestMapping(\"/\")，path 必须配 /）", url);
            } else {
                log.info("自检通过：{} 返回 {}（非 404 即可）", url, code);
            }
        } catch (Exception e) {
            log.warn("自检探测失败（可能是启动顺序问题，不影响注册）：{} {}", url, e.getMessage());
        }
    }
}

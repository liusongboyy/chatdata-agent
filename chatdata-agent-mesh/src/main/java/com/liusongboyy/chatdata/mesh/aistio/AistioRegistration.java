package com.liusongboyy.chatdata.mesh.aistio;

import com.liusongboyy.chatdata.mesh.a2a.AskAgentRunner;
import com.liusongboyy.chatdata.mesh.ask.AskClient;
import com.liusongboyy.chatdata.mesh.ask.AskResult;
import com.liusongboyy.chatdata.mesh.config.MeshProperties;
import com.liusongboyy.chatdata.mesh.identity.ProxyTargetResolver;
import com.liusongboyy.chatdata.mesh.identity.ProxyTokenService;
import io.agentscope.extensions.aistio.Aistio;
import io.agentscope.extensions.aistio.AistioConfig;
import io.agentscope.extensions.aistio.SessionBridge;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 把 mesh 接入 agentscope service 的控制面。
 *
 * <h2>它和 Nacos 是两条独立通道</h2>
 *
 * <table>
 *   <tr><th>通道</th><th>作用</th></tr>
 *   <tr><td>Nacos + A2A</td><td>其他 agent 发现并调用</td></tr>
 *   <tr><td>aistio</td><td>agentscope service 管理台可见、可编排</td></tr>
 * </table>
 *
 * <p>两者互不替代。关掉一个不影响另一个。
 *
 * <h2>注册流程</h2>
 *
 * <p>aistio 先走 HTTP 自注册拿到 {@code agentId}/{@code bindingId}/{@code generation}，
 * 再用这些连 gRPC 长连接。所以 {@code startHttpRegister} 必须开，
 * 且 {@code controlPlane}（gRPC）与 {@code controlPlaneHttp} 指向同一个地址——
 * 你的 control 是 Go 服务，HTTP 与 gRPC 同端口多路复用。
 *
 * <h2>失败不阻断启动</h2>
 *
 * <p>控制面不可达时 A2A 端点照样能用，只是管理台看不到。
 * 反过来（注册成功但端点没起）更糟——那会让管理台展示一个调不通的 agent。
 */
@Component
public class AistioRegistration {

    private static final Logger log = LoggerFactory.getLogger(AistioRegistration.class);

    private final MeshProperties properties;
    private final ProxyTargetResolver resolver;
    private final ProxyTokenService tokens;
    private final AskClient ask;

    private SessionBridge bridge;
    private MeshTarget target;

    public AistioRegistration(MeshProperties properties, ProxyTargetResolver resolver,
                              ProxyTokenService tokens, AskClient ask) {
        this.properties = properties;
        this.resolver = resolver;
        this.tokens = tokens;
        this.ask = ask;
    }

    /**
     * 启动时接入控制面。
     *
     * <p>用 {@code ApplicationRunner} 而不是 {@code @PostConstruct}：
     * 契约 HTTP 端口要能绑定，而那时 Web 容器应已就绪。
     */
    @org.springframework.context.annotation.Bean
    org.springframework.boot.ApplicationRunner aistioRunner() {
        return args -> {
            if (!properties.getAistio().isEnabled()) {
                log.info("aistio 未启用（mesh.aistio.enabled=false）");
                return;
            }
            try {
                start();
            } catch (RuntimeException e) {
                // 不阻断：A2A 端点照样能用，只是管理台看不到
                log.error("接入 aistio 控制面失败（A2A 端点仍可用，但管理台看不到）："
                        + "{}", e.getMessage(), e);
            }
        };
    }

    private void start() {
        MeshProperties.Aistio cfg = properties.getAistio();

        // 目标对象：aistio 的 instrument 会把 adapter.attach 挂到它上面。
        // 这里用 mesh 自己——canHandle 认这个类型
        target = new MeshTarget();

        MeshFrameworkAdapter adapter = new MeshFrameworkAdapter(this::conversationTurn);

        AistioConfig aistioConfig = AistioConfig.builder(cfg.getAgentKey())
                // 同一个地址：control 是 Go 服务，HTTP 与 gRPC 同端口
                .controlPlane(cfg.getControlPlane())
                .controlPlaneHttp(cfg.getControlPlaneHttp())
                .internalToken(cfg.getInternalToken())
                .namespace(cfg.getNamespace())
                .tenant(cfg.getTenant())
                .enableEvents(cfg.isEnableEvents())
                // control 回调 agent 用（查上下文/历史）。会占一个本地端口
                .contractHttpPort(cfg.getContractHttpPort())
                // **必须显式传。** SDK 在 publicBaseUrl 为空时回落到
                // "http://localhost:<port>"——control 在容器里，那指向容器自己，
                // 表现为 Dashboard 里有 agent、Sessions 页永远空、对话能跑但
                // 上下文/历史查不到
                .publicBaseUrl(cfg.getPublicBaseUrl())
                // **必须显式开**。builder 里 startGrpc 默认 false——
                // 不设的话只做 HTTP 注册、不连 gRPC，管理台看不到这个 agent
                .startGrpc(true)
                // 开 gRPC 后必须先注册拿到 agentId/bindingId/generation，
                // 否则 AistioConfig 的构造校验会直接抛
                .startHttpRegister(true)
                .build();

        bridge = Aistio.instrument(target, aistioConfig, adapter);
        // 把两个地址都打出来：它们端口不同，出问题时第一个要确认的就是这个
        log.info("已接入 aistio 控制面：agentKey={} grpc={} http={} 回调={}",
                cfg.getAgentKey(), cfg.getControlPlane(), cfg.getControlPlaneHttp(),
                cfg.getPublicBaseUrl());
    }

    /**
     * 管理台发起的一轮对话。
     *
     * <h2>它没有「代表谁」的信息</h2>
     *
     * <p>A2A 有 {@code userId} 说明代表谁；aistio 的 {@code runConversationTurn}
     * 只有 sessionId 与文本。所以这里用配置的**默认被代理用户**——
     * 那是管理台场景下的合理约定：平台管理员在看这个 agent 的表现。
     *
     * <p>必须仍然走「换令牌 → 调 ask」的完整链路，不能绕过权限。否则管理台就成了
     * 一个能看全部数据的后门，而那正是我们花力气建授权表要防的事。
     */
    private String conversationTurn(String sessionId, String content) {
        MeshProperties.Aistio cfg = properties.getAistio();

        String proxySpec = "user:" + cfg.getDefaultAgentClient() + ":" + cfg.getDefaultUser();
        Optional<ProxyTargetResolver.ProxyTarget> parsed =
                resolver.resolve(proxySpec, cfg.getDefaultAgentClient());
        if (parsed.isEmpty()) {
            return "aistio 对话失败：默认被代理用户配置不合法（" + proxySpec + "）。"
                    + "检查 mesh.aistio.default-agent-client / default-user，"
                    + "以及该用户是否在 IAM 里授权了这个 agent。";
        }
        var target = parsed.get();

        try {
            String token = tokens.tokenFor(target.agentClient(), target.username());
            AskResult result = ask.ask(token, content, sessionId, false);
            // 返回可读摘要。管理台展示的是文本，结构化数据给不了它
            return summary(result);
        } catch (ProxyTokenService.TokenUnavailableException | AskClient.AskFailedException e) {
            return "aistio 对话失败：" + e.getMessage();
        }
    }

    private static String summary(AskResult r) {
        if (r.answer() != null && !r.answer().isBlank()) {
            return r.answer();
        }
        if (r.analysis() != null && r.analysis().narrative() != null) {
            return r.analysis().narrative();
        }
        if (!r.hasContent()) {
            return "没有符合条件的数据。";
        }
        if (r.singleValue()) {
            return r.columns().get(0) + "：" + r.rows().get(0).get(0);
        }
        StringBuilder sb = new StringBuilder("共 " + r.rowCount() + " 行（列：")
                .append(String.join("、", r.columns())).append("）。前几行：\n");
        int n = Math.min(r.rows().size(), 10);
        for (int i = 0; i < n; i++) {
            sb.append("· ").append(String.join(" | ", r.rows().get(i))).append('\n');
        }
        return sb.toString();
    }

    @PreDestroy
    void stop() {
        if (bridge != null) {
            try {
                bridge.close();
                log.info("已断开 aistio 控制面");
            } catch (Exception e) {
                log.warn("断开 aistio 时出错：{}", e.getMessage());
            }
        }
    }

    /** adapter.canHandle 认的对象。实现空接口即可。 */
    static final class MeshTarget implements MeshFrameworkAdapter.MeshInstrumented {
    }
}

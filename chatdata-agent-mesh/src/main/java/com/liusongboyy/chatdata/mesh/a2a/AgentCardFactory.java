package com.liusongboyy.chatdata.mesh.a2a;

import com.liusongboyy.chatdata.mesh.config.MeshProperties;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
import io.agentscope.spring.boot.a2a.properties.A2aAgentCardProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 构建注册到 Nacos 的 AgentCard。
 *
 * <h2>技能只从 starter 配置读</h2>
 *
 * <p>{@code agentscope.a2a.server.card.skills} 是唯一来源。这个类<b>不另写一份</b>。
 *
 * <p>曾经两处各写一遍，结果出现过「A2A 端点返回的 card 有技能、注册到 Nacos
 * 的没有」——因为只改了一处。两处同源的信息必须有唯一来源，
 * 否则迟早漂移，而漂移时的表现是「同一个 agent 在不同地方看到的能问范围不同」，
 * 调用方按其中一个决策就会出错。
 *
 * <h2>为什么要单独构建注册用的 card</h2>
 *
 * <p>starter 自带的 card 里 {@code url} 由 {@code server.address + server.port}
 * 推出，注册到 Nacos 时需要的是<b>其他机器可达的地址</b>
 * （{@code mesh.a2a.advertised-*}）。两者在容器/多网卡环境里不一样。
 */
@Component
public class AgentCardFactory {

    private final MeshProperties properties;

    /** starter 的 AgentCard 配置：技能的唯一来源。 */
    private final A2aAgentCardProperties starterCard;

    public AgentCardFactory(MeshProperties properties, A2aAgentCardProperties starterCard) {
        this.properties = properties;
        this.starterCard = starterCard;
    }

    /**
     * 校验「实际查询用的域」与「AgentCard 声明的技能」一致。
     *
     * <p>两者不一致时的表现是<b>答非所问但不报错</b>：AgentCard 说能问门店，
     * 实际查的是保险库，SQL 合法、数字真实，调用方无从察觉。
     *
     * <p>放在启动时检查——那时配置刚加载，改配置重启的成本最低。
     */
    public void verifyConsistency() {
        String configured = properties.getA2a().getDomainId();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "mesh.a2a.domain-id 未配置。不指定域时 ask 会落到第一个启用中的域，"
                            + "而那通常不是 AgentCard 声明的那个——结果是答非所问且不报错");
        }

        List<AgentSkill> declared = starterCard.getSkills();
        if (declared == null || declared.isEmpty()) {
            throw new IllegalStateException(
                    "agentscope.a2a.server.card.skills 为空——AgentCard 没声明任何能力，"
                            + "其他 agent 无法判断什么时候该用它");
        }

        // 约定：技能 id 形如 domain-<域id>。域与技能必须一一对应，
        // 否则调用方会按 A 的能力提问、得到 B 的数据
        String expectedSkillId = "domain-" + configured.trim();
        boolean matched = declared.stream().anyMatch(s -> expectedSkillId.equals(s.id()));
        if (!matched) {
            throw new IllegalStateException(
                    "mesh.a2a.domain-id=" + configured + " 对应的技能 " + expectedSkillId
                            + " 不在 AgentCard 声明的技能里（现有："
                            + declared.stream().map(AgentSkill::id).toList() + "）。"
                            + "域与技能必须一一对应，否则调用方会按 A 的能力问、得到 B 的数据");
        }
    }

    /** 构建注册到 Nacos 的那份 card。 */
    public AgentCard build() {
        MeshProperties.A2a a2a = properties.getA2a();
        // 注册出去的地址必须是**其他机器可达**的。starter 那份用 server.address
        // 推导，在容器/多网卡环境里通常是探测到的内网 IP，而 Nacos 里的其他 agent
        // 未必连得上——那表现为「注册成功但调用超时」
        String url = "http://" + a2a.getAdvertisedHost() + ":" + a2a.getAdvertisedPort()
                + a2a.getPath();

        return new AgentCard.Builder()
                .name(a2a.getAgentName())
                .description(a2a.getAgentDescription())
                .url(url)
                .version(a2a.getVersion())
                // A2A 0.3.0：当前 agentscope 2.0.4 对齐的协议版本
                .protocolVersion("0.3.0")
                .preferredTransport("JSONRPC")
                .capabilities(new AgentCapabilities.Builder()
                        // 问数是一次完整查询，不是增量生成——不需要逐字流式。
                        // 但 A2A 的 task 生命周期仍走 working → completed
                        .streaming(true)
                        .build())
                .defaultInputModes(List.of("text"))
                // data：结构化表格；text：一段可读摘要。
                // 两种都给——有些调用方只读文本
                .defaultOutputModes(List.of("data", "text"))
                .skills(starterCard.getSkills())
                .build();
    }
}

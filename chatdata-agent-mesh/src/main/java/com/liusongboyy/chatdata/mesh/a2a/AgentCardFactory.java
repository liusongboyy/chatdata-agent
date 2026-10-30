package com.liusongboyy.chatdata.mesh.a2a;

import com.liusongboyy.chatdata.mesh.config.MeshProperties;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 构建 AgentCard。
 *
 * <h2>技能按数据域拆，不是一个大技能</h2>
 *
 * <p>其他 agent 要靠 skills 判断「该不该用这个 agent」「用它查哪类数据」。
 * 一个大技能说「能问所有数据」，调用方只能盲试；按域拆开，它能按 tags 选。
 *
 * <h2>为什么写死在代码里而不是从库读</h2>
 *
 * <p>AgentCard 是**对外公开**的，注册到 Nacos 后所有能发现它的 agent 都能读。
 * 从库里读会把「系统里有哪些数据域」暴露出去——包括调用方无权访问的那些。
 * 那本身就是信息泄露。
 *
 * <p>代价是加数据域要改代码重启。这是可接受的运维成本。
 *
 * <h2>本期只暴露「门店分析」</h2>
 *
 * <p>先验证整条链路。skill 的数量不影响架构——第二个域要暴露时
 * 在 {@link #skills()} 里加一项即可。
 */
@Component
public class AgentCardFactory {

    /** 门店分析域。与 ask 里的 {@code ask_domain.id} 一致。 */
    static final String DOMAIN_STORE_ANALYSIS = "2300";

    private final MeshProperties properties;

    public AgentCardFactory(MeshProperties properties) {
        this.properties = properties;
    }

    public AgentCard build() {
        MeshProperties.A2a a2a = properties.getA2a();
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
                .skills(skills())
                .build();
    }

    /**
     * 技能清单。
     *
     * <p>描述写的是**能问什么**，不是**表结构是什么**：
     * 调用方是 agent，它需要判断「这个问题该不该交给它」，
     * 而不是知道底层有哪些列。
     */
    static List<AgentSkill> skills() {
        return List.of(
                new AgentSkill.Builder()
                        .id("domain-" + DOMAIN_STORE_ANALYSIS)
                        .name("门店分析")
                        .description("回答门店经营问题：销售额、订单数、库存、报废、"
                                + "员工打卡。支持按组织、时间、商品维度聚合，"
                                + "结果受调用方被代理人的数据权限约束。")
                        .tags(List.of("retail", "store", "sales", "inventory", "经营", "门店"))
                        .examples(List.of(
                                "上周各门店的订单金额",
                                "库存周转天数是多少",
                                "报废金额最高的门店",
                                "打卡次数合计"))
                        .build());
    }
}

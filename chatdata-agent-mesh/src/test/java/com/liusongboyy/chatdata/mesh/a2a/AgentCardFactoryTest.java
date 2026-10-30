package com.liusongboyy.chatdata.mesh.a2a;

import com.liusongboyy.chatdata.mesh.config.MeshProperties;
import io.a2a.spec.AgentCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentCard 的内容。
 *
 * <p>它不是给人看的介绍页，而是**其他 agent 用来判断「该不该用我」的依据**。
 * 所以断言的是那几件它会用到的信息：地址能不能连、有哪些技能、每个技能是什么。
 */
class AgentCardFactoryTest {

    private static AgentCard card() {
        MeshProperties props = new MeshProperties();
        props.getA2a().setAgentName("chatdata-ask");
        props.getA2a().setAdvertisedHost("10.0.0.5");
        props.getA2a().setAdvertisedPort(20001);
        return new AgentCardFactory(props).build();
    }

    @Test
    @DisplayName("★ url 用 advertised-host，不是写死的 127.0.0.1")
    void urlUsesAdvertisedHost() {
        // 写 127.0.0.1 的话，别的机器上的 agent 拿到地址后连的是它自己——
        // 表现为「注册成功但调用失败」
        assertThat(card().url()).isEqualTo("http://10.0.0.5:20001/a2a");
    }

    @Test
    @DisplayName("★ 技能按数据域拆，本期只暴露门店分析")
    void skillsArePerDomain() {
        AgentCard c = card();

        assertThat(c.skills()).hasSize(1);
        assertThat(c.skills().get(0).id()).isEqualTo("domain-2300");
        assertThat(c.skills().get(0).name()).isEqualTo("门店分析");
    }

    @Test
    @DisplayName("技能描述写「能问什么」，不是「表结构是什么」")
    void skillDescribesCapabilityNotSchema() {
        String desc = card().skills().get(0).description();

        // 调用方是 agent，它要判断「这个问题该不该交给它」
        assertThat(desc).contains("销售额").contains("库存");
        // 不该暴露底层表名/列名——那既没用也是信息泄露
        assertThat(desc).doesNotContain("f_order").doesNotContain("dim_org");
    }

    @Test
    @DisplayName("★ tags 要能用于检索（其他 agent 按它筛）")
    void skillsHaveSearchableTags() {
        assertThat(card().skills().get(0).tags())
                .contains("retail", "store", "门店");
    }

    @Test
    @DisplayName("示例问题让调用方理解能力边界")
    void skillsHaveExamples() {
        assertThat(card().skills().get(0).examples()).isNotEmpty();
    }

    @Test
    @DisplayName("输出模式要同时声明 data 与 text")
    void declaresBothOutputModes() {
        // data：结构化表格，给能解析 JSON 的调用方
        // text：可读摘要，给只读文本的调用方
        assertThat(card().defaultOutputModes()).containsExactlyInAnyOrder("data", "text");
    }

    @Test
    @DisplayName("协议版本与传输方式要和 agentscope 2.0.4 对齐")
    void protocolMatchesStarter() {
        AgentCard c = card();
        assertThat(c.protocolVersion()).isEqualTo("0.3.0");
        assertThat(c.preferredTransport()).isEqualTo("JSONRPC");
    }
}

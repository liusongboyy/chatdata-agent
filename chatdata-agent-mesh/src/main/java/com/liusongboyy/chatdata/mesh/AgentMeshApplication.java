package com.liusongboyy.chatdata.mesh;

import com.liusongboyy.chatdata.mesh.config.MeshProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Agent mesh：把问数暴露成一个 A2A agent。
 *
 * <h2>它是第三个入口</h2>
 *
 * <p>浏览器走网关 BFF、飞书走 channel、其他 agent 走 A2A。三条路径最后都汇到 ask，
 * 由它统一判权限——本模块**不判断权限**，只做「谁要问什么 → 拿他的令牌 → 翻译结果」。
 *
 * <h2>与 channel 的关系</h2>
 *
 * <p>两者同构（都实现了「代表用户换令牌」），但**不共享代码**：
 * 信任边界不同（飞书推送的人 vs 公司内 agent），失败话术也不同。
 * 重复几十行，代价远小于把两个信任边界混在一起。
 */
@SpringBootApplication
@EnableConfigurationProperties(MeshProperties.class)
public class AgentMeshApplication {

    public static void main(String[] args) {
        var ctx = SpringApplication.run(AgentMeshApplication.class, args);
        // 启动时校验「AgentCard 声明的技能」与「实际查询用的域」一致。
        // 不一致时是不报错的答非所问，越早发现越好
        ctx.getBean(com.liusongboyy.chatdata.mesh.a2a.AgentCardFactory.class).verifyConsistency();
    }
}

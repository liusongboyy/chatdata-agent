package com.liusongboyy.chatdata.ask;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.liusongboyy.chatdata")
@ConfigurationPropertiesScan("com.liusongboyy.chatdata")
@MapperScan({
        // 只扫 mapper 子包，不要扫上层包：MyBatis 的扫描器会把包内**所有接口**
        // 注册成 mapper，若 Service 接口与之同包，会产生同类型的重复 bean，
        // 报错为 "required a single bean, but 2 were found"，完全指不到真正的原因。
        "com.liusongboyy.chatdata.ask.audit.mapper",
        // 自研问数引擎的语义配置读取
        "com.liusongboyy.chatdata.engine.semantic.mapper",
        "com.liusongboyy.chatdata.engine.audit.mapper",
        "com.liusongboyy.chatdata.engine.admin.mapper",
        // 用户反馈（审核后沉淀为 SQL 范例）
        "com.liusongboyy.chatdata.engine.feedback.mapper",
        // 会话历史（产品视图，与 agentscope 状态表分开）
        "com.liusongboyy.chatdata.ask.conversation.mapper",
        // 数据域/助理策略变更审计
        "com.liusongboyy.chatdata.ask.policy.mapper",
        // 模型配置（问数用什么模型、单价、角色白名单）
        "com.liusongboyy.chatdata.engine.model.mapper"
})
@EnableScheduling
public class AskApplication {

    public static void main(String[] args) {
        SpringApplication.run(AskApplication.class, args);
    }
}

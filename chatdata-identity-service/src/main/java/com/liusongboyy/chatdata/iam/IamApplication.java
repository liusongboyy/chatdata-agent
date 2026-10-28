package com.liusongboyy.chatdata.iam;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * IAM：用户、组织、角色、权限与登录。
 *
 * <p>独立部署，自己的库（chatdata_iam），不 join ask 业务库。
 * 认证授权走 OAuth 2.1；access token 是不透明引用令牌，授权记录在 MySQL、
 * 会话与缓存在 Redis。见 docs/adr/0008。
 *
 * <p><strong>这是白名单式列包</strong>（与 ask 的 {@code AskApplication} 同理）：
 * 新增一个放 Mapper 的包时必须加进来，否则表现为启动时
 * "required a bean of type '…Mapper' that could not be found"，
 * 而报错里看不出是扫描范围的问题。
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.liusongboyy.chatdata.iam")
@MapperScan({
        "com.liusongboyy.chatdata.iam.user",
        "com.liusongboyy.chatdata.iam.org",
        "com.liusongboyy.chatdata.iam.role",
        "com.liusongboyy.chatdata.iam.policy",
        // 角色的功能权限（iam_role_permission）
        "com.liusongboyy.chatdata.iam.permission",
})
public class IamApplication {

    public static void main(String[] args) {
        SpringApplication.run(IamApplication.class, args);
    }
}

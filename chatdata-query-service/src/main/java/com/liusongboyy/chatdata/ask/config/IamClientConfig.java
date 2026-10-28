package com.liusongboyy.chatdata.ask.config;

import com.liusongboyy.chatdata.iam.client.IamIntrospector;
import com.liusongboyy.chatdata.iam.client.IamRoleDirectoryClient;
import com.liusongboyy.chatdata.iam.client.IamUserDirectoryClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * IAM 客户端装配。
 *
 * <p>三块能力：
 * <ul>
 *   <li>内省：把不透明令牌换成主体（含角色与组织），缓存 30 秒</li>
 *   <li>角色目录：读 IAM 里的角色码，供启动校验与管理台角色点选</li>
 *   <li>用户目录：供策略编辑器的用户选择器使用</li>
 * </ul>
 *
 * <p><b>数据策略不在这里。</b>按 ADR-0010，数据域/助理的授权与行/列规则归
 * ask 自己所有，存在 {@code perm_json} 里，问数热路径不调用 IAM。
 * IAM 只提供身份目录（角色、用户），因为身份的事实来源必须唯一。
 */
@Configuration
public class IamClientConfig {

    @Bean
    public IamIntrospector iamIntrospector(
            @Value("${chatdata.iam.base-url:http://127.0.0.1:9000}") String baseUrl,
            @Value("${chatdata.iam.introspect-cache-seconds:30}") long cacheSeconds,
            ObjectProvider<StringRedisTemplate> redisProvider) {
        // ObjectProvider：没有 Redis 时不报错，退化为无缓存
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        return new IamIntrospector(baseUrl, redis, Duration.ofSeconds(cacheSeconds));
    }

    /**
     * 角色目录。
     *
     * <p>总是装配，密钥缺失时由客户端在调用点报出明确原因——这样"没配环境变量"
     * 与"IAM 拒绝"能分开，而不是启动时抛一个与现场无关的 bean 创建失败。
     */
    @Bean
    public IamRoleDirectoryClient iamRoleDirectoryClient(
            @Value("${chatdata.iam.base-url:http://127.0.0.1:9000}") String baseUrl,
            @Value("${chatdata.iam.client-id:ask-resource-server}") String clientId,
            @Value("${chatdata.iam.client-secret:}") String clientSecret) {
        return new IamRoleDirectoryClient(baseUrl, clientId, clientSecret);
    }

    /**
     * 用户目录。
     *
     * <p>与角色目录一样**总是装配**：策略编辑器需要它。数据策略存在 ask 自己这里，
     * 但"用户是谁"仍然只有 IAM 一个来源。
     */
    @Bean
    public IamUserDirectoryClient iamUserDirectoryClient(
            @Value("${chatdata.iam.base-url:http://127.0.0.1:9000}") String baseUrl,
            @Value("${chatdata.iam.client-id:ask-resource-server}") String clientId,
            @Value("${chatdata.iam.client-secret:}") String clientSecret) {
        return new IamUserDirectoryClient(baseUrl, clientId, clientSecret);
    }
}

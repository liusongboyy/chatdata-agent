package com.liusongboyy.chatdata.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.session.data.redis.config.annotation.web.server.EnableRedisWebSession;

/**
 * 唯一入口。
 *
 * <p>职责只有跨切面的几件：路由、BFF 会话与令牌中继、限流、CORS。
 * **任何"这个人能不能看这个域"的判断都不在这里**——那属于 IAM 的策略层
 * 与 ask 引擎的执行点（ADR-0002）。
 *
 * <p>见 docs/adr/0008 与 chatdata-agent-design/iam-platform/spec.md §9。
 *
 * <h2>为什么必须 {@code @EnableRedisWebSession}</h2>
 *
 * <p>只引 {@code spring-session-data-redis} 依赖**不会**让 WebFlux 会话落 Redis——
 * 那需要显式开启。缺了它的表现很隐蔽：
 *
 * <ul>
 *   <li>会话存在进程内存里，进程一重启，BFF 就"登录状态还在、令牌没了"</li>
 *   <li>多实例部署时请求打到另一个实例，会话直接消失</li>
 *   <li>{@code /bff/me} 仍返回 {@code authenticated=true}（有 OAuth2User 属性），
 *       但 {@code tokenReady=false}，之后所有 {@code /api/**} 都是 401</li>
 * </ul>
 *
 * <p>最后一条最容易误判：看起来像"令牌过期"或"权限配置错了"，
 * 实际是会话存储没生效。
 */
@SpringBootApplication
@EnableRedisWebSession
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}

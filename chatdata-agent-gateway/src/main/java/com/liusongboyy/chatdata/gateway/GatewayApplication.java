package com.liusongboyy.chatdata.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 唯一入口。
 *
 * <p>职责只有跨切面的几件：路由、BFF 会话与令牌中继、限流、CORS。
 * **任何"这个人能不能看这个域"的判断都不在这里**——那属于 IAM 的策略层
 * 与 ask 引擎的执行点（ADR-0002）。
 *
 * <p>见 docs/adr/0008 与 .scratch/iam-platform/spec.md §9。
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}

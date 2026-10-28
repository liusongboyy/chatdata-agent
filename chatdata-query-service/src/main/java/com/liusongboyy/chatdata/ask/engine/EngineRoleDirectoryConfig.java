package com.liusongboyy.chatdata.ask.engine;

import com.liusongboyy.chatdata.engine.startup.RoleDirectory;
import com.liusongboyy.chatdata.iam.client.IamRoleDirectoryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 把 IAM 的角色目录提供给引擎的启动校验。
 *
 * <h2>为什么这层适配写在 ask 模块而不是引擎里</h2>
 *
 * <p>引擎只认调用方传进来的角色码，从不自己去找角色——「角色存在哪里」是
 * 宿主应用的事。把这张表的查询塞进引擎，等于让一个库去猜宿主的认证模型。
 * 所以引擎只声明 {@link RoleDirectory} 这么个取角色的口子，由应用侧实现。
 *
 * <h2>为什么是 IAM 而不是本地 {@code sys_role}</h2>
 *
 * <p>身份与角色已收敛到 IAM，本地的 {@code sys_role} 只剩迁移前的旧数据。
 * 若校验仍读旧表，就会出现两种方向相反的错误：管理员在 IAM 里新建的角色被报成
 * "不存在"（拦住一次本来正确的启动），而在 IAM 里删掉的角色在旧表里还在、
 * 引用它的规则被判定为合规（放过一次真实的数据外露）。两种都比不校验更糟。
 *
 * <h2>为什么查全部角色（含停用的）</h2>
 *
 * <p>「角色不存在」与「角色存在但已停用」要分开报：前者多半是名字打错了，
 * 后者是有人主动停用了角色而忘了清理引用它的权限规则。两者的处理方式不同，
 * 报成同一句等于让运维自己去猜。
 */
@Configuration
public class EngineRoleDirectoryConfig {

    private static final Logger log = LoggerFactory.getLogger(EngineRoleDirectoryConfig.class);

    @Bean
    public RoleDirectory engineRoleDirectory(IamRoleDirectoryClient client) {
        return () -> {
            try {
                Map<String, Boolean> roles = new LinkedHashMap<>();
                for (IamRoleDirectoryClient.RoleEntry r : client.roles()) {
                    // **不 trim**：运行时是逐字符匹配的，这里若宽容地去掉空白，
                    // 就会造出"启动校验说没问题、运行时匹配不上"这种最坏组合
                    roles.put(r.code(), r.enabled());
                }
                return Optional.of(roles);
            } catch (Exception e) {
                // 拿不到就**跳过**角色引用校验，而不是当成"一个角色都没有"——
                // 后者会把每一份权限配置都报成"角色不存在"，一屏假告警
                // 等于没有告警，而且会让人干脆关掉这道校验
                log.warn("读取 IAM 角色目录失败，本次跳过角色引用校验：{}", e.getMessage());
                return Optional.empty();
            }
        };
    }
}

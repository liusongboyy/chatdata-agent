package com.liusongboyy.chatdata.iam.bootstrap;

import com.liusongboyy.chatdata.iam.config.IamProperties;
import com.liusongboyy.chatdata.iam.permission.PermissionCatalog;
import com.liusongboyy.chatdata.iam.role.RoleMapper;
import com.liusongboyy.chatdata.iam.role.RoleRow;
import com.liusongboyy.chatdata.iam.user.UserMapper;
import com.liusongboyy.chatdata.iam.user.UserRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 建第一个超级管理员账号：{@code --spring.profiles.active=bootstrap-admin} 时执行、跑完即退出。
 *
 * <h2>为什么必须有它</h2>
 *
 * <p>全新部署时 {@code iam_role} 是空的，没有任何人能登录 —— 而"登录进管理台去建角色"
 * 这个动作本身要求已经能登录。这是个鸡生蛋问题，只能靠一次带外的初始化解开。
 *
 * <p>密码必须由**环境变量**提供而不是写在 SQL 里：{@code 02-seed-dev.sql} 那种把
 * BCrypt 哈希硬编码进脚本的做法只适合本地开发，生产上等于把密码写进了版本库。
 *
 * <h2>用法</h2>
 *
 * <pre>
 *   BOOTSTRAP_ADMIN_USERNAME=admin \
 *   BOOTSTRAP_ADMIN_PASSWORD='一次性强密码' \
 *   mvn -pl chatdata-identity-service spring-boot:run -Dspring-boot.run.profiles=bootstrap-admin
 * </pre>
 *
 * <p>登录后请立刻在「用户」页改掉这个密码（或直接删掉这个账号改用个人账号）。
 *
 * <h2>幂等</h2>
 *
 * <p>角色不存在就建；用户不存在就建；两者都在就只补角色关系。
 * <strong>已存在的用户不会被改密码</strong>，除非显式打开
 * {@code BOOTSTRAP_ADMIN_RESET_PASSWORD=true}——否则一次误执行会把线上管理员的密码换掉。
 */
@Component
@Profile("bootstrap-admin")
public class BootstrapAdminRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private static final int MIN_PASSWORD = 8;

    private final UserMapper users;
    private final RoleMapper roles;
    private final PasswordEncoder passwordEncoder;
    private final IamProperties properties;
    private final ApplicationContext context;

    private final String username;
    private final String password;
    private final boolean resetPassword;

    public BootstrapAdminRunner(UserMapper users, RoleMapper roles, PasswordEncoder passwordEncoder,
                                IamProperties properties, ApplicationContext context,
                                @Value("${BOOTSTRAP_ADMIN_USERNAME:admin}") String username,
                                @Value("${BOOTSTRAP_ADMIN_PASSWORD:}") String password,
                                @Value("${BOOTSTRAP_ADMIN_RESET_PASSWORD:false}") boolean resetPassword) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.context = context;
        this.username = username;
        this.password = password;
        this.resetPassword = resetPassword;
    }

    @Override
    public void run(String... args) {
        int code;
        try {
            // 不设默认密码是刻意的：一个"默认管理员密码"迟早会被带到生产
            if (password == null || password.isBlank()) {
                throw new IllegalStateException(
                        "必须通过环境变量 BOOTSTRAP_ADMIN_PASSWORD 提供初始密码");
            }
            if (password.length() < MIN_PASSWORD) {
                throw new IllegalStateException("初始密码至少 " + MIN_PASSWORD + " 位");
            }
            if (username == null || username.isBlank()) {
                throw new IllegalStateException("BOOTSTRAP_ADMIN_USERNAME 不能为空");
            }

            ensureSuperRole();
            long userId = ensureUser();
            ensureRoleGranted(userId);

            log.info("初始超级管理员已就绪：username={}。"
                    + "登录后请立刻改密码，或删掉这个账号改用个人账号。", username);
            code = 0;
        } catch (Exception e) {
            System.err.println("初始化超级管理员失败：" + e.getMessage());
            e.printStackTrace(System.err);
            code = 1;
        }

        int exitCode = code;
        int exit = SpringApplication.exit(context, () -> exitCode);
        System.exit(exit);
    }

    /**
     * 确保内置超管角色存在。
     *
     * <p>正常情况下 {@code 08-bootstrap.sql} 已经建好了，这里只是防御：
     * 角色不存在时下面的授权会**静默插 0 行**（{@code insertUserRole} 按 code 关联），
     * 表现为"跑成功了但登进去什么都没有"。
     */
    private void ensureSuperRole() {
        String tenant = properties.getTenant();
        RoleRow existing = roles.selectByCode(tenant, PermissionCatalog.BUILTIN_SUPER_ROLE);
        if (existing != null) {
            log.info("内置超管角色已存在：code={}", PermissionCatalog.BUILTIN_SUPER_ROLE);
            return;
        }
        roles.insertRole(tenant, PermissionCatalog.BUILTIN_SUPER_ROLE,
                PermissionCatalog.BUILTIN_SUPER_ROLE_NAME, "ADMIN",
                "内置超级管理员：自动拥有全部功能权限，不可编辑、不可停用。"
                        + "用于兜底，日常请用普通管理角色。", true);
        log.info("已创建内置超管角色：code={}", PermissionCatalog.BUILTIN_SUPER_ROLE);
    }

    /** @return 用户 id */
    private long ensureUser() {
        String tenant = properties.getTenant();
        UserRow existing = users.selectByUsername(tenant, username);
        if (existing != null) {
            if (resetPassword) {
                users.updatePassword(existing.id(), passwordEncoder.encode(password));
                log.warn("已重置用户 {} 的密码（BOOTSTRAP_ADMIN_RESET_PASSWORD=true）", username);
            } else {
                log.info("用户 {} 已存在，保留其密码不动", username);
            }
            return existing.id();
        }

        Map<String, Object> params = new HashMap<>();
        params.put("tenant", tenant);
        params.put("username", username);
        params.put("passwordHash", passwordEncoder.encode(password));
        params.put("displayName", "超级管理员");
        params.put("status", "ACTIVE");
        try {
            users.insertUser(params);
        } catch (DuplicateKeyException e) {
            // 并发执行了两次：另一次已经建好了
            UserRow raced = users.selectByUsername(tenant, username);
            if (raced == null) {
                throw e;
            }
            return raced.id();
        }
        Object idValue = params.get("id");
        if (!(idValue instanceof Number n)) {
            throw new IllegalStateException("新增用户后未回填主键");
        }
        log.info("已创建用户：username={} id={}", username, n.longValue());
        return n.longValue();
    }

    /**
     * 授超管角色。
     *
     * <p>用 {@code NOT EXISTS} 的思路而不是"先删后插"：这里**只加不减**，
     * 误执行不会把这个人已有的其它角色抹掉。
     */
    private void ensureRoleGranted(long userId) {
        String tenant = properties.getTenant();
        int inserted = users.insertUserRole(userId, tenant, PermissionCatalog.BUILTIN_SUPER_ROLE);
        if (inserted == 0) {
            // 角色不存在，或已有该授予（MySQL 不会区分）。上面已确保角色存在，
            // 所以这里只可能是"已经授过了"
            log.info("用户 {} 已持有超管角色，未重复授予", username);
        }
    }
}

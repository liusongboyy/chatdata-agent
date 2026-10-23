-- =====================================================================
-- 本地账号（临时认证）
--
-- ⚠️ 这是**临时认证**，不是本项目的目标架构。
--
-- 目标架构是「身份从企业统一身份（SSO）获取，角色在本项目维护」。
-- 建这张表的原因只有一个：SSO 方案短期无法定案，而系统需要一套能上生产的
-- 登录方式——当前的 /dev/login（读请求头/Cookie 认身份）**只存在于 dev profile**，
-- 绝不能进生产。
--
-- 因此本表连同 /sys/login 相关实现都应在 SSO 落地后**整体删除**：
-- 届时只需替换 IdentityResolver 的实现，其余代码不受影响。
-- 见 docs/adr/0005-temporary-password-auth.md。
--
-- 表名刻意不叫 sys_user：jeecg-boot 的用户体系用的是那个名字，
-- 将来若真接入 jeecg 会撞名。chatdata_ 前缀表明它属于本项目。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `chatdata_user` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`      VARCHAR(64)  NOT NULL                COMMENT '登录名，与 sys_user_role.username 一致',
  `password_hash` VARCHAR(100) NOT NULL                COMMENT 'BCrypt 哈希，绝不存明文',
  `display_name`  VARCHAR(64)  DEFAULT NULL            COMMENT '显示名',
  `enabled`       TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否启用：0=否 1=是',
  `created_at`    DATETIME     NOT NULL                COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL                COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地账号（临时认证，SSO 落地后删除）';

-- 初始账号。密码仅用于本地起步；生产环境必须立即修改。
-- 哈希为 BCrypt（$2a$, cost=10），生成后已用同一密码回验通过。
--   admin    / admin123
--   zhangsan / zhangsan123
--   lisi     / lisi123
INSERT INTO `chatdata_user` (`username`, `password_hash`, `display_name`, `enabled`, `created_at`, `updated_at`)
VALUES
    ('admin',    '$2a$10$4yto5q8taBXg.FwT8Dcoz.hUWUmBDMhGAteSfUCJu/ocYYjVJ3gyq', '管理员', 1, NOW(), NOW()),
    ('zhangsan', '$2a$10$sBvDqNtMgN/7a7RMLgyH8evquMcU3dFiRcN/wnQkZmiagjDBX916.', '张三',   1, NOW(), NOW()),
    ('lisi',     '$2a$10$JnJrgW8wFHS9G6ShH7NoyuIul.cSv3eJZXxggc4DApKxJG8H44Bne', '李四',   1, NOW(), NOW())
ON DUPLICATE KEY UPDATE `updated_at` = NOW();

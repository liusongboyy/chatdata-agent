-- =====================================================================
-- 从 ask 库迁移用户/角色/组织到 IAM 库
--
-- 前提：
--   1. 两个库在同一 MySQL 实例（或已做跨库访问），下面的 jimureport 是 ask 库名；
--   2. 已执行 01-schema.sql 与 02-seed-dev.sql（需要 HQ 根组织与角色）；
--   3. 迁移期间 ask 仍在跑旧认证，**不要删旧表**；P1 切换完成后再清理。
--
-- 说明：
--   - 用户密码哈希原样搬运，迁移后密码不变；
--   - org_code 是一人一组织的旧模型，这里转成 iam_user_org 的主组织；
--   - 角色 code 原样保留（ADR-0004：code 不可重命名）。
-- =====================================================================

-- 1) 角色
INSERT INTO `iam_role` (`tenant_id`, `code`, `name`, `category`, `description`, `enabled`)
SELECT 't_default', r.`role_code`, r.`role_name`,
       CASE WHEN r.`role_code` = 'chat2bi' THEN 'ADMIN' ELSE 'DATA' END,
       r.`description`, r.`enabled`
FROM `jimureport`.`sys_role` r
ON DUPLICATE KEY UPDATE
    `name` = VALUES(`name`),
    `category` = VALUES(`category`),
    `enabled` = VALUES(`enabled`);

-- 2) 用户
INSERT INTO `iam_user` (`tenant_id`, `username`, `password_hash`, `display_name`, `status`)
SELECT 't_default', u.`username`, u.`password_hash`, u.`display_name`,
       CASE WHEN u.`enabled` = 1 THEN 'ACTIVE' ELSE 'DISABLED' END
FROM `jimureport`.`chatdata_user` u
ON DUPLICATE KEY UPDATE
    `password_hash` = VALUES(`password_hash`),
    `display_name` = VALUES(`display_name`),
    `status` = VALUES(`status`);

-- 3) 组织：把旧 org_code 变成 HQ 下的一个子组织（已存在则跳过）
INSERT INTO `iam_org` (`tenant_id`, `parent_id`, `code`, `name`, `path`, `sort_no`, `enabled`)
SELECT 't_default', 1, t.`org_code`, t.`org_code`, CONCAT('/1/0/'), 100, 1
FROM (SELECT DISTINCT `org_code` FROM `jimureport`.`chatdata_user`
      WHERE `org_code` IS NOT NULL AND `org_code` <> '') t
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`);

-- path 需要自增 id，插入后统一修正
UPDATE `iam_org`
SET `path` = CONCAT('/1/', `id`, '/')
WHERE `tenant_id` = 't_default' AND `parent_id` = 1 AND `code` <> 'HQ';

-- 4) 用户主组织
INSERT INTO `iam_user_org` (`user_id`, `org_id`, `is_primary`)
SELECT iu.`id`, o.`id`, 1
FROM `jimureport`.`chatdata_user` u
JOIN `iam_user` iu ON iu.`username` = u.`username` AND iu.`tenant_id` = 't_default'
JOIN `iam_org` o ON o.`code` = u.`org_code` AND o.`tenant_id` = 't_default'
WHERE u.`org_code` IS NOT NULL AND u.`org_code` <> ''
ON DUPLICATE KEY UPDATE `is_primary` = VALUES(`is_primary`);

-- 5) 用户角色
INSERT INTO `iam_user_role` (`user_id`, `role_id`, `org_id`)
SELECT iu.`id`, ir.`id`, NULL
FROM `jimureport`.`sys_user_role` ur
JOIN `iam_user` iu ON iu.`username` = ur.`username` AND iu.`tenant_id` = 't_default'
JOIN `iam_role` ir ON ir.`code` = ur.`role_code` AND ir.`tenant_id` = 't_default'
ON DUPLICATE KEY UPDATE `role_id` = VALUES(`role_id`);

-- 6) 核对：数量应一致（admin 已在种子中，可能多 1）
SELECT 'roles' AS item, COUNT(*) AS iam_count,
       (SELECT COUNT(*) FROM `jimureport`.`sys_role`) AS ask_count
FROM `iam_role`
UNION ALL
SELECT 'users', COUNT(*), (SELECT COUNT(*) FROM `jimureport`.`chatdata_user`) FROM `iam_user`
UNION ALL
SELECT 'user_roles', COUNT(*), (SELECT COUNT(*) FROM `jimureport`.`sys_user_role`) FROM `iam_user_role`;

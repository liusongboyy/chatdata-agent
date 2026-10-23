-- =====================================================================
-- 本地开发种子（chatdata_iam）
--
-- 只用于本地/演示。生产初始化请走 sql/iam/08-bootstrap.sql +
-- `bootstrap-admin` profile（见 BootstrapAdminRunner），不要执行本文件的密码。
--
--   admin / admin123    —— 与 ask 的临时账号一致，便于迁移期对照
-- =====================================================================

INSERT INTO `iam_tenant` (`id`, `name`, `enabled`)
VALUES ('t_default', '默认租户', 1)
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`);

-- 组织：根 + 两个区域。显式 id 仅为种子可读性；生产由自增分配。
INSERT INTO `iam_org` (`id`, `tenant_id`, `parent_id`, `code`, `name`, `path`, `sort_no`, `enabled`)
VALUES
  (1,  't_default', NULL, 'HQ',   '总部',   '/1/',    0, 1),
  (10, 't_default', 1,    '华东', '华东大区', '/1/10/', 10, 1),
  (11, 't_default', 1,    '华北', '华北大区', '/1/11/', 20, 1)
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `path` = VALUES(`path`);

-- 角色：沿用 ADR-0004 的三分。code 与现有权限配置里的字符串保持一致。
--
-- super_admin 是**内置超管**：权限由代码展开为全部，不落 iam_role_permission，
-- 界面只读、不可停用。它是那道"改错了还能进来"的保险，所以必须有、且必须有人持有。
INSERT INTO `iam_role` (`tenant_id`, `code`, `name`, `category`, `description`, `enabled`)
VALUES
  ('t_default', 'super_admin', '超级管理员', 'ADMIN',
   '内置超级管理员：自动拥有全部功能权限，不可编辑、不可停用。用于兜底，日常请用普通管理角色。', 1),
  ('t_default', 'chat2bi',  '问数管理台', 'ADMIN',
   '管理台角色：可配置数据源、语义域、助手与反馈审核。不参与问数，不授予数据权限。', 1),
  ('t_default', '区域经理', '区域经理',   'DATA',
   '数据权限角色：语义域中限定 region = 所属大区。', 1),
  ('t_default', '分析师',   '分析师',     'DATA',
   '数据权限角色：语义域中对敏感金额列脱敏。', 1)
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `category` = VALUES(`category`);

-- 管理员：使用 ask 现有的 BCrypt 哈希（admin123），迁移期可直接对照登录。
INSERT INTO `iam_user` (`tenant_id`, `username`, `password_hash`, `display_name`, `status`)
VALUES ('t_default', 'admin',
        '$2a$10$4yto5q8taBXg.FwT8Dcoz.hUWUmBDMhGAteSfUCJu/ocYYjVJ3gyq',
        '管理员', 'ACTIVE')
ON DUPLICATE KEY UPDATE `display_name` = VALUES(`display_name`), `status` = VALUES(`status`);

INSERT INTO `iam_user_org` (`user_id`, `org_id`, `is_primary`)
SELECT u.id, 1, 1 FROM `iam_user` u WHERE u.username = 'admin'
ON DUPLICATE KEY UPDATE `is_primary` = VALUES(`is_primary`);

-- ⚠️ 不用 ON DUPLICATE KEY：`iam_user_role` 的唯一键含可空的 `org_id`，
-- 而 MySQL 唯一键里 NULL 互不相等，租户级授予（org_id = NULL）每次执行都会再插一行。
-- 重复的角色行会让"某人有几个角色"这类统计悄悄偏大。
INSERT INTO `iam_user_role` (`user_id`, `role_id`, `org_id`)
SELECT u.id, r.id, NULL
FROM `iam_user` u JOIN `iam_role` r ON r.code IN ('super_admin', 'chat2bi')
WHERE u.username = 'admin'
  AND NOT EXISTS (
      SELECT 1 FROM `iam_user_role` t
      WHERE t.`user_id` = u.`id` AND t.`role_id` = r.`id` AND t.`org_id` IS NULL
  );

-- 功能权限的种子在 07-role-permission.sql（问数页的 ask.ui.*）与
-- 08-bootstrap.sql（chat2bi 的管理台预置）。按序号执行即可。


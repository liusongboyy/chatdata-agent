-- =====================================================================
-- 初始化 / 升级：内置超级管理员角色
--
-- 什么时候跑：
--   · 已有部署从「chat2bi 兼任超管」升级到「独立的 super_admin」——**必须先跑这个**
--   · 全新部署：先跑本脚本建角色，再用 bootstrap-admin profile 建第一个管理员账号
--
-- 幂等，可以重复执行。
--
-- ⚠️ 顺序很重要。超管的判定已经从 chat2bi 改到 super_admin，先跑本脚本把
--    现有管理员补授 super_admin，再上新的应用版本；反过来会有一段时间
--    **所有人都进不了管理台**（chat2bi 不再等于超管，而 super_admin 还没授出去）。
-- =====================================================================

-- ── 1) 建内置超管角色 ────────────────────────────────────────────────
-- category 用 ADMIN：它的授予对象与失败后果都属于"管理台"这一类（ADR-0004 的三分）。
-- 权限不写进 iam_role_permission——由代码展开为全部，见 PermissionCatalog。
INSERT INTO `iam_role` (`tenant_id`, `code`, `name`, `category`, `description`, `enabled`)
VALUES ('t_default', 'super_admin', '超级管理员', 'ADMIN',
        '内置超级管理员：自动拥有全部功能权限，不可编辑、不可停用。用于兜底，日常请用普通管理角色。', 1)
ON DUPLICATE KEY UPDATE
    `name` = VALUES(`name`),
    `description` = VALUES(`description`),
    -- 有人手工把它停用了就恢复回来：停用它等于把唯一的兜底路径掐掉
    `enabled` = 1;

-- ── 2) 把现有管理员补授 super_admin ──────────────────────────────────
-- 判定依据是"当前持有 chat2bi 的人"——在旧模型里 chat2bi 就是超管。
-- 不做这一步，升级的那一刻他们就失去全部权限，而**他们正是唯一还能改回来的人**。
--
-- ⚠️ 用 NOT EXISTS 而不是 INSERT IGNORE / ON DUPLICATE KEY。
--    `iam_user_role` 的唯一键是 (user_id, role_id, org_id)，而**租户级授予的 org_id 是 NULL**；
--    MySQL 的唯一键里 NULL 互不相等，所以那种写法每次执行都会再插一行重复的角色授予。
--    （`02-seed-dev.sql` 里就有这个隐患，见该文件末尾的核对段。）
INSERT INTO `iam_user_role` (`user_id`, `role_id`, `org_id`)
SELECT DISTINCT xur.`user_id`, s.`id`, NULL
FROM `iam_user_role` xur
JOIN `iam_role` legacy ON legacy.`id` = xur.`role_id` AND legacy.`code` = 'chat2bi'
JOIN `iam_user` u ON u.`id` = xur.`user_id` AND u.`tenant_id` = 't_default'
JOIN `iam_role` s ON s.`tenant_id` = u.`tenant_id` AND s.`code` = 'super_admin'
WHERE NOT EXISTS (
    SELECT 1 FROM `iam_user_role` t
    WHERE t.`user_id` = xur.`user_id` AND t.`role_id` = s.`id` AND t.`org_id` IS NULL
);

-- ── 3) 给「问数管理台」角色预置它需要的权限 ──────────────────────────
-- chat2bi 从此是**普通可配置角色**（不再是超管）。它的描述是"可配置数据源、
-- 语义域、助手与反馈审核。不参与问数"，所以预置管理面的一切、但不含 ask.ui.*。
-- 预置之后管理员仍可按需收窄——这正是把它从超管拆出来的意义。
INSERT IGNORE INTO `iam_role_permission` (`role_id`, `code`)
SELECT r.`id`, p.`code`
FROM `iam_role` r
JOIN (
            SELECT 'iam.users'                AS `code`
  UNION ALL SELECT 'iam.users.write'
  UNION ALL SELECT 'iam.users.roles'
  UNION ALL SELECT 'iam.users.resetPassword'
  UNION ALL SELECT 'iam.orgs'
  UNION ALL SELECT 'iam.orgs.write'
  UNION ALL SELECT 'iam.roles'
  UNION ALL SELECT 'iam.roles.write'
  UNION ALL SELECT 'iam.roles.permissions'
  UNION ALL SELECT 'ask.admin.datasources'
  UNION ALL SELECT 'ask.admin.datasources.write'
  UNION ALL SELECT 'ask.admin.datasources.delete'
  UNION ALL SELECT 'ask.admin.datasources.test'
  UNION ALL SELECT 'ask.admin.domains'
  UNION ALL SELECT 'ask.admin.domains.write'
  UNION ALL SELECT 'ask.admin.domains.delete'
  UNION ALL SELECT 'ask.admin.domains.tables'
  UNION ALL SELECT 'ask.admin.assistants'
  UNION ALL SELECT 'ask.admin.assistants.write'
  UNION ALL SELECT 'ask.admin.assistants.delete'
  UNION ALL SELECT 'ask.admin.feedback'
  UNION ALL SELECT 'ask.admin.feedback.review'
) p
WHERE r.`tenant_id` = 't_default' AND r.`code` = 'chat2bi';

-- ── 4) 核对 ──────────────────────────────────────────────────────────
-- 期望：
--   super_admin  → 内置超管，permission_count 恒为 0（权限在代码里展开，这是正常的）
--   chat2bi      → 24 项（上面那份预置）
-- 每个启用中的部署至少要有一个 super_admin 的持有者，否则下面这条会报出来。
SELECT r.`code` AS role_code, r.`enabled`,
       (SELECT COUNT(*) FROM `iam_role_permission` p WHERE p.`role_id` = r.`id`) AS permission_count,
       (SELECT COUNT(*) FROM `iam_user_role` ur WHERE ur.`role_id` = r.`id`) AS holder_count
FROM `iam_role` r
WHERE r.`tenant_id` = 't_default'
ORDER BY r.`category`, r.`id`;

SELECT '超级管理员持有者数（必须 >= 1，否则没人能进管理台）' AS check_item,
       COUNT(*) AS holders
FROM `iam_user_role` ur
JOIN `iam_role` r ON r.`id` = ur.`role_id`
JOIN `iam_user` u ON u.`id` = ur.`user_id`
WHERE r.`code` = 'super_admin' AND r.`enabled` = 1 AND u.`status` = 'ACTIVE';

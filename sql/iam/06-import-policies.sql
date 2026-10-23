-- =====================================================================
-- ⚠️ 已退役（2026-10-23，ADR-0010）：本脚本只保留作历史记录。
--    数据策略已回归 ask 的 perm_json；请勿在全新部署中执行。
--    收尾动作见 11-retire-iam-policy.sql。
-- 把 ask 的 perm_json 导入 IAM 策略表
--
-- 前提：已执行 05-policy.sql；两个库在同一实例（jimureport 是 ask 库名）。
--
-- 迁移是"搬运 + 版本号"：文档与旧 perm_json 同构，因此引擎的
-- DomainPermission.from 一行不用改，回滚也只是把 policy.source 切回 local。
--
-- **搬运时顺手修掉一个已知的坏引用**：旧 `sys_role` 里有个 `管理员`，它没有迁到 IAM
-- （语义由内置超管 `super_admin` 承接）。不修的话，搬过来的授权/规则在运行期
-- 匹配不上任何人（静默失效），启动校验每次都会报"角色不存在"。
-- 已经迁过的库用 09-fix-stale-role-refs.sql 补。
--
-- 可重复执行：再跑一次会把 version + 1（内容未变，但版本推进是安全的）。
-- =====================================================================

-- 1) 数据域策略
INSERT INTO `iam_policy` (`tenant_id`, `resource_type`, `resource_id`, `document`, `version`, `updated_by`)
SELECT 't_default', 'DOMAIN', CAST(d.`id` AS CHAR),
       REPLACE(d.`perm_json`, '"管理员"', '"super_admin"'), 1, 'migration'
FROM `jimureport`.`ask_domain` d
WHERE d.`perm_json` IS NOT NULL
  AND TRIM(d.`perm_json`) <> ''
  AND JSON_VALID(d.`perm_json`)
ON DUPLICATE KEY UPDATE
    `document` = VALUES(`document`),
    `version` = `version` + 1,
    `updated_by` = 'migration';

-- 2) 助手策略（只搬授权部分：enabled / grantRoles / grantUsers）
INSERT INTO `iam_policy` (`tenant_id`, `resource_type`, `resource_id`, `document`, `version`, `updated_by`)
SELECT 't_default', 'ASSISTANT', CAST(a.`id` AS CHAR),
       REPLACE(a.`perm_json`, '"管理员"', '"super_admin"'), 1, 'migration'
FROM `jimureport`.`ask_assistant` a
WHERE a.`perm_json` IS NOT NULL
  AND TRIM(a.`perm_json`) <> ''
  AND JSON_VALID(a.`perm_json`)
ON DUPLICATE KEY UPDATE
    `document` = VALUES(`document`),
    `version` = `version` + 1,
    `updated_by` = 'migration';

-- 3) 核对
SELECT `resource_type`, COUNT(*) AS cnt, MIN(`version`) AS min_v, MAX(`version`) AS max_v
FROM `iam_policy`
GROUP BY `resource_type`;

-- =====================================================================
-- ⚠️ 已退役（2026-10-23，ADR-0010）：本脚本只保留作历史记录。
--    数据策略已回归 ask 的 perm_json；请勿在全新部署中执行。
--    收尾动作见 11-retire-iam-policy.sql。
-- ADR-0010：把 IAM 里最终生效的数据策略搬回 ask 的 perm_json
--
-- 背景：06-import-policies.sql 曾把 ask.perm_json 导入 iam_policy，并在导入时
-- 把旧角色 `管理员` 修成内置超管 `super_admin`。此后策略的事实来源是 IAM，
-- 若期间在 IAM 控制台改过策略，ask.perm_json 就是旧拷贝。
--
-- 本脚本做三件事：
--   1. 备份 ask_domain / ask_assistant 的 perm_json；
--   2. 用 iam_policy 的最终文档覆盖本地 perm_json（只覆盖 JSON 合法的行）；
--   3. 输出差异与残留的旧角色引用，供人工复核。
--
-- 可重复执行。执行后把 chatdata.engine.policy.source 切到 local（代码默认值已切）。
--
-- ⚠️ 前提：两个库在同一个 MySQL 实例（既有迁移脚本同样如此）。
--    IAM 实例独立部署时，请用导出/导入代替本脚本。
-- =====================================================================

-- 0) 备份。表名带日期，避免覆盖上一轮备份
CREATE TABLE IF NOT EXISTS `jimureport`.`ask_domain_perm_backup_20261023` AS
SELECT `id`, `perm_json`, NOW() AS `backed_up_at` FROM `jimureport`.`ask_domain`;

CREATE TABLE IF NOT EXISTS `jimureport`.`ask_assistant_perm_backup_20261023` AS
SELECT `id`, `perm_json`, NOW() AS `backed_up_at` FROM `jimureport`.`ask_assistant`;

-- 1) 数据域：以 IAM 文档为准
START TRANSACTION;

UPDATE `jimureport`.`ask_domain` d
JOIN `chatdata_iam`.`iam_policy` p
  ON p.`tenant_id` = 't_default'
 AND p.`resource_type` = 'DOMAIN'
 AND p.`resource_id` = CAST(d.`id` AS CHAR)
SET d.`perm_json` = p.`document`
WHERE p.`document` IS NOT NULL
  AND JSON_VALID(p.`document`);

-- 2) 助理：以 IAM 文档为准
UPDATE `jimureport`.`ask_assistant` a
JOIN `chatdata_iam`.`iam_policy` p
  ON p.`tenant_id` = 't_default'
 AND p.`resource_type` = 'ASSISTANT'
 AND p.`resource_id` = CAST(a.`id` AS CHAR)
SET a.`perm_json` = p.`document`
WHERE p.`document` IS NOT NULL
  AND JSON_VALID(p.`document`);

COMMIT;

-- 3) 复核 A：迁移后仍与 IAM 文档不一致的资源（应为 0 行）
SELECT 'DOMAIN' AS `resource_type`, d.`id`, d.`name`
FROM `jimureport`.`ask_domain` d
JOIN `chatdata_iam`.`iam_policy` p
  ON p.`tenant_id` = 't_default'
 AND p.`resource_type` = 'DOMAIN'
 AND p.`resource_id` = CAST(d.`id` AS CHAR)
WHERE p.`document` IS NOT NULL
  AND NOT (CAST(p.`document` AS JSON) = CAST(d.`perm_json` AS JSON))

UNION ALL

SELECT 'ASSISTANT', a.`id`, a.`name`
FROM `jimureport`.`ask_assistant` a
JOIN `chatdata_iam`.`iam_policy` p
  ON p.`tenant_id` = 't_default'
 AND p.`resource_type` = 'ASSISTANT'
 AND p.`resource_id` = CAST(a.`id` AS CHAR)
WHERE p.`document` IS NOT NULL
  AND NOT (CAST(p.`document` AS JSON) = CAST(a.`perm_json` AS JSON));

-- 4) 复核 B：仍引用旧角色 `管理员` 的行（应为 0；IAM 导入时已改为 super_admin）
SELECT 'DOMAIN' AS `resource_type`, `id`, `name`
FROM `jimureport`.`ask_domain`
WHERE `perm_json` LIKE '%"管理员"%'
UNION ALL
SELECT 'ASSISTANT', `id`, `name`
FROM `jimureport`.`ask_assistant`
WHERE `perm_json` LIKE '%"管理员"%';

-- 5) 复核 C：资源数量与策略数量（IAM 有、本地没有 → 该资源会被 fail-closed）
SELECT 'DOMAIN' AS `resource_type`,
       (SELECT COUNT(*) FROM `jimureport`.`ask_domain`) AS `local_resources`,
       (SELECT COUNT(*) FROM `chatdata_iam`.`iam_policy`
         WHERE `tenant_id`='t_default' AND `resource_type`='DOMAIN') AS `iam_policies`
UNION ALL
SELECT 'ASSISTANT',
       (SELECT COUNT(*) FROM `jimureport`.`ask_assistant`),
       (SELECT COUNT(*) FROM `chatdata_iam`.`iam_policy`
         WHERE `tenant_id`='t_default' AND `resource_type`='ASSISTANT');

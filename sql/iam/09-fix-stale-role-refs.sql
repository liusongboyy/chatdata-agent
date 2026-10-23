-- =====================================================================
-- ⚠️ 已退役（2026-10-23，ADR-0010）：本脚本只保留作历史记录。
--    数据策略已回归 ask 的 perm_json；请勿在全新部署中执行。
--    收尾动作见 11-retire-iam-policy.sql。
-- 清理已不存在的角色引用：`管理员` → `super_admin`
--
-- 背景：
--   角色目录迁到 IAM 后，`管理员` 这个角色码不再存在（它的语义由内置超管
--   `super_admin` 承接，见 08-bootstrap.sql）。但历史策略文档是从
--   `ask_domain.perm_json` / `ask_assistant.perm_json` 导入的，里面仍写着
--   `管理员`——于是：
--     * 运行期这些授权/规则匹配不上任何人（静默失效）；
--     * 启动校验会报 5 条 WARNING（"角色不存在"），每次启动都刷一遍。
--
-- 为什么是 `super_admin` 而不是直接删掉引用：
--   `管理员` 在旧模型里就是"能管一切的人"，把引用直接删掉会让原本的管理员
--   失去访问权；映射到内置超管才保住了原意。
--
-- 适用范围：
--   * `chatdata_iam.iam_policy.document`（权威策略）
--   * `jimureport.ask_domain.perm_json` / `jimureport.ask_assistant.perm_json`
--     （本地副本；`policy.source=local` 时仍会被读，双读校验也会比对它）
--
-- 幂等：重复执行无副作用（替换后的文档里不再含 `管理员`）。
-- 前置：IAM 与 ask 库在同一 MySQL 实例；IAM 库名 chatdata_iam，ask 库名 jimureport。
-- =====================================================================

-- 1) IAM 策略文档
UPDATE `chatdata_iam`.`iam_policy`
SET `document` = REPLACE(`document`, '"管理员"', '"super_admin"')
WHERE `document` LIKE '%"管理员"%';

-- 2) ask 本地副本：域权限
UPDATE `jimureport`.`ask_domain`
SET `perm_json` = REPLACE(`perm_json`, '"管理员"', '"super_admin"')
WHERE `perm_json` LIKE '%"管理员"%';

-- 3) ask 本地副本：助理权限
UPDATE `jimureport`.`ask_assistant`
SET `perm_json` = REPLACE(`perm_json`, '"管理员"', '"super_admin"')
WHERE `perm_json` LIKE '%"管理员"%';

-- 校验：以下三条都应为 0 行
SELECT 'iam_policy 残留' AS `检查项`, COUNT(*) AS `行数`
FROM `chatdata_iam`.`iam_policy` WHERE `document` LIKE '%"管理员"%'
UNION ALL
SELECT 'ask_domain 残留', COUNT(*)
FROM `jimureport`.`ask_domain` WHERE `perm_json` LIKE '%"管理员"%'
UNION ALL
SELECT 'ask_assistant 残留', COUNT(*)
FROM `jimureport`.`ask_assistant` WHERE `perm_json` LIKE '%"管理员"%';

-- =====================================================================
-- 模型管理页的功能权限
--
-- 依据：.scratch/model-cost/spec.md §4.4.5
--
-- ## 为什么要单独一个脚本而不是改 08-bootstrap.sql
--
-- 08 只在**首次部署**时执行（它同时建角色、授超管）。已经上线的部署再跑一遍
-- 会重新插入那些行——`INSERT IGNORE` 让它是幂等的，但它表达的是"初始状态"，
-- 而权限码的**增量**该有自己的迁移脚本，这样 DBA 能看清这次到底加了什么。
--
-- ## 三个码
--
--   ask.admin.models        能看模型列表（只读，不含密钥）
--   ask.admin.models.write  新增 / 编辑 / 启停
--   ask.admin.models.test   测试连通性
--
-- **测试单独一个码**：它会真发一次请求、产生真实费用。
-- 与数据源的 `ask.admin.datasources.test` 同一取舍。
-- =====================================================================

-- 给现有的「问数管理台」角色补上新码。
-- 用 INSERT IGNORE + 子查询而不是硬编码 role_id：角色 id 各环境不同。
INSERT IGNORE INTO `iam_role_permission` (`role_id`, `code`)
SELECT r.`id`, p.`code`
FROM `iam_role` r
JOIN (
            SELECT 'ask.admin.models'      AS `code`
  UNION ALL SELECT 'ask.admin.models.write'
  UNION ALL SELECT 'ask.admin.models.test'
) p
WHERE r.`tenant_id` = 't_default'
  AND r.`code` = 'chat2bi';

-- 核对：应当能看到 chat2bi 的三条新权限
SELECT r.`code` AS role_code, p.`code` AS permission
FROM `iam_role` r
JOIN `iam_role_permission` p ON p.`role_id` = r.`id`
WHERE r.`tenant_id` = 't_default' AND r.`code` = 'chat2bi'
  AND p.`code` LIKE 'ask.admin.models%'
ORDER BY p.`code`;

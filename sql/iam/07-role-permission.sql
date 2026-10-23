-- =====================================================================
-- 角色的功能权限（页面 / 按钮 / tab）
--
-- 与 iam_policy 的区别：
--   iam_policy      = 数据权限（能看哪些行、哪些列），按资源存整份文档
--   iam_role_permission = 功能权限（能看到哪些页面与操作），按角色存一个集合
-- 两者互不相干，见 ADR-0004 的三分（ADMIN / DATA / FUNCTION）。
--
-- 为什么用关联表而不是 JSON 列：取"某用户的全部权限"是一条 join，
-- 而且能在 code 上建索引回答"谁有这项权限"。那份集合没有整体语义，
-- 不需要像 perm_json 那样整份解析。
--
-- 权限码本身定义在代码里（PermissionCatalog），这里只存"哪条角色有哪些码"。
-- 未知码会在保存时被拒绝，所以库里不会积累永远不会生效的脏码。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `iam_role_permission` (
  `role_id`    BIGINT      NOT NULL,
  `code`       VARCHAR(64) NOT NULL,
  `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`role_id`, `code`),
  -- 反向查询："哪些角色有这项权限"。审计与排查用
  KEY `idx_iam_role_permission_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色的功能权限';

-- ── 播种 ──────────────────────────────────────────────────────────────
--
-- 必须**早于**后端开始按权限判定：否则问数页的图表 tab 与反馈按钮
-- 会凭空消失（前端按权限隐藏，而这两个角色还没配任何码）。
--
-- 按 code 关联而不是按 id：角色 id 在不同环境里不同。code 写错时
-- 下面的 INSERT 会静默插 0 行，所以附了一条校验 SELECT，跑完要眼看一遍。
--
-- 不播种 super_admin：它在代码里展开为全部权限（内置超管，见 PermissionCatalog）。
-- chat2bi 的预置权限在 08-bootstrap.sql 里（它是普通角色，可编辑）。
-- 不播种审计员：它现在本来就没有管理面入口，改完仍然是零，符合预期。
INSERT IGNORE INTO `iam_role_permission` (`role_id`, `code`)
SELECT r.`id`, c.`code`
FROM `iam_role` r
JOIN (
            SELECT 'ask.ui.charts'           AS `code`
  UNION ALL SELECT 'ask.ui.feedback'
  UNION ALL SELECT 'ask.ui.feedback.correct'
) c
WHERE r.`tenant_id` = 't_default'
  AND r.`code` IN ('区域经理', '分析师');

-- ── 核对 ──────────────────────────────────────────────────────────────
-- 期望：区域经理 3 行、分析师 3 行。若某个角色是 0，说明角色码写错了。
SELECT r.`code` AS role_code, COUNT(p.`code`) AS permission_count
FROM `iam_role` r
LEFT JOIN `iam_role_permission` p ON p.`role_id` = r.`id`
WHERE r.`tenant_id` = 't_default'
GROUP BY r.`code`
ORDER BY r.`code`;

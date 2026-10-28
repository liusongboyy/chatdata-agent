-- =====================================================================
-- 组织编码路径 code_path
--
-- 行规则的动态变量需要「当前用户组织的编码路径」，形如 /HQ/HQ-CVS/SUB-EAST/。
-- 已有的 `path` 是 **id 物化路径**（/1/100/110/）——它稳定，但业务库不认 id，
-- 业务表里存的通常是组织编码，因此需要一份编码版。
--
-- 为什么物化成一列而不是查询时现算：
--   code 与 parent_id 创建后都不可变（见 AdminOrgService.update 的说明），
--   所以编码路径同样"写一次、永不漂移"，维护方式与 path 一致。
--   现算要递归 CTE，而这条路径在每次问数时都要取，不值得。
--
-- 幂等：可重复执行。MySQL 8.4 没有 ADD COLUMN IF NOT EXISTS，用 information_schema 判。
-- =====================================================================

SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'iam_org' AND COLUMN_NAME = 'code_path'
);
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `iam_org` ADD COLUMN `code_path` VARCHAR(1000) NOT NULL DEFAULT ''''
     COMMENT ''编码物化路径，如 /HQ/HQ-CVS/SUB-EAST/（给业务库看）'' AFTER `path`',
  'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 回填存量行：按父链自顶向下拼，父在前、子树在后，一趟递归即可。
-- 只填空的，重跑不会覆盖已被管理员修正过的值。
WITH RECURSIVE tree AS (
  SELECT id, CONCAT('/', code, '/') AS cp
  FROM `iam_org`
  WHERE parent_id IS NULL
  UNION ALL
  SELECT o.id, CONCAT(t.cp, o.code, '/')
  FROM `iam_org` o
  JOIN tree t ON o.parent_id = t.id
)
UPDATE `iam_org` o
JOIN tree t ON t.id = o.id
SET o.code_path = t.cp
WHERE o.code_path = '';

-- 行规则 op=前缀 查子树时走它。
-- 用前缀索引（191 字符）：utf8mb4 下整列 1000 字符 = 4000 字节，超过 InnoDB 的 3072 上限。
-- 191 足以覆盖真实的组织层级深度，且能匹配上 `LIKE 'xxx%'` 的前缀扫描。
SET @has_idx := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'iam_org' AND INDEX_NAME = 'idx_iam_org_code_path'
);
SET @ddl := IF(@has_idx = 0,
  'ALTER TABLE `iam_org` ADD KEY `idx_iam_org_code_path` (`tenant_id`, `code_path`(191))',
  'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

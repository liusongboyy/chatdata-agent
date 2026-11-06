-- =====================================================================
-- 审计表加索引：给健康度看板的查询用
--
-- 依据：chatdata-agent-design/health-dashboard/spec.md
--
-- 健康度看板每次打开都要做一次：
--   SELECT domain_id, outcome, error_message
--   FROM ask_audit_log
--   WHERE created_at >= NOW() - INTERVAL N DAY
--
-- 现有 idx_created_at 只能定位行，取 domain_id/outcome 还要回表。
-- 这组列让「定位窗口内的行」变成 index-only：
--
--   EXPLAIN ... SELECT domain_id, outcome ...
--     → Extra: Using where; Using index      ← 不回表
--
-- ## 为什么不把 error_message 也放进索引
--
-- 试过 error_message(191)，结果反而更差：
--   · 前缀索引取不到完整值，MySQL 仍需回表 → Extra 变成 Using index condition
--   · 索引宽度增加不少（191 字符 × 每条）
--
-- 而 error_message 是归类失败原因的**必需输入**，不能从查询里去掉。
-- 所以接受"每条匹配行回表一次取它"——那已经从全表扫描降到 O(窗口行数)。
--
-- ## 什么时候该换方案
--
-- 这个索引解决的是"定位行"，不解决"每次都要全量扫窗口内的行"。
-- 窗口内行数到几万后，实时聚合本身就慢了——那时要改成预聚合表，
-- 而不是继续加宽索引。见 spec §「什么情况下需要改」。
--
-- ## 顺序
--
-- created_at 必须在最左：它是范围条件，放后面 B+ 树无法利用范围剪枝。
-- 后两列的顺序按区分度排列：domain_id 基数高于 outcome。
-- =====================================================================

SET @has_idx := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ask_audit_log'
    AND INDEX_NAME = 'idx_health_window'
);
SET @ddl := IF(@has_idx = 0,
  'CREATE INDEX `idx_health_window`
     ON `ask_audit_log` (`created_at`, `domain_id`, `outcome`)',
  'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

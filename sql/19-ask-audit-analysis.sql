-- =====================================================================
-- 分析编排的审计字段
--
-- 一次分析 = 1 条主记录（step_no IS NULL）+ N 条步骤记录（step_no 从 1 开始）。
-- 主记录与步骤记录用 batch_id 关联；不用自增 parent_id，是因为那要求
-- "插入后拿回主键"，而审计记录是不可变 record，绕这一圈没有收益。
--
-- 兼容性：
--   * 既有查询不受影响——新增列全部可空。
--   * "提问列表"要加过滤条件 step_no IS NULL，否则会把步骤行也列出来。
--
-- **幂等**：全新部署的 04-audit.sql 已包含这些列，这里会跳过；
--           2026-10-26 之前建的库才需要它补列。
-- =====================================================================

SET @db := DATABASE();

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ask_audit_log' AND COLUMN_NAME = 'batch_id') = 0,
  'ALTER TABLE `ask_audit_log`
     ADD COLUMN `batch_id`  VARCHAR(32) DEFAULT NULL COMMENT ''一次分析的批次标识；单查询为空'',
     ADD COLUMN `step_no`   INT         DEFAULT NULL COMMENT ''步骤序号，从 1 开始；主记录与单查询为空'',
     ADD COLUMN `plan_json` TEXT               DEFAULT NULL COMMENT ''分析计划原文；仅主记录'',
     ADD COLUMN `narrative` TEXT               DEFAULT NULL COMMENT ''合成结论；仅主记录''',
  'SELECT ''ask_audit_log 已有分析字段，跳过'' AS skipped');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ask_audit_log' AND INDEX_NAME = 'idx_batch') = 0,
  'ALTER TABLE `ask_audit_log` ADD KEY `idx_batch` (`batch_id`, `step_no`)',
  'SELECT ''idx_batch 已存在，跳过'' AS skipped');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

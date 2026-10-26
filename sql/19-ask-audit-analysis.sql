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
-- =====================================================================

ALTER TABLE `ask_audit_log`
  ADD COLUMN `batch_id`  VARCHAR(32) DEFAULT NULL COMMENT '一次分析的批次标识；单查询为空',
  ADD COLUMN `step_no`   INT         DEFAULT NULL COMMENT '步骤序号，从 1 开始；主记录与单查询为空',
  ADD COLUMN `plan_json` TEXT               DEFAULT NULL COMMENT '分析计划原文；仅主记录',
  ADD COLUMN `narrative` TEXT               DEFAULT NULL COMMENT '合成结论；仅主记录';

-- 按批次还原一次分析
ALTER TABLE `ask_audit_log`
  ADD KEY `idx_batch` (`batch_id`, `step_no`);

-- 说明：MySQL 的 ADD COLUMN 不支持 IF NOT EXISTS（8.0 仍不支持），
-- 重复执行本脚本会报 1060。sql/README.md 里已注明迁移脚本按顺序执行一次。

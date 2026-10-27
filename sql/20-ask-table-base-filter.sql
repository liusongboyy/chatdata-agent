-- =====================================================================
-- 注册表的默认过滤条件
--
-- 背景：实测反复出现同一类错——问「2025 年保费合计」，模型自己补上
--       `AND status = '生效'`（它"觉得"过期的保单不算保费）。
--       那个判断不算离谱，但它是**猜的**：SQL 合法、数字合理，
--       只是少了三分之一，而用户看不出差别。
--
-- 更根本的是：**"什么算有效数据"是业务口径，不该由模型每次猜**。
-- 配在这里，渲染器固定拼进 WHERE，模型不必猜、也猜不错。
--
-- 安全性：片段只由管理员在建模时填写、**不经模型产出**，因此没有注入面。
--          渲染器仍会拒绝含占位符 `?` 的片段——那会打乱参数顺序。
--
-- 与 SQL 虚拟表的区别：
--   虚拟表（mode=SQL）适合"整张表的口径"（如 SELECT * FROM ticket WHERE deleted = 0），
--   它连表结构一起换掉；base_filter 只加条件、不动结构，适合"物理表 + 少量默认过滤"。
--
-- **幂等**：全新部署的 08-ask-engine.sql 已包含该列，这里会跳过。
-- =====================================================================

SET @db := DATABASE();

SET @sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ask_domain_table' AND COLUMN_NAME = 'base_filter') = 0,
  'ALTER TABLE `ask_domain_table`
     ADD COLUMN `base_filter` VARCHAR(512) DEFAULT NULL
     COMMENT ''默认过滤条件（原始 SQL 片段），如 status = ''''生效''''；空表示不过滤''',
  'SELECT ''ask_domain_table 已有 base_filter，跳过'' AS skipped');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

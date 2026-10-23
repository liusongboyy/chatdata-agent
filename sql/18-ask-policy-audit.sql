-- =====================================================================
-- 数据域/助理策略变更审计（ADR-0010 P1）
--
-- 记录"谁在什么时候把哪条策略改成了什么版本"。不存策略全文：
--   全文会随每次保存膨胀，而真正要回答的是变更事实与版本指纹。
--   perm_digest 是归一化（去空白）JSON 的 SHA-256；同一内容重复保存得到同一摘要。
--
-- 与 ask_audit_log（问数审计）分开：两者的查询者、留存与增长速率都不同，
-- 混在一张表里会让"查某人的提问"被迫扫过大量策略变更。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `jimureport`.`ask_policy_audit` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `resource_type` VARCHAR(16)  NOT NULL                COMMENT 'DOMAIN / ASSISTANT',
  `resource_id`   BIGINT       NOT NULL                COMMENT '域或助理 ID',
  `resource_name` VARCHAR(255) DEFAULT NULL            COMMENT '保存时的名称快照',
  `action`        VARCHAR(16)  NOT NULL                COMMENT 'CREATE / UPDATE',
  `operator`      VARCHAR(128) NOT NULL                COMMENT '操作人（SSO 用户名）',
  `perm_digest`   CHAR(64)     NOT NULL                COMMENT '策略 JSON 的 SHA-256（去空白）',
  `created_at`    DATETIME     NOT NULL                COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_resource` (`resource_type`, `resource_id`, `created_at`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_operator_created` (`operator`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据策略变更审计';

-- =====================================================================
-- ⚠️ 已退役（2026-10-23，ADR-0010）：本脚本只保留作历史记录。
--    数据策略已回归 ask 的 perm_json；请勿在全新部署中执行。
--    收尾动作见 11-retire-iam-policy.sql。
-- 数据策略（IAM）
--
-- 从 ask 的 ask_domain.perm_json / ask_assistant.perm_json 收敛过来：
-- **策略的唯一事实来源在 IAM**，引擎只负责解释（把行规则拼进 SQL）——
-- 这是 ADR-0002 的执行点约束，也是 spec §5.4 的拆分。
--
-- document 与旧的 perm_json 同构（v/grants/rowRules/colRules），
-- 这样迁移是"搬运 + 加版本号"，不是重新建模。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `iam_policy` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT,
  `tenant_id`     VARCHAR(32) NOT NULL DEFAULT 't_default',
  -- DOMAIN / ASSISTANT
  `resource_type` VARCHAR(32) NOT NULL,
  -- ask 侧的资源 id（域 id、助手 id），字符串以兼容两类资源
  `resource_id`   VARCHAR(64) NOT NULL,
  -- 策略文档（JSON）。与旧 perm_json 同构
  `document`      TEXT        NOT NULL,
  -- 每次写入 +1。引擎按它判断"策略变了没有"，而不是靠猜 TTL
  `version`       INT         NOT NULL DEFAULT 1,
  `updated_by`    VARCHAR(64) NULL,
  `created_at`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iam_policy_resource` (`tenant_id`, `resource_type`, `resource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据策略（DOMAIN / ASSISTANT）';

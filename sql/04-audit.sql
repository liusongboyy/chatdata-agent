-- =====================================================================
-- 问数审计日志
--
-- 依据：会话中已定的审计范围（「企业级」优先序第 2 位）——
--   提问原文 / 实际执行的 SQL / 返回行数 / 耗时 / 用户身份，留存 >= 180 天。
--
-- 关于「生成的 SQL」与「权限改写后的 SQL」：
--   早期第三方产品的响应里**只有一个 sql 字段**，且它已经是权限改写后的最终形态
--   （行级规则会以子查询包裹 + 外层 WHERE 的形式体现在其中）。改写前的原始 SQL
--   无法从响应中取得。因此本表不设该字段，改为同时记录 dsl 与 sql：
--     dsl = 语义意图（指标/维度/时间/筛选）
--     sql = 实际下发的语句
--   两者对照足以还原「想查什么」与「实际查了什么」，比记录一个拿不到的字段有用。
--
-- roles 记录的是**当次请求时的角色快照**，而非引用当前角色表：
--   权限事后可能变更，审计要能回答「他当时凭什么看到了这些数据」。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `ask_audit_log` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`      VARCHAR(64)  NOT NULL                COMMENT '提问者身份（SSO 用户名）',
  `roles`         VARCHAR(512) DEFAULT NULL            COMMENT '当次请求的角色快照，逗号分隔',
  `question`      TEXT                                 COMMENT '提问原文',
  `assistant_id`  VARCHAR(32)  DEFAULT NULL            COMMENT '助手 ID',
  `domain_id`     VARCHAR(32)  DEFAULT NULL            COMMENT '数据域 ID',
  `dsl_json`      TEXT                                 COMMENT '语义意图（指标/维度/时间/筛选）',
  `sql_text`      TEXT                                 COMMENT '实际执行的 SQL（已含权限改写）',
  `row_count`     INT          DEFAULT NULL            COMMENT '返回行数；失败时为空',
  `duration_ms`   BIGINT       NOT NULL                COMMENT '端到端耗时（毫秒）',
  `outcome`       VARCHAR(16)  NOT NULL                COMMENT '结果：SUCCESS / FAILURE',
  `error_message` VARCHAR(512) DEFAULT NULL            COMMENT '失败原因摘要，成功时为空',
  `created_at`    DATETIME     NOT NULL                COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_username_created` (`username`, `created_at`),
  KEY `idx_assistant_created` (`assistant_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问数审计日志';

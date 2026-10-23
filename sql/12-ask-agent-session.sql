-- =====================================================================
-- 问数引擎：多轮会话状态
--
-- 依据：.scratch/self-built-chatbi/spec.md §5 FR-5.2（多轮追问）
--
-- ⚠️ 这张表**不是**我们设计的，是 agentscope 的 `AgentStateStore` 契约。
--
--    `MysqlAgentStateStore` 会自己 `CREATE TABLE IF NOT EXISTS`，字段与主键
--    必须与它期望的一致，否则会出现"能写进去、读出来解析失败"这种最难查的错。
--    本文件是把那份 DDL 固化下来，供**没有 CREATE 权限**的环境手工执行。
--
--    两边共存是安全的：表已存在时它那句 CREATE 是空操作，
--    ensureVersionColumn 查的是 INFORMATION_SCHEMA，也不会重复加列。
--
-- 为什么不用 agentscope 的默认值：
--
--   1. 默认表名 `agentscope_sessions`，且默认落在**独立的 `agentscope` 库**
--      （spike E7 实测：不显式允许建库会直接抛 Database does not exist）。
--      生产账号通常只被授予单库权限，那样会起不来。这里改用业务库 + `ask_` 前缀，
--      与厂商的 `chat2bi_*` 隔离（PRD §6）。
--
--   2. 默认没有 `updated_at` 索引。这张表按会话累积，将来做清理
--      （参照 AskAuditRetentionJob）必然要按时间扫，先把这个索引留下。
--
-- 存储形态：
--
--   `state_key='agent_state'` 的**单条**记录承载整段会话，
--   `state_data` 是 AgentState 的 JSON（含 context 消息数组）。
--   因此"一条会话一行"，`item_index` 恒为 0；agentscope 的列表状态才用得上高位 index。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `ask_agent_session` (
  `session_id`  VARCHAR(255) NOT NULL COMMENT '会话 id（与 user_id 共同构成存储键）',
  `state_key`   VARCHAR(255) NOT NULL COMMENT '状态键。问数链路固定为 agent_state',
  `item_index`  INT          NOT NULL DEFAULT 0 COMMENT '列表状态的分片序号；单状态恒为 0',
  `state_data`  LONGTEXT     NOT NULL COMMENT 'AgentState 的 JSON（含对话上下文）',
  -- 乐观并发用。agentscope 的 CAS 写靠它判定"读到之后有没有别人改过"
  `version`     BIGINT       NOT NULL DEFAULT 0 COMMENT '版本号，每次写入自增',
  `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`session_id`, `state_key`, `item_index`),
  KEY `idx_ask_agent_session_updated_at` (`updated_at`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
  COMMENT '问数引擎的多轮会话状态；由 agentscope AgentStateStore 读写';

-- 会话清理（可选，按需调整天数）。
-- 留着不删不会出错，只是旧会话一直占着——但**不能只是删表了事**：
-- 删掉之后该会话的第 2 轮会退化成"没有上下文的新问题"，用户感觉是"它忘了"。
-- 所以清理周期应当明显长于"人还记得这次对话"的时间。
--
-- DELETE FROM `ask_agent_session` WHERE `updated_at` < NOW() - INTERVAL 30 DAY;

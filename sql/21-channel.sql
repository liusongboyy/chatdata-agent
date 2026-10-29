-- =====================================================================
-- 渠道入站去重
--
-- 依据：.scratch/feishu-channel/spec.md §3.4
--
-- 为什么用表而不是 Redis：
--   飞书的重推间隔是 15 秒 → 5 分钟 → 1 小时 → 6 小时，最多 4 次。
--   最晚一次在 **6 小时后**。所以去重窗口必须 ≥ 6 小时——那已经超出
--   「缓存」的语义，而丢失去重记录会导致用户收到重复回答。
--
-- 清理：保留 24 小时（留余量），由 ChannelDedupCleanupJob 定期删除。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `channel_inbound_dedup` (
  `channel`     VARCHAR(32)  NOT NULL COMMENT '渠道标识，如 feishu',
  `message_id`  VARCHAR(128) NOT NULL COMMENT '飞书 message_id。**不是 event_id**——见 spec §3.5',
  `chat_key`    VARCHAR(128) NOT NULL COMMENT '会话键，排查"这条消息来自哪个会话"时有用',
  `received_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`channel`, `message_id`),
  KEY `idx_dedup_received` (`received_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '渠道入站消息去重';

-- 渠道会话映射：chat_key → ask 的 conversation id。
-- 一问一答的 IM 交互需要它才能支持「那上个月呢」这种追问。
CREATE TABLE IF NOT EXISTS `channel_session` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT,
  `channel`         VARCHAR(32)  NOT NULL,
  `chat_key`        VARCHAR(128) NOT NULL COMMENT '私聊=open_id；群聊=chat_id:open_id（按人隔离）',
  `username`        VARCHAR(64)  NOT NULL COMMENT '归属人。群聊里换人必须换会话',
  `conversation_id` VARCHAR(64)  NOT NULL COMMENT 'ask_conversation.id',
  `last_active_at`  DATETIME     NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_chat` (`channel`, `chat_key`),
  KEY `idx_channel_last_active` (`last_active_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '渠道会话映射';

-- ── 助理选择 ──
-- 用户有多个可用助理时，channel 必须知道这一问该走哪个。
-- 不指定的话 ask 会落到「第一个启用中的域」，而那往往是保险域——
-- 问门店的问题会得到"没能理解"（或更糟：被保险域勉强答成别的指标）。
SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'channel_session'
    AND COLUMN_NAME = 'assistant_id'
);
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `channel_session` ADD COLUMN `assistant_id` VARCHAR(32) NULL
     COMMENT ''当前会话选中的助理；空表示尚未选择'' AFTER `username`',
  'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

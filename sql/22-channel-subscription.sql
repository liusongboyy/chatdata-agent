-- =====================================================================
-- 渠道定时订阅：让「每天 9 点把昨天各门店销售额发到群里」这类需求成立
--
-- 依据：.scratch/roadmap/README.md §2.2
--
-- 设计要点在下面几段，改表之前先读：
--
-- ① 推送用**订阅创建人**的令牌，不是机器人自己的。
--    后者是越权——审计里看不出是谁要的数据，而且创建人被收回权限后
--    订阅还会继续发。用创建人的令牌，权限变化会自然生效。
--
-- ② 同一 (channel, chat_key, question) 唯一。
--    否则同一个人重复配两次，群里会收到两份一模一样的卡片。
--
-- ③ last_run_at / last_result 用于排查「为什么今天没发」。
--    只说"没发"而不说是被权限拒了还是查空了，等于没告警。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `channel_subscription` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `channel`       VARCHAR(32)  NOT NULL COMMENT '渠道标识，如 feishu',
  `chat_key`      VARCHAR(128) NOT NULL COMMENT '发给谁：私聊=open_id；群聊=chat_id',
  `username`      VARCHAR(64)  NOT NULL COMMENT '**订阅创建人**。推送时用他的令牌查数，权限与之一致',
  `question`      VARCHAR(500) NOT NULL COMMENT '要问的问题，与手动提问完全等价',
  `assistant_id`  VARCHAR(32)  NULL     COMMENT '走高管的哪个助理；空表示用默认',
  `cron`          VARCHAR(64)  NOT NULL COMMENT 'Spring cron（6 段，秒 分 时 日 月 周）',
  `enabled`       TINYINT(1)   NOT NULL DEFAULT 1,
  `last_run_at`   DATETIME     NULL     COMMENT '上次尝试执行的时间（无论成败）',
  `last_status`   VARCHAR(32)  NULL     COMMENT 'OK / DENIED / EMPTY / ERROR，排查用',
  `last_error`    VARCHAR(500) NULL     COMMENT '失败原因。**必须留**——只说"没发"没法排查',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_subscription` (`channel`, `chat_key`, `question`),
  KEY `idx_subscription_enabled` (`enabled`, `channel`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '渠道定时订阅';

-- 执行日志：保留每一轮的结果，用于「上周那条为什么没发」这类回溯。
-- 不放进 subscription 表是因为那只能存最后一次，而排查常常要看历史。
CREATE TABLE IF NOT EXISTS `channel_subscription_run` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT,
  `subscription_id` BIGINT       NOT NULL,
  `started_at`      DATETIME     NOT NULL,
  `finished_at`     DATETIME     NULL,
  `status`          VARCHAR(32)  NOT NULL COMMENT 'OK / DENIED / EMPTY / ERROR',
  `row_count`       INT          NULL,
  `message`         VARCHAR(500) NULL,
  PRIMARY KEY (`id`),
  KEY `idx_run_subscription` (`subscription_id`, `started_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订阅执行日志';

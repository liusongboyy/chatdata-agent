-- =====================================================================
-- 分析报告
--
-- 依据：.scratch/report/spec.md
--
-- ## 为什么正文存 JSON 而不是拆表
--
-- 报告是**快照**。拆表意味着正文结构一改就要迁移，而报告一旦生成就不该
-- 跟着变——三个月后有人打开它，看到的必须还是当时那份。
--
-- 这与 ask_message.result_json 同一个理由。
--
-- ## 为什么记 source_message_ids
--
-- 报告是从哪几条问答拼出来的。用于「这份报告的依据是什么」的追溯，
-- 也用于判断"原消息删了但报告还在"这种情况。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `ask_report` (
  `id`                 VARCHAR(64)  NOT NULL,
  `conversation_id`    VARCHAR(64)  NOT NULL COMMENT '来源会话',
  `username`           VARCHAR(64)  NOT NULL COMMENT '生成人',
  `title`              VARCHAR(255) NOT NULL COMMENT '模型生成的标题；失败时用 intent',
  `intent`             VARCHAR(500) NULL     COMMENT '用户原始问题，标题降级用',
  -- 报告正文。**整份存 JSON**，见文件头
  `body_json`          LONGTEXT     NOT NULL,
  -- 逗号分隔的 ask_message.id。用 TEXT 而不是 JSON 数组：
  -- 只是一串 id，用 JSON 反而要在两处解析
  `source_message_ids` TEXT         NOT NULL,
  `model_key`          VARCHAR(32)  NULL     COMMENT '生成标题用的模型；NULL 表示降级路径',
  `generated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `deleted`            TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_report_conv` (`conversation_id`, `deleted`),
  KEY `idx_report_user` (`username`, `deleted`, `generated_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '分析报告';

-- 核对
SELECT COUNT(*) AS report_rows FROM `ask_report`;

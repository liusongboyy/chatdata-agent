-- =====================================================================
-- 嵌入式问数：宿主系统与助理的授权关系
--
-- 依据：.scratch/platform/spec.md §3
--
-- ## 这张表管什么
--
-- 「哪个宿主系统可以问哪个助理」。宿主系统 = 一个 OAuth 客户端
-- （如 CRM），它替自己的用户换令牌，但只能问被授权的助理。
--
-- ## 为什么单独一张表而不是靠 scope
--
-- scope 表达「能做什么动作」（ask.read），这里要表达「能问哪个助理」——
-- 那是**数据范围**，不是动作。把助理编进 scope 会得到 ask.assistant.2200
-- 这种码，而助理是随时可增删的配置，等于让 OAuth 客户端注册
-- 跟着业务配置一起变。
--
-- ## 为什么没有 created_at 的默认值之外的字段
--
-- 授权只有「有」与「没有」两种状态，撤销用 revoked_at 而不是删行——
-- 删了之后「这个宿主曾经能问这个助理吗」就查不到了。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `iam_embed_client_assistant` (
  `client_id`    VARCHAR(64) NOT NULL COMMENT '宿主系统的 OAuth client_id',
  `assistant_id` VARCHAR(32) NOT NULL COMMENT '允许问的助理 id（ask_assistant.id，跨库故无外键）',
  `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `revoked_at`   DATETIME    NULL COMMENT '撤销时间。NULL 表示仍然有效',
  PRIMARY KEY (`client_id`, `assistant_id`),
  KEY `idx_embed_assistant` (`assistant_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '嵌入式问数：宿主系统可问的助理';

-- =====================================================================
-- 用户映射
--
-- 宿主系统给的 user_ref 要能映射到本系统账号。复用 iam_user 的
-- external_provider / external_id 两列——channel 用的也是它们。
--
-- 区别只在 provider 值：channel 是 'feishu'，embed 是 'embed'。
-- 一个人可以同时有飞书绑定与 embed 映射吗？**不能**，因为这两列是单值的。
-- 这是既有设计的限制，暂不改：实际场景里两种接入方式很少同时用。
-- =====================================================================

-- 给 external 映射加索引：embed 每次换令牌都要按它查一次
SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'CREATE INDEX `idx_user_external` ON `iam_user` (`external_provider`, `external_id`)',
    'DO 0')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'iam_user'
    AND INDEX_NAME = 'idx_user_external'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 核对：表建好了吗
SELECT COUNT(*) AS embed_grant_rows FROM `iam_embed_client_assistant`;

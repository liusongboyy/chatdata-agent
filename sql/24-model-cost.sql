-- =====================================================================
-- 问数使用的模型
--
-- 依据：chatdata-agent-design/model-cost/spec.md
--
-- ## 为什么放数据库而不是 yml
--
-- 模型是**业务决策**（哪个角色能用哪个、单价多少），不是部署参数。
-- 配置在文件里就没法从管理台改，改一次要重启一次——而单价变化、
-- 新模型上线都很频繁。
--
-- ## 四条约束为什么做在数据库层
--
--   ① model_key 唯一 —— 审计里存的是它，重复了成本统计会串
--   ② allowed_roles 非 NULL —— NULL 与空数组含义**相反**
--      （NULL 像"还没配"，空数组是"不限制"），混用会造成默认放行
--   ③ is_default 用应用层保证唯一，但加索引让查询快
--   ④ api_key 密文 —— 与数据源密码同一套 PasswordCipher
-- =====================================================================

CREATE TABLE IF NOT EXISTS `ask_model` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT,
  `model_key`     VARCHAR(32)   NOT NULL COMMENT '稳定标识，审计里存它。改了会让历史成本统计断链',
  `display_name`  VARCHAR(64)   NOT NULL COMMENT '给用户看的名字，如「DeepSeek V4 Flash（快速）」',
  `description`   VARCHAR(200)  NULL     COMMENT '用途说明，写给人看而不是写参数',

  -- ── 供应商 ──
  -- 只认 base_url：火山方舟 / OpenAI / 百炼 / DeepSeek 官方都兼容 OpenAI 协议。
  -- **不做厂商枚举校验**——自定义选项让管理员接 vLLM / Ollama / 代理
  `base_url`      VARCHAR(500)  NOT NULL,
  `api_key_enc`   VARCHAR(500)  NULL     COMMENT '密文。NULL 表示未配（本地模型可能不需要）',
  `model_name`    VARCHAR(128)  NOT NULL COMMENT '供应商侧的模型名',

  -- ── 单价（元 / 百万 token）──
  -- **允许 NULL** 表示未配单价。那时能算 token 不能算钱，
  -- 看板显示「未配单价」而不是 0——0 元比"不知道"危险得多
  `price_input`   DECIMAL(12,6) NULL,
  `price_cached`  DECIMAL(12,6) NULL COMMENT '缓存命中的输入，多数供应商单独计价（通常便宜十倍）',
  `price_output`  DECIMAL(12,6) NULL,
  `priced_at`     DATETIME      NULL     COMMENT '这套单价是哪天生效的。价格会变，看板要能说明',

  -- ── 权限 ──
  -- JSON 数组，**空数组表示不限制**。
  -- 不能存 NULL 表示"不限制"——NULL 与"还没配"无法区分，而两者含义相反
  `allowed_roles` JSON          NOT NULL,

  `is_default`    TINYINT(1)    NOT NULL DEFAULT 0,
  `enabled`       TINYINT(1)    NOT NULL DEFAULT 1,
  `created_by`    VARCHAR(64)   NULL,
  `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_key` (`model_key`),
  KEY `idx_model_default` (`is_default`, `enabled`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '问数使用的模型';

-- =====================================================================
-- 审计表：记录每次提问用了哪个模型、消耗多少
--
-- **全部允许 NULL**：
--   · 加列之前的历史记录没有这些值
--   · 失败的请求可能拿不到 usage
-- NULL 是"没采到"，0 是"确实没消耗"——两者不能混
-- =====================================================================

SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `ask_audit_log` ADD COLUMN `model_key` VARCHAR(32) NULL COMMENT ''用的哪个模型''',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ask_audit_log'
    AND COLUMN_NAME = 'model_key'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `ask_audit_log` ADD COLUMN `input_tokens` INT NULL COMMENT ''输入 token。**已包含 cached_tokens**''',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ask_audit_log'
    AND COLUMN_NAME = 'input_tokens'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `ask_audit_log` ADD COLUMN `output_tokens` INT NULL COMMENT ''输出 token。**已包含 reasoning_tokens**''',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ask_audit_log'
    AND COLUMN_NAME = 'output_tokens'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `ask_audit_log` ADD COLUMN `cached_tokens` INT NULL COMMENT ''缓存命中的输入 token。是 input_tokens 的**子集**''',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ask_audit_log'
    AND COLUMN_NAME = 'cached_tokens'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `ask_audit_log` ADD COLUMN `cache_creation_tokens` INT NULL COMMENT ''写缓存消耗（部分供应商单独计费）''',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ask_audit_log'
    AND COLUMN_NAME = 'cache_creation_tokens'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `ask_audit_log` ADD COLUMN `reasoning_tokens` INT NULL COMMENT ''推理 token。是 output_tokens 的**子集**，不能单独计费''',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ask_audit_log'
    AND COLUMN_NAME = 'reasoning_tokens'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 成本看板按时间窗口 + 模型聚合，这个索引让它 index-only
SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'CREATE INDEX `idx_audit_cost` ON `ask_audit_log` (`created_at`, `model_key`)',
    'DO 0')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ask_audit_log'
    AND INDEX_NAME = 'idx_audit_cost'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =====================================================================
-- 域：可选的模型绑定
-- =====================================================================

SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `ask_domain` ADD COLUMN `model_key` VARCHAR(32) NULL COMMENT ''域绑定的模型；NULL 表示不限制，用户在角色允许范围内自选''',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ask_domain'
    AND COLUMN_NAME = 'model_key'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =====================================================================
-- 问数引擎：助理
--
-- 依据：.scratch/self-built-chatbi/spec.md §5 FR-4
--
-- ⚠️ 助理层**不是数据保护点**。
--
--    ADR-0002 实测：助手层拦下返回**明确错误**（"您没有该助理的访问权限"），
--    域层拦下返回 **0 行**。前者体验更好，但也说明助手层只决定
--    "这个助理可不可见/可用"，**数据保护由域层的行/列规则承担**。
--    不要把两者混为一谈，也不要把助理权限当成数据闸门。
--
-- ⚠️ 判据与域层**不同且不可统一**：
--
--     域：grantRoles/grantUsers 有内容，或 isPublic
--     助手：enabled=true **且** 主体在授权名单内
--
--    域看"有没有授权内容"，助手看"开关是否打开"——这是厂商各自默认值的差异。
--    本项目把"缺 enabled 键"与 false 一视同仁（厂商此处是**放行**）。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `ask_assistant` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`          VARCHAR(100) NOT NULL                COMMENT '助理名称',
  `avatar`        VARCHAR(20)  NULL                    COMMENT '头像 emoji',
  `description`   VARCHAR(500) NULL                    COMMENT '描述',
  `welcome`       VARCHAR(500) NULL                    COMMENT '欢迎语',
  -- 主域。多域时它是默认落点，也是唯一被真正使用的那个（见下方 routing 说明）
  `domain_id`     BIGINT       NULL                    COMMENT '主数据域 id',
  -- 全部绑定域。**当前只用于展示与未来的智能路由**——见文件末尾说明
  `domain_ids`    VARCHAR(1000) NULL                   COMMENT '绑定的全部数据域 id 列表 JSON ["1","2"]',
  `show_sql`      TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '是否展示 SQL 1/是 0/否',
  `status`        VARCHAR(20)  NOT NULL DEFAULT 'draft' COMMENT '状态 online/已上线，draft/草稿，offline/已下线',
  `questions_json` TEXT        NULL                    COMMENT '推荐问题 JSON ["...",...]',
  `perm_json`     TEXT         NULL                    COMMENT '访问权限 JSON {enabled,grantRoles,grantUsers}',
  `sort_no`       INT          NOT NULL DEFAULT 0      COMMENT '排序号',
  `created_by`    VARCHAR(64)  NULL                    COMMENT '创建人',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by`    VARCHAR(64)  NULL                    COMMENT '更新人',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ask_assistant_name` (`name`),
  KEY `idx_ask_assistant_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问数引擎-助理';

-- ---------------------------------------------------------------------
-- 关于 domain_ids（多域）与"智能路由"
--
-- PRD FR-4.2 要求「绑定数据域，支持多域（问数时智能路由）」。
-- 真正的智能路由要让模型判断"这个问题该落到哪个域"——那是独立的一块工作
-- （需要把所有域的字段清单喂给模型，并处理跨域问题）。
--
-- 本轮**不假装有路由**：
--   - 问数时使用主域（domain_id）
--   - 助理绑定了多个域时，界面让**用户自己选**用哪个
--   - 服务端日志会记一条提示，说明多域路由尚未实现
--
-- 这样多域信息是有用的（用户能选），而不是一个存了不生效的字段。
-- ---------------------------------------------------------------------

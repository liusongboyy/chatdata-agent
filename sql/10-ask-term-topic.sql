-- =====================================================================
-- 问数引擎：业务术语 / 查询主题
--
-- 依据：chatdata-agent-design/self-built-chatbi/spec.md §5 FR-3.5 / FR-3.6
--
-- ⚠️ apply_kind 目前只用到 'none'（仅释义）。
--
--    厂商的模型有三种：none / time（时间口径）/ filter（数据筛选）。
--    后两种要**真正作用于渲染**才有意义——把口径写进 WHERE 或替换时间范围。
--    本轮只把术语作为**近义词与释义**喂进 prompt，那是解决
--    「用户说『保费合计』而登记的名字是『保费』」这个实际问题的正解。
--
--    存下 apply_kind 是为了将来扩展时不必改表，但**界面只暴露「仅释义」**——
--    提供一个点了不生效的选项，比不提供更糟。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 业务术语
--
-- synonyms_json 是这里的核心：它让模型知道用户的各种说法对应哪个登记字段。
-- 没有它时，模型会跟着用户的措辞走（实测：问「保费合计」产出
-- metrics:["保费合计"]，而登记的别名是「保费」），只能靠重试补救。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ask_term` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `domain_id`      BIGINT       NOT NULL                COMMENT '所属数据域 id',
  `name`           VARCHAR(100) NOT NULL                COMMENT '术语名称。通常是登记的字段别名',
  `synonyms_json`  TEXT         NULL                    COMMENT '近义词 JSON ["...",...]。用户可能的各种说法',
  `description`    VARCHAR(500) NULL                    COMMENT '释义。会进 prompt，写给模型看',
  -- 见文件头的说明：目前只写 'none'
  `apply_kind`     VARCHAR(20)  NOT NULL DEFAULT 'none' COMMENT '应用方式 none/time/filter（目前只用 none）',
  `apply_time`     VARCHAR(50)  NULL                    COMMENT '时间口径取值（apply_kind=time，暂未启用）',
  `apply_clause`   VARCHAR(500) NULL                    COMMENT '追加过滤条件（apply_kind=filter，暂未启用）',
  `sort_no`        INT          NOT NULL DEFAULT 0      COMMENT '排序号',
  `created_by`     VARCHAR(64)  NULL                    COMMENT '创建人',
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by`     VARCHAR(64)  NULL                    COMMENT '更新人',
  `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_ask_term_domain` (`domain_id`),
  UNIQUE KEY `uk_ask_term_name` (`domain_id`, `name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问数引擎-业务术语';

-- ---------------------------------------------------------------------
-- 查询主题
--
-- fields_json 是一组「表.列」的引用，用来收敛模型的取数范围。
-- 域内表多的时候，主题把"这次问什么"缩到一个子集，prompt 更短、选错表的概率更低。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ask_topic` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `domain_id`    BIGINT       NOT NULL                COMMENT '所属数据域 id',
  `name`         VARCHAR(100) NOT NULL                COMMENT '主题名称',
  `description`  VARCHAR(500) NULL                    COMMENT '描述',
  `status`       VARCHAR(20)  NOT NULL DEFAULT 'draft' COMMENT '状态 published/已发布，draft/草稿',
  `fields_json`  TEXT         NULL                    COMMENT '主题字段 JSON ["表名.列名",...]',
  `sort_no`      INT          NOT NULL DEFAULT 0      COMMENT '排序号',
  `created_by`   VARCHAR(64)  NULL                    COMMENT '创建人',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by`   VARCHAR(64)  NULL                    COMMENT '更新人',
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_ask_topic_domain` (`domain_id`),
  UNIQUE KEY `uk_ask_topic_name` (`domain_id`, `name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问数引擎-查询主题';

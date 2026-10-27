-- =====================================================================
-- 自研问数引擎：数据源 / 语义域 / 域内注册表
--
-- 依据：docs/adr/0006-self-build-ask-engine-supersedes-integration.md
--       .scratch/self-built-chatbi/spec.md §6
--
-- ⚠️ 表前缀 ask_，与厂商的 chat2bi_* 完全隔离。
--    理由：ADR-0006 决定两者并行运行、切换方式另行决策。
--    共用表会让并行期无法回退，也让"哪些数据是我们的"变得说不清。
--
-- ⚠️ 不加 tenant_id 列。厂商每张表都有，但本项目是单租户
--    （ChatbiTokenService.getTenantId() 返回 null），加了就是永远为空的死列。
--    若将来启用多租户，再单独加一版迁移。
--
-- 本文件只建**当前真正要用的**三张表。topic / term / assistant /
-- conversation / message 等表等对应功能落地时再加——
-- DDL 与代码同步比"一次建全"更重要，否则半年后没人知道哪张表是活的。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 数据源
--
-- 与第三方产品的同类表并存但互不依赖。
-- 注意 db_type 的取值必须落在 SqlDialects 注册表里，
-- 否则渲染阶段会抛 UnsupportedDialectException（不回落默认方言）。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ask_datasource` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`          VARCHAR(100) NOT NULL                COMMENT '数据源名称',
  `db_type`       VARCHAR(32)  NOT NULL                COMMENT '类型，如 MYSQL5.7 / Doris / POSTGRESQL / ORACLE / SQLSERVER',
  `driver_class`  VARCHAR(200) NOT NULL                COMMENT 'JDBC 驱动类名',
  `url`           VARCHAR(1000) NOT NULL               COMMENT 'JDBC URL',
  `username`      VARCHAR(200) NULL                    COMMENT '数据库用户名',
  -- 加密存储，绝不落明文。密钥来自 chatdata.engine.datasource.secret，
  -- 见 PasswordCipher 的说明。长度留足 AES-GCM 的 base64 结果
  `password_enc`  VARCHAR(500) NULL                    COMMENT '数据库密码（AES-GCM 加密后的 base64）',
  `enabled`       TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否启用',
  `created_by`    VARCHAR(64)  NULL                    COMMENT '创建人',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by`    VARCHAR(64)  NULL                    COMMENT '更新人',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ask_datasource_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问数引擎-数据源';

-- ---------------------------------------------------------------------
-- 语义域
--
-- perm_json 沿用厂商的 JSON blob 形态（PRD §6 已决定不拆表），
-- 但**多了两个厂商没有的字段**：
--   v                 —— schema 版本号。不拆表意味着演进只能靠约定，
--                        没有版本号的话半年后没人知道某条 JSON 是哪个版本写的
--   unrestrictedRoles —— 显式声明"谁可以看全量行"。厂商的语义是
--                        "没配 rowRules 即全量可见"，那是默认放行，
--                        我们要的是显式声明（见 RowScope 的说明）
--
-- joins_json 结构：[{id,left,right,leftCol,rightCol,type}]
--   left/right 存的是**注册表 id**（已实测确认，不是表名），
--   type 取值形如 "LEFT JOIN" / "INNER JOIN"
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ask_domain` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`          VARCHAR(100) NOT NULL                COMMENT '数据域名称',
  `description`   VARCHAR(500) NULL                    COMMENT '描述',
  `datasource_id` BIGINT       NULL                    COMMENT '绑定数据源 id，空表示用应用默认库',
  `status`        VARCHAR(20)  NOT NULL DEFAULT 'enabled' COMMENT '状态 enabled/disabled',
  -- MODELED（建模）/ RAW（直连）。直连模式没有行级/列级权限，只做表级授权
  `mode`          VARCHAR(16)  NOT NULL DEFAULT 'MODELED' COMMENT 'MODELED 建模 / RAW 直连',
  `joins_json`    TEXT         NULL                    COMMENT '表关联 JSON [{id,left,right,leftCol,rightCol,type}]，left/right 为注册表 id',
  `perm_json`     TEXT         NULL                    COMMENT '权限配置 JSON，含 v 版本号与 unrestrictedRoles',
  `sort_no`       INT          NOT NULL DEFAULT 0      COMMENT '排序号',
  `created_by`    VARCHAR(64)  NULL                    COMMENT '创建人',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by`    VARCHAR(64)  NULL                    COMMENT '更新人',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_ask_domain_status` (`status`),
  KEY `idx_ask_domain_datasource` (`datasource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问数引擎-语义域';

-- ---------------------------------------------------------------------
-- 域内注册表
--
-- columns_json 结构：[{col,type,agg,alias,enabled}]
--   col     物理列名。**必须与 DatabaseMetaData 报告的原文逐字符一致**——
--           渲染器会原样加引号引用它，差一个字符就查不到列
--   agg     聚合方式，维度列留空。取值见 Aggregation 枚举，
--           未知取值会在解析时抛异常（不回落 NONE，那会把指标当维度渲染）
--   alias   业务名，问数时用户与模型使用的名字。为空则退化为用 col 匹配
--
-- mode=SQL 时 sql_text 必填，渲染时包成子查询；
-- 构造 RegisteredTable 时会校验这一点，不留到渲染阶段才发现。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ask_domain_table` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `domain_id`     BIGINT       NOT NULL                COMMENT '所属数据域 id',
  `name`          VARCHAR(100) NOT NULL                COMMENT '表名（mode=TABLE）或虚拟表标识（mode=SQL）',
  `label`         VARCHAR(100) NULL                    COMMENT '显示名称',
  `mode`          VARCHAR(20)  NOT NULL DEFAULT 'TABLE' COMMENT '类型 TABLE/数据库表，SQL/SQL 虚拟表',
  `sql_text`      TEXT         NULL                    COMMENT 'SQL 虚拟表定义（mode=SQL 时必填）',
  `columns_json`  TEXT         NULL                    COMMENT '列定义 JSON [{col,type,agg,alias,enabled,derived,condition,description,samples}]',
  -- 默认过滤条件（原始 SQL 片段），如 status = '生效'。
  -- 只由管理员在建模时填写，**不经模型产出**，因此没有注入面
  `base_filter`   VARCHAR(512) NULL                    COMMENT '默认过滤条件；空表示不过滤',
  `enabled`       TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否对 LLM 可见：0=否 1=是',
  `sort_no`       INT          NOT NULL DEFAULT 0      COMMENT '排序号',
  `created_by`    VARCHAR(64)  NULL                    COMMENT '创建人',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by`    VARCHAR(64)  NULL                    COMMENT '更新人',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_ask_domain_table_domain` (`domain_id`),
  UNIQUE KEY `uk_ask_domain_table_name` (`domain_id`, `name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问数引擎-域内注册表';

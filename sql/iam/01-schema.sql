-- =====================================================================
-- IAM 库表（chatdata_iam）
--
-- 与 ask 业务库**物理隔离**：IAM 的表不 join 业务表，业务库也不保存用户与角色。
-- 见 docs/adr/0008 与 .scratch/iam-platform/spec.md。
--
-- 执行：
--   mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS chatdata_iam DEFAULT CHARSET utf8mb4"
--   mysql -uroot -p chatdata_iam < sql/iam/01-schema.sql
-- =====================================================================

-- ── 租户 ──
-- 短期只有 t_default 一个；表结构先带租户维度，加第二个租户时不用改表。
CREATE TABLE IF NOT EXISTS `iam_tenant` (
  `id`         VARCHAR(32)  NOT NULL COMMENT '租户 id，如 t_default',
  `name`       VARCHAR(100) NOT NULL COMMENT '租户名',
  `enabled`    TINYINT(1)   NOT NULL DEFAULT 1,
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户';

-- ── 组织树 ──
-- path 是物化路径（/1/12/37/），ORG_AND_CHILDREN 判定用 LIKE 前缀，避免递归查询。
CREATE TABLE IF NOT EXISTS `iam_org` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`         VARCHAR(32)  NOT NULL DEFAULT 't_default',
  `parent_id`         BIGINT       NULL COMMENT '父组织；根为 NULL',
  `code`              VARCHAR(64)  NOT NULL COMMENT '稳定编码，展示与外部映射用；授权内部用 id',
  `name`              VARCHAR(100) NOT NULL,
  `path`              VARCHAR(500) NOT NULL COMMENT '物化路径，如 /1/12/37/',
  `code_path`         VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '编码物化路径，如 /HQ/华东/上海/（给业务库看的，行规则用）',
  `sort_no`           INT          NOT NULL DEFAULT 0,
  `enabled`           TINYINT(1)   NOT NULL DEFAULT 1,
  `external_provider` VARCHAR(32)  NULL COMMENT '外部来源：feishu/ldap/...',
  `external_id`       VARCHAR(128) NULL COMMENT '外部组织 id',
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iam_org_code` (`tenant_id`, `code`),
  KEY `idx_iam_org_parent` (`tenant_id`, `parent_id`),
  KEY `idx_iam_org_path` (`tenant_id`, `path`),
  -- 前缀索引 191 字符：utf8mb4 下整列 1000 字符 = 4000 字节，超过 InnoDB 的 3072 上限。
  -- 191 足以覆盖真实层级深度，且能匹配 `LIKE 'xxx%'` 的前缀扫描
  KEY `idx_iam_org_code_path` (`tenant_id`, `code_path`(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织（树）';

-- ── 用户 ──
CREATE TABLE IF NOT EXISTS `iam_user` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`         VARCHAR(32)  NOT NULL DEFAULT 't_default',
  `username`          VARCHAR(64)  NOT NULL COMMENT '登录名',
  `password_hash`     VARCHAR(100) NULL COMMENT 'BCrypt；SSO 用户可为空',
  `display_name`      VARCHAR(64)  NULL,
  `mobile`            VARCHAR(32)  NULL,
  `email`             VARCHAR(128) NULL,
  `status`            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  `token_version`     INT          NOT NULL DEFAULT 0 COMMENT '改密码/踢下线时 +1，旧令牌批量失效',
  `external_provider` VARCHAR(32)  NULL COMMENT '外部身份来源：feishu/ldap/...',
  `external_id`       VARCHAR(128) NULL COMMENT '外部用户 id（如飞书 open_id）',
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iam_user_username` (`tenant_id`, `username`),
  UNIQUE KEY `uk_iam_user_external` (`tenant_id`, `external_provider`, `external_id`),
  KEY `idx_iam_user_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

-- ── 用户-组织 ──
-- 支持一人多组织；is_primary 决定动态行规则取哪个组织值。
CREATE TABLE IF NOT EXISTS `iam_user_org` (
  `id`         BIGINT   NOT NULL AUTO_INCREMENT,
  `user_id`    BIGINT   NOT NULL,
  `org_id`     BIGINT   NOT NULL,
  `is_primary` TINYINT(1) NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iam_user_org` (`user_id`, `org_id`),
  KEY `idx_iam_user_org_org` (`org_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户所属组织';

-- ── 角色 ──
-- category 沿用 ADR-0004 的三分：ADMIN（管理台）/ DATA（数据权限）/ FUNCTION（功能）。
-- code 一经使用不可重命名：策略与权限配置按 code 引用。
CREATE TABLE IF NOT EXISTS `iam_role` (
  `id`         BIGINT      NOT NULL AUTO_INCREMENT,
  `tenant_id`  VARCHAR(32) NOT NULL DEFAULT 't_default',
  `code`       VARCHAR(64) NOT NULL,
  `name`       VARCHAR(64) NOT NULL,
  `category`   VARCHAR(16) NOT NULL DEFAULT 'DATA' COMMENT 'ADMIN/DATA/FUNCTION',
  `description` VARCHAR(255) NULL,
  `enabled`    TINYINT(1)  NOT NULL DEFAULT 1,
  `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iam_role_code` (`tenant_id`, `code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色';

-- ── 用户-角色 ──
-- org_id 为空 = 租户级授予；非空 = 组织级授予（可沿子树继承）。
CREATE TABLE IF NOT EXISTS `iam_user_role` (
  `id`               BIGINT   NOT NULL AUTO_INCREMENT,
  `user_id`          BIGINT   NOT NULL,
  `role_id`          BIGINT   NOT NULL,
  `org_id`           BIGINT   NULL,
  `include_children` TINYINT(1) NOT NULL DEFAULT 0,
  `created_at`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iam_user_role` (`user_id`, `role_id`, `org_id`),
  KEY `idx_iam_user_role_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色（租户级 / 组织级）';

-- ── 审计 ──
CREATE TABLE IF NOT EXISTS `iam_audit_log` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `tenant_id`   VARCHAR(32)  NOT NULL DEFAULT 't_default',
  `actor`       VARCHAR(64)  NOT NULL,
  `action`      VARCHAR(64)  NOT NULL COMMENT 'LOGIN/LOGOUT/REFRESH/USER_UPDATE/ROLE_GRANT/...',
  `target_type` VARCHAR(32)  NULL,
  `target_id`   VARCHAR(64)  NULL,
  `result`      VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAILURE/DENIED',
  `detail`      VARCHAR(500) NULL,
  `ip`          VARCHAR(64)  NULL,
  `user_agent`  VARCHAR(255) NULL,
  `request_id`  VARCHAR(64)  NULL,
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_iam_audit_actor` (`tenant_id`, `actor`, `created_at`),
  KEY `idx_iam_audit_action` (`tenant_id`, `action`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IAM 审计';

-- =====================================================================
-- Spring Authorization Server 官方表（MySQL 版）
-- 用官方 DDL，不自造：JdbcRegisteredClientRepository /
-- JdbcOAuth2AuthorizationService / JdbcOAuth2AuthorizationConsentService 直接依赖这些列。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `oauth2_registered_client` (
  `id`                            VARCHAR(100)  NOT NULL,
  `client_id`                     VARCHAR(100)  NOT NULL,
  `client_id_issued_at`           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `client_secret`                 VARCHAR(200)  NULL,
  `client_secret_expires_at`      TIMESTAMP     NULL,
  `client_name`                   VARCHAR(200)  NOT NULL,
  `client_authentication_methods` VARCHAR(1000) NOT NULL,
  `authorization_grant_types`     VARCHAR(1000) NOT NULL,
  `redirect_uris`                 VARCHAR(1000) NULL,
  `post_logout_redirect_uris`     VARCHAR(1000) NULL,
  `scopes`                        VARCHAR(1000) NOT NULL,
  `client_settings`               VARCHAR(2000) NOT NULL,
  `token_settings`                VARCHAR(2000) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `oauth2_authorization` (
  `id`                            VARCHAR(100) NOT NULL,
  `registered_client_id`          VARCHAR(100) NOT NULL,
  `principal_name`                VARCHAR(200) NOT NULL,
  `authorization_grant_type`      VARCHAR(100) NOT NULL,
  `authorized_scopes`             VARCHAR(1000) NULL,
  `attributes`                    BLOB          NULL,
  `state`                         VARCHAR(500)  NULL,
  `authorization_code_value`      BLOB          NULL,
  `authorization_code_issued_at`  TIMESTAMP     NULL,
  `authorization_code_expires_at` TIMESTAMP     NULL,
  `authorization_code_metadata`   BLOB          NULL,
  `access_token_value`            BLOB          NULL,
  `access_token_issued_at`        TIMESTAMP     NULL,
  `access_token_expires_at`       TIMESTAMP     NULL,
  `access_token_metadata`         BLOB          NULL,
  `access_token_type`             VARCHAR(100)  NULL,
  `access_token_scopes`           VARCHAR(1000) NULL,
  `oidc_id_token_value`           BLOB          NULL,
  `oidc_id_token_issued_at`       TIMESTAMP     NULL,
  `oidc_id_token_expires_at`      TIMESTAMP     NULL,
  `oidc_id_token_metadata`        BLOB          NULL,
  `refresh_token_value`           BLOB          NULL,
  `refresh_token_issued_at`       TIMESTAMP     NULL,
  `refresh_token_expires_at`      TIMESTAMP     NULL,
  `refresh_token_metadata`        BLOB          NULL,
  `user_code_value`               BLOB          NULL,
  `user_code_issued_at`           TIMESTAMP     NULL,
  `user_code_expires_at`          TIMESTAMP     NULL,
  `user_code_metadata`            BLOB          NULL,
  `device_code_value`             BLOB          NULL,
  `device_code_issued_at`         TIMESTAMP     NULL,
  `device_code_expires_at`        TIMESTAMP     NULL,
  `device_code_metadata`          BLOB          NULL,
  PRIMARY KEY (`id`),
  KEY `idx_oauth2_authorization_principal` (`principal_name`),
  KEY `idx_oauth2_authorization_client` (`registered_client_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `oauth2_authorization_consent` (
  `registered_client_id` VARCHAR(100)  NOT NULL,
  `principal_name`       VARCHAR(200)  NOT NULL,
  `authorities`          VARCHAR(1000) NOT NULL,
  PRIMARY KEY (`registered_client_id`, `principal_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

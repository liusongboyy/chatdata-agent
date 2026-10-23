-- =====================================================================
-- 问数引擎种子数据：一个能对着本地库跑通的完整语义域
--
-- 依据：sql/08-ask-engine.sql
--
-- 用途：让「加载 → 解析 → 渲染 → 执行」这条链路有一个可复现的起点。
--       没有种子数据时，每次验证都要手工构造夹具，而那些夹具与真实配置
--       的结构差异正是最容易出问题的地方。
--
-- 幂等：固定 id + INSERT ... ON DUPLICATE KEY UPDATE，可重复执行。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 数据源：本地 MySQL
--
-- password_enc 这里留空——种子数据不落密码。
-- 需要连库执行时，由管理页写入（会经 PasswordCipher 加密）。
-- 加载与渲染不依赖密码，故不影响验证。
-- ---------------------------------------------------------------------
INSERT INTO `ask_datasource` (`id`, `name`, `db_type`, `driver_class`, `url`, `username`, `password_enc`, `enabled`)
VALUES (1, '本地问数库', 'MYSQL5.7', 'com.mysql.cj.jdbc.Driver',
        'jdbc:mysql://127.0.0.1:3306/jimureport?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai',
        'root', NULL, 1)
ON DUPLICATE KEY UPDATE `db_type` = VALUES(`db_type`), `url` = VALUES(`url`), `enabled` = VALUES(`enabled`);

-- ---------------------------------------------------------------------
-- 语义域：保险分析域
--
-- perm_json 里两个字段是**本项目相对厂商新增的**：
--   v                 —— schema 版本号
--   unrestrictedRoles —— 显式授予全量行可见
--
-- 注意「分析师」进了 unrestrictedRoles 而非靠"没配 rowRules"推导——
-- 后者是厂商的默认放行语义，本项目要求显式声明（见 RowScope 的说明）。
-- ---------------------------------------------------------------------
INSERT INTO `ask_domain` (`id`, `name`, `description`, `datasource_id`, `status`, `joins_json`, `perm_json`, `sort_no`)
VALUES (1, '保险分析域', '保单与客户分析', 1, 'enabled',
        -- joins_json 的 left/right 是**注册表 id**（已实测确认，不是表名）。
        -- 这里用 INNER：保单必有对应客户，且是多对一，不会因 join 产生重复行。
        '[{"id":1,"left":"1","right":"2","leftCol":"cust_id","rightCol":"id","type":"INNER JOIN"}]',
        -- grantUsers 里放了 admin：admin 的角色是 chat2bi（管理台角色），
        -- 不在 grantRoles 里。开发时用它登录能完整走一遍问数，
        -- 而不用去找 zhangsan/lisi 的密码。
        --
        -- 用 grantUsers 而不是把 chat2bi 加进 grantRoles：后者会放宽到
        -- **所有管理台角色的用户**，而这里只需要一个。
        '{"v":1,"isPublic":false,"grantRoles":["区域经理","分析师"],"grantUsers":["admin"],"unrestrictedRoles":["分析师"],"rowRules":[{"id":1,"role":"区域经理","field":"region","op":"=","value":"华东","sysVar":"","dynamic":false}],"colRules":[{"id":1,"role":"分析师","field":"premium","action":"脱敏"}]}',
        0)
ON DUPLICATE KEY UPDATE `perm_json` = VALUES(`perm_json`), `joins_json` = VALUES(`joins_json`), `status` = VALUES(`status`);

-- ---------------------------------------------------------------------
-- 注册表：ins_policy
--
-- 列定义里的 col 必须与库里实际列名逐字符一致——渲染器原样加引号引用它。
-- 「生效日期」的别名用于时间条件定位（渲染器按候选名找时间列）。
-- ---------------------------------------------------------------------
INSERT INTO `ask_domain_table` (`id`, `domain_id`, `name`, `label`, `mode`, `sql_text`, `columns_json`, `enabled`, `sort_no`)
VALUES (1, 1, 'ins_policy', '保单表', 'TABLE', NULL,
        '[{"col":"premium","type":"decimal","agg":"SUM","alias":"保费","enabled":true},
          {"col":"policy_no","type":"varchar","agg":"COUNT","alias":"保单数","enabled":true},
          {"col":"region","type":"varchar","agg":"","alias":"区域","enabled":true},
          {"col":"status","type":"varchar","agg":"","alias":"状态","enabled":true},
          {"col":"effect_date","type":"date","agg":"","alias":"生效日期","enabled":true}]',
        1, 0)
ON DUPLICATE KEY UPDATE `columns_json` = VALUES(`columns_json`), `enabled` = VALUES(`enabled`);

-- ---------------------------------------------------------------------
-- 注册表：ins_customer
--
-- 与 ins_policy 通过 cust_id = id 关联，用于验证多表 join。
--
-- ⚠️ 列名必须与库里实际存在的列**逐字符一致**——渲染器原样加引用它们，
--    差一个字符就是 Unknown column。本文件里的列是照着
--    ins_customer 的真实结构写的（id/cust_no/cust_name/gender/age/
--    province/city/region/occupation/cust_level/reg_date）。
--
-- 注意**没有登记 region**：ins_policy 也有 region 且已登记为「区域」，
-- 两张表都登记同名别名会被字段解析判为歧义而拒绝（这是刻意的，见 SemanticModel）。
-- ---------------------------------------------------------------------
INSERT INTO `ask_domain_table` (`id`, `domain_id`, `name`, `label`, `mode`, `sql_text`, `columns_json`, `enabled`, `sort_no`)
VALUES (2, 1, 'ins_customer', '客户表', 'TABLE', NULL,
        '[{"col":"id","type":"bigint","agg":"","alias":"客户ID","enabled":true},
          {"col":"cust_name","type":"varchar","agg":"","alias":"客户姓名","enabled":true},
          {"col":"cust_level","type":"varchar","agg":"","alias":"客户等级","enabled":true},
          {"col":"city","type":"varchar","agg":"","alias":"城市","enabled":true},
          {"col":"reg_date","type":"date","agg":"","alias":"注册日期","enabled":true}]',
        1, 1)
ON DUPLICATE KEY UPDATE `columns_json` = VALUES(`columns_json`), `enabled` = VALUES(`enabled`);

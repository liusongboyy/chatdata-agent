-- =====================================================================
-- 多表域：5 张真实表，行规则只配一次
--
-- 对照 03（单张虚拟表）：企业级的真实形态是"事实表只有 store_id，
-- 组织层级在维表上"。正确做法是注册全部真实表 + 配好 JOIN，
-- 然后在**维表**上配一条行规则——引擎会把维表自动拉进每条查询的 FROM。
--
-- 关键：v0.9.x 之前不拉，谓词引用 org_path 会报 Unknown column。
-- 见 AskSqlRenderer#permissionTableIds。
--
-- 依赖 01 建好 chatdata_cvs；本脚本自建 cvs_dw（模拟 Doris 分析库）。
-- 密码密文同样需要 04 之后单独加密（数据源 id=130）。
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `cvs_dw` DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
USE `cvs_dw`;

CREATE TABLE IF NOT EXISTS `dim_org` (
  `id` BIGINT PRIMARY KEY,
  -- id 物化路径。与 IAM 的 iam_org.path 同构，行规则 sysVar=orgPath(s) 配它
  `org_path` VARCHAR(200) NOT NULL,
  -- 编码物化路径。与 iam_org.code_path 同构，行规则 sysVar=orgCodePath(s) 配它。
  -- 业务库通常认编码不认 id，所以真实项目里这一列才是常用的那一个
  `org_code_path` VARCHAR(500) NOT NULL DEFAULT '',
  `org_name` VARCHAR(64)
) COMMENT='组织维表（权限挂在这里）';

-- 存量库补列：上面的 CREATE TABLE IF NOT EXISTS 对已存在的表是空操作。
-- MySQL 8.4 没有 ADD COLUMN IF NOT EXISTS，用 information_schema 判。
SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'cvs_dw' AND TABLE_NAME = 'dim_org' AND COLUMN_NAME = 'org_code_path'
);
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `dim_org` ADD COLUMN `org_code_path` VARCHAR(500) NOT NULL DEFAULT '''' AFTER `org_path`',
  'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `f_order` (`id` BIGINT PRIMARY KEY, `store_id` BIGINT, `amount` DECIMAL(12,2)) COMMENT='门店商品订购';
CREATE TABLE IF NOT EXISTS `f_stock` (`id` BIGINT PRIMARY KEY, `store_id` BIGINT, `qty` INT) COMMENT='门店商品库存';
CREATE TABLE IF NOT EXISTS `f_scrap` (`id` BIGINT PRIMARY KEY, `store_id` BIGINT, `amount` DECIMAL(12,2)) COMMENT='门店商品报废';
CREATE TABLE IF NOT EXISTS `f_attendance` (`id` BIGINT PRIMARY KEY, `store_id` BIGINT, `emp_id` BIGINT) COMMENT='门店员工打卡';

-- org_code_path 必须与 chatdata_iam.iam_org 的 code_path 一致；
-- 对不上时行规则会匹配 0 行，而界面上看不出来（见 docs 里"静默失效"的说明）
INSERT INTO `dim_org` VALUES
 (140,'/1/100/110/120/130/140/','/HQ/HQ-CVS/SUB-EAST/DEPT-EAST-A/TEAM-EAST-A1/STORE-SH01/','上海门店1'),
 (141,'/1/100/110/120/130/141/','/HQ/HQ-CVS/SUB-EAST/DEPT-EAST-A/TEAM-EAST-A1/STORE-SH02/','上海门店2'),
 (150,'/1/100/111/121/131/150/','/HQ/HQ-CVS/SUB-NORTH/DEPT-NORTH-A/TEAM-NORTH-A1/STORE-BJ01/','北京门店1'),
 (160,'/1/100/112/122/132/160/','/HQ/HQ-CVS/SUB-SOUTH/DEPT-SOUTH-A/TEAM-SOUTH-A1/STORE-GZ01/','广州门店1')
ON DUPLICATE KEY UPDATE `org_path`=VALUES(`org_path`),
                        `org_code_path`=VALUES(`org_code_path`), `org_name`=VALUES(`org_name`);

INSERT INTO `f_order` VALUES (1,140,1000),(2,141,2600),(3,150,4600),(4,160,8600)
ON DUPLICATE KEY UPDATE `amount`=VALUES(`amount`);
INSERT INTO `f_stock` VALUES (1,140,50),(2,141,60),(3,150,70),(4,160,80)
ON DUPLICATE KEY UPDATE `qty`=VALUES(`qty`);
INSERT INTO `f_scrap` VALUES (1,140,10),(2,141,20),(3,150,30),(4,160,40)
ON DUPLICATE KEY UPDATE `amount`=VALUES(`amount`);
INSERT INTO `f_attendance` VALUES (1,140,1),(2,141,2),(3,150,3),(4,160,4)
ON DUPLICATE KEY UPDATE `emp_id`=VALUES(`emp_id`);

-- ⚠️ 以下 id 必须与脚本里写死的一致；joins_json 的 left/right 是**注册表 id**，不是表名
USE `chatdata_ask`;
DELETE FROM `ask_assistant` WHERE `id` = 2300;
DELETE FROM `ask_domain_table` WHERE `domain_id` = 2300;
DELETE FROM `ask_domain` WHERE `id` = 2300;
DELETE FROM `ask_datasource` WHERE `id` = 130;

INSERT INTO `ask_datasource`
  (`id`,`name`,`db_type`,`driver_class`,`url`,`username`,`password_enc`,`enabled`,`created_by`)
VALUES (130,'门店分析库','MYSQL5.7','com.mysql.cj.jdbc.Driver',
  'jdbc:mysql://127.0.0.1:3306/cvs_dw?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai',
  'root',NULL,1,'demo');

INSERT INTO `ask_domain`
  (`id`,`name`,`description`,`datasource_id`,`status`,`mode`,`joins_json`,`perm_json`,`sort_no`,`created_by`)
VALUES (2300,'门店分析域','5 张真实表，行规则只配在组织维表上一次',130,'enabled','MODELED',
'[{"id":1,"left":"230001","right":"230005","leftCol":"store_id","rightCol":"id","type":"INNER JOIN"},
  {"id":2,"left":"230002","right":"230005","leftCol":"store_id","rightCol":"id","type":"INNER JOIN"},
  {"id":3,"left":"230003","right":"230005","leftCol":"store_id","rightCol":"id","type":"INNER JOIN"},
  {"id":4,"left":"230004","right":"230005","leftCol":"store_id","rightCol":"id","type":"INNER JOIN"}]',
'{"v":1,"isPublic":false,"grantRoles":["区域经理","分析师","super_admin"],"grantUsers":[],"unrestrictedRoles":["super_admin"],"rowRules":[{"id":1,"role":"区域经理","field":"org_path","op":"starts_with","value":null,"sysVar":"orgPaths","dynamic":true}],"colRules":[]}',
0,'demo');

INSERT INTO `ask_domain_table` (`id`,`domain_id`,`name`,`label`,`mode`,`sql_text`,`columns_json`,`enabled`,`sort_no`,`created_by`) VALUES
 (230001,2300,'f_order','门店商品订购','TABLE',NULL,
  '[{"col":"amount","type":"decimal","agg":"SUM","alias":"订单金额","enabled":true},{"col":"id","type":"bigint","agg":"COUNT","alias":"订单数","enabled":true}]',1,0,'demo'),
 (230002,2300,'f_stock','门店商品库存','TABLE',NULL,
  '[{"col":"qty","type":"int","agg":"SUM","alias":"库存数量","enabled":true},{"col":"id","type":"bigint","agg":"COUNT","alias":"高库存门店数","enabled":true,"condition":{"field":"库存数量","op":">","value":"60"}}]',1,1,'demo'),
 (230003,2300,'f_scrap','门店商品报废','TABLE',NULL,
  '[{"col":"amount","type":"decimal","agg":"SUM","alias":"报废金额","enabled":true}]',1,2,'demo'),
 (230004,2300,'f_attendance','门店员工打卡','TABLE',NULL,
  '[{"col":"id","type":"bigint","agg":"COUNT","alias":"打卡次数","enabled":true}]',1,3,'demo'),
 (230005,2300,'dim_org','组织维表','TABLE',NULL,
  '[{"col":"org_path","type":"varchar","agg":"","alias":"组织路径","enabled":true},{"col":"org_code_path","type":"varchar","agg":"","alias":"组织编码路径","enabled":true},{"col":"org_name","type":"varchar","agg":"","alias":"门店名称","enabled":true}]',1,4,'demo');

INSERT INTO `ask_assistant`
  (`id`,`name`,`avatar`,`description`,`welcome`,`domain_id`,`domain_ids`,`show_sql`,`status`,`questions_json`,`perm_json`,`sort_no`,`created_by`)
VALUES (2300,'门店分析助手','📊','5 张表：订购/库存/报废/打卡，按组织自动过滤',
 '试试问「订单金额合计是多少？」或「库存数量合计是多少？」',2300,'["2300"]',1,'online',
 '["订单金额合计是多少？","库存数量合计是多少？","报废金额合计是多少？","打卡次数是多少？"]',
 '{"enabled":true,"grantRoles":["区域经理","分析师","super_admin"],"grantUsers":[]}',0,'demo');

SELECT d.id, d.name, COUNT(t.id) AS tables FROM ask_domain d
JOIN ask_domain_table t ON t.domain_id = d.id WHERE d.id = 2300 GROUP BY d.id, d.name;

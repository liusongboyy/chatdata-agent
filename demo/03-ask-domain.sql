-- =====================================================================
-- 演示用的语义域：便利店订单域（域 2200）
--
-- 关键在于 order_txn_with_org 这个 SQL 虚拟表：它把订单与组织路径 join 在一起，
-- 使行规则能引用 org_path。**组织副本在业务库**，不做跨库 join——
-- 生产里这张表由同步任务维护。
--
-- 行规则两条，演示两种运算符的差异：
--   区域经理 → starts_with（覆盖整棵子树）
--   分析师   → =           （只看自己那一层）
--
-- 密码密文不在本文件里：见 04-encrypt-datasource.sh。
-- =====================================================================

USE `chatdata_ask`;

DELETE FROM `ask_domain_table` WHERE `domain_id` = 2200;
DELETE FROM `ask_domain` WHERE `id` = 2200;
DELETE FROM `ask_datasource` WHERE `id` = 120;

INSERT INTO `ask_datasource`
  (`id`,`name`,`db_type`,`driver_class`,`url`,`username`,`password_enc`,`enabled`,`created_by`)
VALUES (120,'便利店订单库','MYSQL5.7','com.mysql.cj.jdbc.Driver',
  'jdbc:mysql://127.0.0.1:3306/chatdata_cvs?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai',
  'root',NULL,1,'demo');

INSERT INTO `ask_domain`
  (`id`,`name`,`description`,`datasource_id`,`status`,`mode`,`joins_json`,`perm_json`,`sort_no`,`created_by`)
VALUES (2200,'便利店订单域','按组织层级动态过滤订单',120,'enabled','MODELED',NULL,
'{"v":1,"isPublic":false,"grantRoles":["区域经理","分析师","super_admin"],"grantUsers":[],"unrestrictedRoles":["super_admin"],"rowRules":[{"id":1,"role":"区域经理","field":"org_path","op":"starts_with","value":null,"sysVar":"orgPaths","dynamic":true},{"id":2,"role":"分析师","field":"org_path","op":"=","value":null,"sysVar":"orgPaths","dynamic":true}],"colRules":[]}',
0,'demo');

-- ⚠️ org_path 必须登记为列：行规则引用的字段必须是域内已注册且启用的列，
-- 光在虚拟表 SQL 里 SELECT 出来是不够的
INSERT INTO `ask_domain_table`
  (`id`,`domain_id`,`name`,`label`,`mode`,`sql_text`,`columns_json`,`enabled`,`sort_no`,`created_by`)
VALUES (220001,2200,'order_txn_with_org','订单（含组织路径）','SQL',
'SELECT o.id, o.order_no, o.amount, o.biz_date, org.path AS org_path, org.name AS org_name FROM order_txn o JOIN store s ON s.id = o.store_id JOIN org ON org.id = s.org_id',
'[{"col":"amount","type":"decimal","agg":"SUM","alias":"交易金额","enabled":true},{"col":"id","type":"bigint","agg":"COUNT","alias":"订单数","enabled":true},{"col":"org_name","type":"varchar","agg":"","alias":"所属组织","enabled":true},{"col":"order_no","type":"varchar","agg":"","alias":"订单编号","enabled":true},{"col":"biz_date","type":"date","agg":"","alias":"生效日期","enabled":true},{"col":"org_path","type":"varchar","agg":"","alias":"组织路径","enabled":true}]',
1,0,'demo');

SELECT d.id, d.name, d.mode, t.name AS table_name, t.mode AS table_mode
FROM `ask_domain` d JOIN `ask_domain_table` t ON t.domain_id = d.id WHERE d.id = 2200;

-- ── 助理 ────────────────────────────────────────────────────────────
-- 问数页是**从助理进入**的：只建域不建助理，用户根本没有入口。
-- 助理层的判据与域层不同（enabled=true 且主体在名单内），两道闸门分别生效：
-- 助理决定"这个入口可不可见"，域决定"能看到哪些行"。
DELETE FROM `ask_assistant` WHERE `id` = 2200;

INSERT INTO `ask_assistant`
  (`id`,`name`,`avatar`,`description`,`welcome`,`domain_id`,`domain_ids`,
   `show_sql`,`status`,`questions_json`,`perm_json`,`sort_no`,`created_by`)
VALUES (2200,'便利店订单助手','🏪','按组织层级查看便利店订单与交易额',
 '你好，我可以按你所在的组织范围查询便利店订单。试试问「交易金额合计是多少？」',
 2200,'["2200"]',1,'online',
 '["交易金额合计是多少？","订单数是多少？","按所属组织统计交易金额","按门店统计订单数"]',
 '{"enabled":true,"grantRoles":["区域经理","分析师","super_admin"],"grantUsers":[]}',
 0,'demo');

SELECT a.id, a.name, a.status, a.domain_id FROM `ask_assistant` a WHERE a.id = 2200;

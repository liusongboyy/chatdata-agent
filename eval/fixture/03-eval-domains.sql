-- =====================================================================
-- 评测夹具的语义域配置（写在业务库 chatdata_ask 的 ask_* 表里）
--
-- 三个域共用数据源 100（chatdata_eval）：
--   2001 保险分析域（建模，3 表）
--   2002 工单分析域（建模，4 表）
--   2003 工单直连域（直连，12 表）
--
-- 只给 super_admin 授权：评测身份用它，且不依赖任何业务角色。
-- 可重复执行：先删后插，id 固定。
-- =====================================================================

USE `chatdata_ask`;

DELETE FROM `ask_domain_table` WHERE `domain_id` IN (2001, 2002, 2003);
DELETE FROM `ask_domain` WHERE `id` IN (2001, 2002, 2003);
DELETE FROM `ask_datasource` WHERE `id` = 100;

INSERT INTO `ask_datasource`
  (`id`, `name`, `db_type`, `driver_class`, `url`, `username`, `password_enc`, `enabled`, `created_by`)
VALUES
  (100, '评测夹具库', 'MYSQL5.7', 'com.mysql.cj.jdbc.Driver',
   'jdbc:mysql://127.0.0.1:3306/chatdata_eval?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai',
   'root', NULL, 1, 'eval-fixture');

-- ── 2001 保险分析域（建模） ─────────────────────────────────────────
INSERT INTO `ask_domain`
  (`id`, `name`, `description`, `datasource_id`, `status`, `mode`, `joins_json`, `perm_json`, `sort_no`, `created_by`)
VALUES
  (2001, '保险分析域（评测夹具）', '保单、客户与理赔，用于建模模式基线', 100, 'enabled', 'MODELED',
   '[{"id":1,"left":"200101","right":"200102","leftCol":"cust_id","rightCol":"id","type":"INNER JOIN"},{"id":2,"left":"200103","right":"200101","leftCol":"policy_id","rightCol":"id","type":"INNER JOIN"}]',
   '{"v":1,"isPublic":false,"grantRoles":["super_admin"],"grantUsers":[],"unrestrictedRoles":["super_admin"],"rowRules":[],"colRules":[]}',
   0, 'eval-fixture');

INSERT INTO `ask_domain_table`
  (`id`, `domain_id`, `name`, `label`, `mode`, `sql_text`, `columns_json`, `enabled`, `sort_no`, `created_by`)
VALUES
  (200101, 2001, 'ins_policy', '保单表', 'TABLE', NULL,
   '[{"col":"premium","type":"decimal","agg":"SUM","alias":"保费","enabled":true},{"col":"policy_no","type":"varchar","agg":"COUNT","alias":"保单数","enabled":true},{"col":"coverage_amount","type":"decimal","agg":"SUM","alias":"保额","enabled":true},{"col":"region","type":"varchar","agg":"","alias":"区域","enabled":true},{"col":"status","type":"varchar","agg":"","alias":"保单状态","enabled":true},{"col":"product_type","type":"varchar","agg":"","alias":"险种类型","enabled":true},{"col":"channel","type":"varchar","agg":"","alias":"销售渠道","enabled":true},{"col":"province","type":"varchar","agg":"","alias":"出单省份","enabled":true},{"col":"effect_date","type":"date","agg":"","alias":"生效日期","enabled":true},{"col":"sign_date","type":"date","agg":"","alias":"投保年份","enabled":true},{"col":"term_years","type":"int","agg":"AVG","alias":"平均保障年限","enabled":true}]',
   1, 0, 'eval-fixture'),
  (200102, 2001, 'ins_customer', '客户表', 'TABLE', NULL,
   '[{"col":"cust_name","type":"varchar","agg":"","alias":"客户姓名","enabled":true},{"col":"gender","type":"varchar","agg":"","alias":"性别","enabled":true},{"col":"age","type":"int","agg":"AVG","alias":"平均年龄","enabled":true},{"col":"cust_level","type":"varchar","agg":"","alias":"客户等级","enabled":true},{"col":"occupation","type":"varchar","agg":"","alias":"职业","enabled":true},{"col":"city","type":"varchar","agg":"","alias":"城市","enabled":true}]',
   1, 1, 'eval-fixture'),
  (200103, 2001, 'ins_claim', '理赔表', 'TABLE', NULL,
   '[{"col":"claim_no","type":"varchar","agg":"COUNT","alias":"理赔件数","enabled":true},{"col":"claim_amount","type":"decimal","agg":"SUM","alias":"申请理赔金额","enabled":true},{"col":"settle_amount","type":"decimal","agg":"SUM","alias":"赔付金额","enabled":true},{"col":"status","type":"varchar","agg":"","alias":"理赔状态","enabled":true},{"col":"claim_type","type":"varchar","agg":"","alias":"理赔类型","enabled":true},{"col":"report_date","type":"date","agg":"","alias":"统计日期","enabled":true},{"col":"province","type":"varchar","agg":"","alias":"出险省份","enabled":true}]',
   1, 2, 'eval-fixture');

-- ── 2002 工单分析域（建模） ─────────────────────────────────────────
INSERT INTO `ask_domain`
  (`id`, `name`, `description`, `datasource_id`, `status`, `mode`, `joins_json`, `perm_json`, `sort_no`, `created_by`)
VALUES
  (2002, '工单分析域（评测夹具）', '工单、分类、评论与流转，用于建模模式基线', 100, 'enabled', 'MODELED',
   '[{"id":1,"left":"200201","right":"200202","leftCol":"category_id","rightCol":"id","type":"INNER JOIN"},{"id":2,"left":"200203","right":"200201","leftCol":"ticket_id","rightCol":"id","type":"INNER JOIN"},{"id":3,"left":"200204","right":"200201","leftCol":"ticket_id","rightCol":"id","type":"INNER JOIN"}]',
   '{"v":1,"isPublic":false,"grantRoles":["super_admin"],"grantUsers":[],"unrestrictedRoles":["super_admin"],"rowRules":[],"colRules":[]}',
   1, 'eval-fixture');

INSERT INTO `ask_domain_table`
  (`id`, `domain_id`, `name`, `label`, `mode`, `sql_text`, `columns_json`, `enabled`, `sort_no`, `created_by`)
VALUES
  -- 软删过滤放在虚拟表里：语义层的列定义不该暴露 deleted 这种实现细节，
  -- 而"必须过滤软删"是这张表的业务口径，属于建模的一部分。
  (200201, 2002, 'ticket', '工单表', 'SQL',
   'SELECT * FROM ticket WHERE deleted = 0',
   '[{"col":"ticket_no","type":"varchar","agg":"COUNT","alias":"工单数","enabled":true},{"col":"priority","type":"int","agg":"","alias":"优先级","enabled":true},{"col":"status","type":"varchar","agg":"","alias":"状态","enabled":true},{"col":"source","type":"varchar","agg":"","alias":"来源","enabled":true},{"col":"title","type":"varchar","agg":"","alias":"标题","enabled":true},{"col":"created_at","type":"datetime","agg":"","alias":"生效日期","enabled":true},{"col":"resolved_at","type":"datetime","agg":"","alias":"解决时间","enabled":true},{"col":"closed_at","type":"datetime","agg":"","alias":"关闭时间","enabled":true},{"col":"sla_deadline","type":"datetime","agg":"","alias":"SLA截止时间","enabled":true},{"col":"ai_confidence","type":"double","agg":"AVG","alias":"AI置信度","enabled":true},{"col":"category_id","type":"bigint","agg":"","alias":"分类ID","enabled":true},{"col":"assignee_id","type":"bigint","agg":"","alias":"处理人ID","enabled":true},{"col":"team_id","type":"bigint","agg":"","alias":"处理团队ID","enabled":true}]',
   1, 0, 'eval-fixture'),
  (200202, 2002, 'ticket_category', '工单分类表', 'SQL',
   'SELECT * FROM ticket_category WHERE deleted = 0',
   '[{"col":"name","type":"varchar","agg":"","alias":"分类名称","enabled":true},{"col":"enabled","type":"int","agg":"","alias":"分类是否启用","enabled":true}]',
   1, 1, 'eval-fixture'),
  (200203, 2002, 'ticket_comment', '工单评论', 'SQL',
   'SELECT * FROM ticket_comment WHERE deleted = 0',
   '[{"col":"id","type":"bigint","agg":"COUNT","alias":"评论数","enabled":true},{"col":"is_internal","type":"int","agg":"","alias":"内部备注标记","enabled":true}]',
   1, 2, 'eval-fixture'),
  (200204, 2002, 'ticket_flow', '工单流转记录', 'SQL',
   'SELECT * FROM ticket_flow WHERE deleted = 0',
   '[{"col":"id","type":"bigint","agg":"COUNT","alias":"流转次数","enabled":true},{"col":"from_status","type":"varchar","agg":"","alias":"原状态","enabled":true},{"col":"to_status","type":"varchar","agg":"","alias":"新状态","enabled":true}]',
   1, 3, 'eval-fixture');

-- ── 2003 工单直连域（直连） ─────────────────────────────────────────
INSERT INTO `ask_domain`
  (`id`, `name`, `description`, `datasource_id`, `status`, `mode`, `joins_json`, `perm_json`, `sort_no`, `created_by`)
VALUES
  (2003, '工单直连域（评测夹具）', '12 张表全开，模型自己写 SQL；只做表级授权', 100, 'enabled', 'RAW',
   NULL,
   '{"v":1,"isPublic":false,"grantRoles":["super_admin"],"grantUsers":[],"unrestrictedRoles":["super_admin"],"rowRules":[],"colRules":[]}',
   2, 'eval-fixture');

INSERT INTO `ask_domain_table`
  (`id`, `domain_id`, `name`, `label`, `mode`, `sql_text`, `columns_json`, `enabled`, `sort_no`, `created_by`)
VALUES
  (200301, 2003, 'ticket', '工单表', 'TABLE', NULL, '[]', 1, 0, 'eval-fixture'),
  (200302, 2003, 'ticket_category', '工单分类表', 'TABLE', NULL, '[]', 1, 1, 'eval-fixture'),
  (200303, 2003, 'ticket_comment', '工单评论', 'TABLE', NULL, '[]', 1, 2, 'eval-fixture'),
  (200304, 2003, 'ticket_flow', '工单流转记录', 'TABLE', NULL, '[]', 1, 3, 'eval-fixture'),
  (200305, 2003, 'knowledge_article', '知识条目', 'TABLE', NULL, '[]', 1, 4, 'eval-fixture'),
  (200306, 2003, 'knowledge_attachment', '知识附件', 'TABLE', NULL, '[]', 1, 5, 'eval-fixture'),
  (200307, 2003, 'knowledge_category', '知识分类', 'TABLE', NULL, '[]', 1, 6, 'eval-fixture'),
  (200308, 2003, 'notification', '通知', 'TABLE', NULL, '[]', 1, 7, 'eval-fixture'),
  (200309, 2003, 'sla_policy', 'SLA策略', 'TABLE', NULL, '[]', 1, 8, 'eval-fixture'),
  (200310, 2003, 'sla_timer', 'SLA计时', 'TABLE', NULL, '[]', 1, 9, 'eval-fixture'),
  (200311, 2003, 'sla_violation', 'SLA违规记录', 'TABLE', NULL, '[]', 1, 10, 'eval-fixture'),
  (200312, 2003, 'dispatch_rule', '分派规则', 'TABLE', NULL, '[]', 1, 11, 'eval-fixture');

-- ── 2002/2003 的枚举术语 ─────────────────────────────────────────────
-- 中文问法到库内枚举值的映射。**这是术语的本职**：模型看到「状态」的取值域
-- 只有 CLOSED/OPEN 这些英文码，而用户说的是「已关闭」。不显式给映射，
-- 它会猜——猜错的表现是查回 0 行，而 0 行不像报错，更像"确实没有数据"。
DELETE FROM `ask_term` WHERE `domain_id` IN (2001, 2002, 2003);

INSERT INTO `ask_term` (`domain_id`, `name`, `synonyms_json`, `description`, `apply_kind`, `sort_no`, `created_by`)
VALUES
  (2001, '保单状态', '["状态"]', '取值：生效、已过期、退保（中文，与库内一致）', 'none', 0, 'eval-fixture'),
  (2001, '理赔状态', '["理赔进度"]', '取值：待审核、已赔付、已拒赔', 'none', 1, 'eval-fixture'),
  (2001, '险种类型', '["险种"]', '取值：寿险、健康险、车险、意外险、财产险', 'none', 2, 'eval-fixture'),
  (2001, '大区', '["区域"]', '取值：华东、华南、华北、华中、西南、西北', 'none', 3, 'eval-fixture'),
  (2002, '状态', '["工单状态"]', '取值是英文码：OPEN=待处理/新建，IN_PROGRESS=处理中，RESOLVED=已解决，CLOSED=已关闭。用户说中文时按此映射', 'none', 0, 'eval-fixture'),
  (2002, '来源', '["工单来源","渠道"]', '取值是英文码：WEB=网页，APP=手机应用，EMAIL=邮件，API=接口', 'none', 1, 'eval-fixture'),
  (2002, '内部备注标记', '["内部备注","内部评论"]', '取值：1 表示是内部备注，0 表示是普通评论', 'none', 2, 'eval-fixture'),
  (2002, '分类名称', '["工单分类","分类"]', '取值：账号与登录、订单与支付、产品使用、发票与合同、数据与报表', 'none', 3, 'eval-fixture'),
  (2003, '状态', '["工单状态"]', 'ticket.status 取值是英文码：OPEN=待处理，IN_PROGRESS=处理中，RESOLVED=已解决，CLOSED=已关闭', 'none', 0, 'eval-fixture'),
  (2003, '来源', '["工单来源","渠道"]', 'ticket.source 取值：WEB、APP、EMAIL、API', 'none', 1, 'eval-fixture'),
  (2003, '知识文章状态', '["文章状态"]', 'knowledge_article.status 取值：1=已发布，0=草稿', 'none', 2, 'eval-fixture'),
  (2003, '通知读取状态', '["是否已读"]', 'notification.read_status 取值：0=未读，1=已读', 'none', 3, 'eval-fixture'),
  (2003, 'SLA违规类型', '["违规类型"]', 'sla_violation.violation_type 取值：first_response=首次响应违规，resolve=解决违规', 'none', 4, 'eval-fixture'),
  (2003, '内部备注标记', '["内部备注"]', 'ticket_comment.is_internal 取值：1=内部备注，0=普通评论', 'none', 5, 'eval-fixture');

-- 复核
SELECT 'datasource' AS `kind`, COUNT(*) AS `rows` FROM `ask_datasource` WHERE `id` = 100
UNION ALL SELECT 'domains', COUNT(*) FROM `ask_domain` WHERE `id` IN (2001, 2002, 2003)
UNION ALL SELECT 'tables', COUNT(*) FROM `ask_domain_table` WHERE `domain_id` IN (2001, 2002, 2003);

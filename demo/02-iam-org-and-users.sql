-- =====================================================================
-- 演示环境的 IAM 数据：便利店组织树 + 用户绑定 + 角色
--
-- 与 01-cvs-database.sql 里的 org 副本必须保持一致（路径逐字符相同）。
-- 用户分布在不同层级，用来演示"同一条前缀规则在不同层级自动生效"。
-- =====================================================================

USE `chatdata_iam`;

-- ── 组织树（挂在总部 /1/ 之下）──────────────────────────────────────
INSERT INTO `iam_org` (`id`,`tenant_id`,`parent_id`,`code`,`name`,`path`,`sort_no`,`enabled`) VALUES
 (100,'t_default',1,'HQ-CVS','便利群','/1/100/',10,1),
 (110,'t_default',100,'SUB-EAST','华东子公司','/1/100/110/',0,1),
 (120,'t_default',110,'DEPT-EAST-A','华东使用部门A','/1/100/110/120/',0,1),
 (130,'t_default',120,'TEAM-EAST-A1','华东A组','/1/100/110/120/130/',0,1),
 (140,'t_default',130,'STORE-SH01','上海门店1','/1/100/110/120/130/140/',0,1),
 (141,'t_default',130,'STORE-SH02','上海门店2','/1/100/110/120/130/141/',0,1),
 (111,'t_default',100,'SUB-NORTH','华北子公司','/1/100/111/',1,1),
 (121,'t_default',111,'DEPT-NORTH-A','华北使用部门A','/1/100/111/121/',0,1),
 (131,'t_default',121,'TEAM-NORTH-A1','华北A组','/1/100/111/121/131/',0,1),
 (150,'t_default',131,'STORE-BJ01','北京门店1','/1/100/111/121/131/150/',0,1),
 (112,'t_default',100,'SUB-SOUTH','华南子公司','/1/100/112/',2,1),
 (122,'t_default',112,'DEPT-SOUTH-A','华南使用部门A','/1/100/112/122/',0,1),
 (132,'t_default',122,'TEAM-SOUTH-A1','华南A组','/1/100/112/122/132/',0,1),
 (160,'t_default',132,'STORE-GZ01','广州门店1','/1/100/112/122/132/160/',0,1)
ON DUPLICATE KEY UPDATE `name`=VALUES(`name`), `path`=VALUES(`path`), `parent_id`=VALUES(`parent_id`);

-- ── 用户绑定到不同层级（按 username 定位，避免依赖自增 id）──────────
-- admin2   便利群总部        → 前缀匹配看到全部
-- zhangsan 华东子公司        → 只看华东
-- auditor  华北A组           → 只看北京店
-- reviewer 上海门店1         → 精确匹配只看本店
DELETE uo FROM `iam_user_org` uo
JOIN `iam_user` u ON u.id = uo.user_id
WHERE u.username IN ('admin2','zhangsan','auditor','reviewer');

INSERT INTO `iam_user_org` (`user_id`,`org_id`,`is_primary`)
SELECT u.id, x.org_id, 1
FROM `iam_user` u
JOIN (
  SELECT 'admin2' AS username, 100 AS org_id
  UNION ALL SELECT 'zhangsan', 110
  UNION ALL SELECT 'auditor', 131
  UNION ALL SELECT 'reviewer', 140
) x ON x.username = u.username;

-- ── 角色：区域经理（前缀匹配）、分析师（精确匹配）──────────────────
INSERT IGNORE INTO `iam_user_role` (`user_id`,`role_id`)
SELECT u.id, r.id FROM `iam_user` u JOIN `iam_role` r
WHERE (u.username IN ('admin2','zhangsan','auditor') AND r.code = '区域经理')
   OR (u.username = 'reviewer' AND r.code = '分析师');

-- ── 复核 ────────────────────────────────────────────────────────────
SELECT u.username, o.name AS org_name, o.path, GROUP_CONCAT(DISTINCT r.code) AS roles
FROM `iam_user` u
LEFT JOIN `iam_user_org` uo ON uo.user_id = u.id
LEFT JOIN `iam_org` o ON o.id = uo.org_id
LEFT JOIN `iam_user_role` ur ON ur.user_id = u.id
LEFT JOIN `iam_role` r ON r.id = ur.role_id
WHERE u.username IN ('admin','admin2','zhangsan','auditor','reviewer')
GROUP BY u.username, o.name, o.path
ORDER BY o.path;

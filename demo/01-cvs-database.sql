-- =====================================================================
-- 便利店演示库：组织树副本 + 门店 + 订单
--
-- 用途：演示"按组织层级动态过滤数据"。与 IAM 的组织树是**两份数据**——
-- 业务库不跨库 join，靠这里的 org 副本。生产中这张表由同步任务维护，
-- 演示环境手动保持一致即可。
--
-- 组织路径必须与 chatdata_iam.iam_org 完全一致，否则权限前缀匹配不到。
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `chatdata_cvs` DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
USE `chatdata_cvs`;

CREATE TABLE IF NOT EXISTS `org` (
  `id` BIGINT PRIMARY KEY,
  `code` VARCHAR(32),
  `name` VARCHAR(64),
  `path` VARCHAR(200) NOT NULL COMMENT '物化路径，与 iam_org.path 一致'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织树副本（来源 iam_org）';

CREATE TABLE IF NOT EXISTS `store` (
  `id` BIGINT PRIMARY KEY,
  `store_code` VARCHAR(32) NOT NULL,
  `store_name` VARCHAR(64) NOT NULL,
  `org_id` BIGINT NOT NULL COMMENT '对应 iam_org.id'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='便利店门店';

CREATE TABLE IF NOT EXISTS `order_txn` (
  `id` BIGINT PRIMARY KEY,
  `order_no` VARCHAR(32) NOT NULL,
  `store_id` BIGINT NOT NULL,
  `amount` DECIMAL(12,2) NOT NULL,
  `biz_date` DATE NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='便利店订单交易';

-- ── 组织树（与 iam_org 中 /1/100% 一致）──────────────────────────────
-- 便利群 /1/100/
--   ├─ 华东子公司 /1/100/110/  └ 使用部门A /1/100/110/120/ └ A组 .../130/
--   │      └ 上海门店1 .../140/     └ 上海门店2 .../141/
--   ├─ 华北子公司 /1/100/111/  └ 使用部门A .../121/ └ A组 .../131/ └ 北京门店1 .../150/
--   └─ 华南子公司 /1/100/112/  └ 使用部门A .../122/ └ A组 .../132/ └ 广州门店1 .../160/
INSERT INTO `org` (`id`,`code`,`name`,`path`) VALUES
 (100,'HQ-CVS','便利群','/1/100/'),
 (110,'SUB-EAST','华东子公司','/1/100/110/'),
 (120,'DEPT-EAST-A','华东使用部门A','/1/100/110/120/'),
 (130,'TEAM-EAST-A1','华东A组','/1/100/110/120/130/'),
 (140,'STORE-SH01','上海门店1','/1/100/110/120/130/140/'),
 (141,'STORE-SH02','上海门店2','/1/100/110/120/130/141/'),
 (111,'SUB-NORTH','华北子公司','/1/100/111/'),
 (121,'DEPT-NORTH-A','华北使用部门A','/1/100/111/121/'),
 (131,'TEAM-NORTH-A1','华北A组','/1/100/111/121/131/'),
 (150,'STORE-BJ01','北京门店1','/1/100/111/121/131/150/'),
 (112,'SUB-SOUTH','华南子公司','/1/100/112/'),
 (122,'DEPT-SOUTH-A','华南使用部门A','/1/100/112/122/'),
 (132,'TEAM-SOUTH-A1','华南A组','/1/100/112/122/132/'),
 (160,'STORE-GZ01','广州门店1','/1/100/112/122/132/160/')
ON DUPLICATE KEY UPDATE `name`=VALUES(`name`), `path`=VALUES(`path`);

-- ── 门店 ────────────────────────────────────────────────────────────
INSERT INTO `store` (`id`,`store_code`,`store_name`,`org_id`) VALUES
 (140,'SH01','上海门店1',140),
 (141,'SH02','上海门店2',141),
 (150,'BJ01','北京门店1',150),
 (160,'GZ01','广州门店1',160)
ON DUPLICATE KEY UPDATE `store_name`=VALUES(`store_name`), `org_id`=VALUES(`org_id`);

-- ── 订单：每店 4 单，金额刻意不同，便于一眼看出范围差异 ──────────────
-- 上海门店1 = 1000 / 上海门店2 = 2600 / 北京门店1 = 4600 / 广州门店1 = 8600
-- 全库合计 16800；华东小计 3600；华北小计 4600
INSERT INTO `order_txn` (`id`,`order_no`,`store_id`,`amount`,`biz_date`) VALUES
 (1,'O-SH01-1',140,100.00,'2026-10-01'),
 (2,'O-SH01-2',140,200.00,'2026-10-02'),
 (3,'O-SH01-3',140,300.00,'2026-10-03'),
 (4,'O-SH01-4',140,400.00,'2026-10-04'),
 (5,'O-SH02-1',141,500.00,'2026-10-01'),
 (6,'O-SH02-2',141,600.00,'2026-10-02'),
 (7,'O-SH02-3',141,700.00,'2026-10-03'),
 (8,'O-SH02-4',141,800.00,'2026-10-04'),
 (9,'O-BJ01-1',150,1000.00,'2026-10-01'),
 (10,'O-BJ01-2',150,1100.00,'2026-10-02'),
 (11,'O-BJ01-3',150,1200.00,'2026-10-03'),
 (12,'O-BJ01-4',150,1300.00,'2026-10-04'),
 (13,'O-GZ01-1',160,2000.00,'2026-10-01'),
 (14,'O-GZ01-2',160,2100.00,'2026-10-02'),
 (15,'O-GZ01-3',160,2200.00,'2026-10-03'),
 (16,'O-GZ01-4',160,2300.00,'2026-10-04')
ON DUPLICATE KEY UPDATE `amount`=VALUES(`amount`), `biz_date`=VALUES(`biz_date`);

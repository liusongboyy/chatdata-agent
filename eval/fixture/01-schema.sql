-- 评测夹具库：与业务库完全隔离，可反复重建
-- DDL 与数据快照来自 aiticket / jimureport，生成脚本见 eval/fixture/prepare.sh

CREATE DATABASE IF NOT EXISTS `chatdata_eval` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `chatdata_eval`;

CREATE TABLE `ticket` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `ticket_no` varchar(32) NOT NULL COMMENT '工单编号',
  `title` varchar(200) NOT NULL COMMENT '标题',
  `description` text COMMENT '描述',
  `category_id` bigint DEFAULT NULL COMMENT '分类ID',
  `priority` int NOT NULL DEFAULT '2' COMMENT '优先级：1-5',
  `status` varchar(20) NOT NULL DEFAULT 'OPEN' COMMENT '状态：OPEN/IN_PROGRESS/RESOLVED/CLOSED',
  `source` varchar(20) NOT NULL DEFAULT 'WEB' COMMENT '来源：WEB/APP/EMAIL/API',
  `creator_id` bigint NOT NULL COMMENT '创建人ID',
  `assignee_id` bigint DEFAULT NULL COMMENT '处理人ID',
  `team_id` bigint DEFAULT NULL COMMENT '处理团队ID',
  `sla_policy_id` bigint DEFAULT NULL COMMENT 'SLA策略ID',
  `sla_deadline` datetime DEFAULT NULL COMMENT 'SLA截止时间',
  `resolved_at` datetime DEFAULT NULL,
  `closed_at` datetime DEFAULT NULL,
  `ai_category_id` bigint DEFAULT NULL COMMENT 'AI推荐分类',
  `ai_assignee_id` bigint DEFAULT NULL COMMENT 'AI推荐处理人',
  `ai_confidence` double DEFAULT NULL COMMENT 'AI置信度',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ticket_no` (`ticket_no`),
  KEY `idx_ticket_tenant_status` (`tenant_id`,`status`),
  KEY `idx_ticket_assignee` (`assignee_id`),
  KEY `idx_ticket_creator` (`creator_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工单表';
CREATE TABLE `ticket_category` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `parent_id` bigint NOT NULL DEFAULT '0' COMMENT '父分类ID，0为根',
  `name` varchar(100) NOT NULL COMMENT '分类名称',
  `sort_order` int NOT NULL DEFAULT '0',
  `enabled` int NOT NULL DEFAULT '1',
  `default_team_id` bigint DEFAULT NULL COMMENT '默认处理团队',
  `default_sla_id` bigint DEFAULT NULL COMMENT '默认SLA策略',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tc_tenant_parent` (`tenant_id`,`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工单分类表';
CREATE TABLE `ticket_comment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `ticket_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `content` text NOT NULL,
  `is_internal` int NOT NULL DEFAULT '0' COMMENT '是否内部备注：0-否 1-是',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tcm_ticket` (`ticket_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工单评论';
CREATE TABLE `ticket_flow` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `ticket_id` bigint NOT NULL,
  `from_status` varchar(20) DEFAULT NULL,
  `to_status` varchar(20) NOT NULL,
  `operator_id` bigint NOT NULL COMMENT '操作人ID',
  `comment` varchar(500) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tf_ticket` (`ticket_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工单流转记录';
CREATE TABLE `knowledge_article` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(200) NOT NULL,
  `content` mediumtext COMMENT '正文（Markdown）',
  `summary` varchar(500) DEFAULT NULL COMMENT '摘要',
  `embedding` mediumtext COMMENT 'RAG向量(本地确定性hash,JSON float数组)',
  `category_id` bigint DEFAULT NULL,
  `tags` varchar(255) DEFAULT NULL COMMENT '标签（逗号分隔）',
  `status` int NOT NULL DEFAULT '0' COMMENT '0-草稿 1-已发布',
  `published_at` datetime DEFAULT NULL COMMENT '发布时间（发布时写入，下架保留）',
  `view_count` int NOT NULL DEFAULT '0',
  `helpful_count` int NOT NULL DEFAULT '0',
  `created_by` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` int NOT NULL DEFAULT '0',
  `tenant_id` bigint NOT NULL DEFAULT '1' COMMENT '租户ID',
  PRIMARY KEY (`id`),
  KEY `idx_ka_category` (`category_id`),
  FULLTEXT KEY `ft_ka_title_content` (`title`,`content`,`tags`) /*!50100 WITH PARSER `ngram` */ 
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识条目';
CREATE TABLE `knowledge_attachment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `article_id` bigint NOT NULL,
  `file_name` varchar(255) NOT NULL,
  `file_size` bigint NOT NULL DEFAULT '0',
  `file_path` varchar(500) NOT NULL,
  `file_type` varchar(50) DEFAULT NULL,
  `tenant_id` bigint NOT NULL DEFAULT '1' COMMENT '租户ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_kat_article` (`article_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识附件';
CREATE TABLE `knowledge_category` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL,
  `parent_id` bigint NOT NULL DEFAULT '0',
  `sort_order` int NOT NULL DEFAULT '0',
  `tenant_id` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_kc_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识分类';
CREATE TABLE `notification` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '接收人ID',
  `title` varchar(200) NOT NULL,
  `content` text,
  `type` varchar(20) NOT NULL COMMENT 'system/ticket/sla',
  `biz_id` bigint DEFAULT NULL COMMENT '关联业务ID',
  `read_status` int NOT NULL DEFAULT '0' COMMENT '0-未读 1-已读',
  `read_at` datetime DEFAULT NULL,
  `tenant_id` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_nt_user_read` (`user_id`,`read_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知';
CREATE TABLE `sla_policy` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL,
  `priority` int DEFAULT NULL COMMENT '匹配优先级',
  `category_id` bigint DEFAULT NULL COMMENT '匹配分类',
  `first_response_time` int NOT NULL COMMENT '首次响应时限（分钟）',
  `resolve_time` int NOT NULL COMMENT '解决时限（分钟）',
  `warning_time` int DEFAULT NULL COMMENT '预警时间（分钟）',
  `enabled` int NOT NULL DEFAULT '1',
  `tenant_id` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_sp_tenant_priority` (`tenant_id`,`priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SLA策略';
CREATE TABLE `sla_timer` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `ticket_id` bigint NOT NULL,
  `policy_id` bigint DEFAULT NULL,
  `ticket_created_at` datetime NOT NULL,
  `first_response_deadline` datetime NOT NULL,
  `resolve_deadline` datetime NOT NULL,
  `first_responded_at` datetime DEFAULT NULL,
  `resolved_at` datetime DEFAULT NULL,
  `violation_status` int NOT NULL DEFAULT '0' COMMENT '0-正常 1-响应违规 2-解决违规',
  `warning_sent` int NOT NULL DEFAULT '0' COMMENT '0-未发预警 1-已发预警',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_st_ticket` (`ticket_id`),
  KEY `idx_st_violation` (`violation_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SLA计时';
CREATE TABLE `sla_violation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `ticket_id` bigint NOT NULL,
  `timer_id` bigint DEFAULT NULL,
  `violation_type` varchar(20) NOT NULL COMMENT 'first_response/resolve',
  `description` varchar(500) DEFAULT NULL,
  `overdue_minutes` int DEFAULT NULL,
  `status` int NOT NULL DEFAULT '0' COMMENT '0-未处理 1-已升级 2-已关闭',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_sv_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SLA违规记录';
CREATE TABLE `dispatch_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `name` varchar(100) NOT NULL,
  `priority` int NOT NULL DEFAULT '0' COMMENT '规则优先级',
  `conditions` json DEFAULT NULL COMMENT '匹配条件',
  `action_type` varchar(50) DEFAULT NULL COMMENT '动作类型：assign/notify',
  `action_config` json DEFAULT NULL COMMENT '动作配置',
  `enabled` int NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分派规则';
CREATE TABLE `ins_policy` (
  `id` bigint NOT NULL COMMENT '保单ID',
  `policy_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '保单号',
  `cust_id` bigint DEFAULT NULL COMMENT '客户ID',
  `cust_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '客户姓名',
  `product_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '险种名称',
  `product_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '险种类型（寿险/健康险/车险/意外险/财产险）',
  `channel` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '销售渠道（线上直销/代理人/银保/电销/经纪）',
  `premium` decimal(12,2) DEFAULT NULL COMMENT '年保费（元）',
  `coverage_amount` decimal(14,2) DEFAULT NULL COMMENT '保额（元）',
  `term_years` int DEFAULT NULL COMMENT '保障年限',
  `sign_date` date DEFAULT NULL COMMENT '投保日期',
  `effect_date` date DEFAULT NULL COMMENT '生效日期',
  `expire_date` date DEFAULT NULL COMMENT '到期日期',
  `status` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '保单状态（生效/已过期/退保）',
  `province` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '出单省份',
  `region` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '大区',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='保险保单表';
CREATE TABLE `ins_customer` (
  `id` bigint NOT NULL COMMENT '客户ID',
  `cust_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '客户编号',
  `cust_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '客户姓名',
  `gender` varchar(4) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '性别（男/女）',
  `age` int DEFAULT NULL COMMENT '年龄',
  `province` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '省份',
  `city` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '城市',
  `region` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '大区（华东/华南/华北/华中/西南/西北）',
  `occupation` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '职业',
  `cust_level` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '客户等级（普通/银卡/金卡/白金）',
  `reg_date` date DEFAULT NULL COMMENT '注册日期',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='保险客户表';
CREATE TABLE `ins_claim` (
  `id` bigint NOT NULL COMMENT '理赔ID',
  `claim_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '理赔单号',
  `policy_id` bigint DEFAULT NULL COMMENT '保单ID',
  `policy_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '保单号',
  `cust_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '客户姓名',
  `product_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '险种类型',
  `report_date` date DEFAULT NULL COMMENT '报案日期',
  `claim_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '理赔类型（医疗费用/身故赔付/车辆损失/意外伤害/重疾给付）',
  `claim_amount` decimal(12,2) DEFAULT NULL COMMENT '申请理赔金额（元）',
  `settle_amount` decimal(12,2) DEFAULT NULL COMMENT '实际赔付金额（元）',
  `status` varchar(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '理赔状态（待审核/已赔付/已拒赔）',
  `province` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '出险省份',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='保险理赔表';

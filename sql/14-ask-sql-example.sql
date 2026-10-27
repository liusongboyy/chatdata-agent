-- =====================================================================
-- SQL 范例库（few-shot）
--
-- 用途：给「直连模式」提供几道相似的（问题 → 正确 SQL）示例，收敛模型的写法。
--
-- ⚠️ **只有人工确认过的查询才能进这张表。**
--
--    不自动收录审计里"执行成功"的查询：执行成功不等于语义正确——
--    「上个月华东的保费」被写成全量保费，照样执行成功、照样有数字。
--    把它收进范例库，等于**教模型重复那个错误**，而且它会成为后续所有
--    相似问题的模板。spec §12.6 记的就是这个坑（BIRD Mini-Dev 32% 标注错误、
--    financial 域 49%），结论是"评测集本身会成为噪声源"——范例库同理。
--
--    故 source 只有三种，共同点是**都经过人工确认**：
--      MANUAL   —— 管理员手写/改写（默认）
--      AUDIT    —— 从审计里挑一条人工确认后存进来的（确认人是 created_by）
--      FEEDBACK —— 用户在问数页提交修正 SQL，管理员审核通过后写入（见 sql/16）
--    'AUTO' 是刻意不提供的取值：没有任何"自动收录"路径。
--
-- 为什么按 domain_id 存而不是全局：
--    范例里的 SQL 引用的是某个域的白名单表，跨域照抄必然查不到表。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `ask_sql_example` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `domain_id`   BIGINT       NOT NULL                COMMENT '所属语义域（范例只在本域内生效）',
  `question`    VARCHAR(500) NOT NULL                COMMENT '示例问法（中文，贴近业务人员的说法）',
  -- 用 TEXT 而不是 VARCHAR：复杂查询（多层 CTE）会超过 2000 字
  `sql_text`    TEXT         NOT NULL                COMMENT '该问题的正确 SQL',
  `note`        VARCHAR(500) NULL                    COMMENT '口径要点，会与范例一起进 prompt',
  -- 检索关键词。**命中即视为强相关**：人工维护的范例只有几十条，
  -- 真正决定"该不该用这条"的是业务概念而不是字面——实测过，
  -- 「分档」范例与「华东大额中额」的字面相似度只有 0.096，
  -- 而同一条范例与不相关的「各区域保费」有 0.057，靠阈值分不开
  `keywords`    VARCHAR(500) NULL                    COMMENT '检索关键词，顿号/逗号分隔',
  `source`      VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' COMMENT '来源 MANUAL/手工，AUDIT/从审计确认',
  `enabled`     TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否参与检索',
  `sort_no`     INT          NOT NULL DEFAULT 0      COMMENT '排序号',
  `created_by`  VARCHAR(64)  NULL                    COMMENT '创建人（AUDIT 来源时即确认人）',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by`  VARCHAR(64)  NULL,
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- 同一域内问法唯一，两个理由：
  --   1. **数据本身**：两条问法完全相同的范例是纯冗余——检索只会挑中其中一条，
  --      而管理员以为自己加了两条不同的写法。
  --   2. **实现依赖**：管理面的 create 是"插入后按问法读回主键"。
  --      问法不唯一时，读回会拿到错的那条 id。
  -- 作用域是 (domain_id, question) 而不是全局：同一个问法在不同域里引用不同的表。
  UNIQUE KEY `uk_ask_sql_example_domain_question` (`domain_id`, `question`),
  KEY `idx_ask_sql_example_domain` (`domain_id`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='直连模式的 SQL 范例库（few-shot）';

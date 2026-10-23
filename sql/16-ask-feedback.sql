-- =====================================================================
-- 用户反馈（问数结果 → 管理员审核 → SQL 范例）
--
-- 目的：把"用户发现答案不对并自己改了 SQL"沉淀成范例库的知识。
-- 这是问数准确率唯一会**随时间自动变好**的机制——其它手段都靠人预先配置。
--
-- ⚠️ **用户提交绝不直接进范例库。**
--    流程是：submit → PENDING → 管理员 APPROVED → 才写 ask_sql_example。
--    理由与 sql/14 一致：执行成功的 SQL 不等于语义正确，用户改的也可能改错。
--    没有审核这一道，"反馈闭环"就会变成"把错误放大给所有相似问题"。
--
-- 为什么单独一张表而不是直接给 ask_sql_example 加 pending 状态：
--    1. 反馈里还有"只是答错了、没给正确 SQL"（WRONG_ANSWER）与"有帮助"（HELPFUL），
--       它们没有对应的 SQL，塞不进范例表
--    2. 反馈是**审计对象**：谁在什么时候说哪条答案错了、谁批的，要能追溯
--    3. 范例表已经被检索路径读取，混入待审核记录会逼着每次查询都多一个状态过滤
-- =====================================================================

CREATE TABLE IF NOT EXISTS `ask_feedback` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `domain_id`     BIGINT       NOT NULL                COMMENT '数据域 id',
  `assistant_id`  VARCHAR(32)  NULL                    COMMENT '助理 id，可为空',
  `username`      VARCHAR(64)  NOT NULL                COMMENT '提交人',
  `question`      VARCHAR(2000) NOT NULL               COMMENT '原始问题',
  -- 反馈类型：
  --   HELPFUL        —— 答案有帮助（正向样本，用于统计）
  --   WRONG_ANSWER   —— 答案不对，但没给正确 SQL
  --   SQL_CORRECTION —— 答案不对，并给出了修正后的 SQL（可沉淀为范例）
  `feedback_type` VARCHAR(16)  NOT NULL                COMMENT 'HELPFUL/WRONG_ANSWER/SQL_CORRECTION',
  `generated_sql` TEXT         NULL                    COMMENT '系统实际执行的 SQL（用户看到的那条）',
  `corrected_sql` TEXT         NULL                    COMMENT '用户修正后的 SQL；仅 SQL_CORRECTION 有值',
  `note`          VARCHAR(500) NULL                    COMMENT '用户补充说明',
  `status`        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
  `example_id`    BIGINT       NULL                    COMMENT '审核通过后写入/复用的 ask_sql_example.id',
  `reviewed_by`   VARCHAR(64)  NULL                    COMMENT '审核人',
  `reviewed_at`   DATETIME     NULL                    COMMENT '审核时间',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- 管理台按状态倒序翻待审队列
  KEY `idx_ask_feedback_status` (`status`, `created_at`),
  -- 按域看反馈分布
  KEY `idx_ask_feedback_domain` (`domain_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问数结果反馈（审核后沉淀为 SQL 范例）';

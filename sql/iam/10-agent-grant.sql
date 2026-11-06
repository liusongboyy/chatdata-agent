-- =====================================================================
-- Agent 代理授权
--
-- 依据：chatdata-agent-design/agent-mesh/spec.md §4.4
--
-- 其他 agent 通过 A2A 调问数时，请求里会说「代表张三问」。
-- 这句话是**调用方说的**——无条件相信就是后门：任何内网服务说出管理员
-- 用户名，就能以他身份查数。
--
-- 这张表是唯一的真边界：用户**自己**授权过某个 agent 代表他。
--
-- 为什么不用权限码（iam_role_permission 里的 agent.proxy）：
--   · 权限码只能表达「有/没有」，而这里要按 agent 分别授权
--   · 权限码由管理员改角色，而"谁能替我查数"应该由我决定
--   · 这张表有授受时间与撤销时间，能回答问题"他什么时候允许的"
--
-- 与渠道绑定的区别：
--   iam_user.external_id 回答的是「这个飞书号是谁」；
--   本表回答的是「这个人同意哪个 agent 代表他」。两者判据不同，
--   所以 IAM 里是两个 grant（channel / agent-proxy），不共用。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `iam_agent_grant` (
  `username`     VARCHAR(64) NOT NULL COMMENT '被代理的用户',
  `agent_client` VARCHAR(64) NOT NULL COMMENT '允许哪个 agent 代理。与 A2A 请求的 X-Agent-Client 一致',
  `granted_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 软撤销：留记录才能回答"他什么时候取消的"。
  -- 硬删会让审计里出现"授权记录消失了"的空白期
  `revoked_at`   DATETIME    NULL     COMMENT '撤销时间；NULL 表示生效中',
  PRIMARY KEY (`username`, `agent_client`),
  -- 「这个 agent 能代理哪些人」也是常见查询（admin 排查用）
  KEY `idx_agent_grant_client` (`agent_client`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '用户授权某 agent 代表自己查数';

-- 验证用：手工给 admin 授权 report-bot，确认链路能否换到令牌。
-- 生产不要用这条——授权应当由用户自己在「我的账号」页完成。
-- INSERT INTO `iam_agent_grant` (`username`, `agent_client`)
-- VALUES ('admin', 'report-bot');

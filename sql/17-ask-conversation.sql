-- 问数会话历史
--
-- ask_agent_session 是 agentscope 的状态契约（内部 JSON，用于给模型喂上下文），
-- 不能用来做列表/搜索/回放。这里单独存一层"产品视图"：
--   ask_conversation  一条会话一行，负责归属、标题、排序
--   ask_message       逐条消息，负责回放与搜索
--
-- conversation.id 直接复用前端的 sessionId，agent 状态仍按 (username, id) 存，
-- 两层通过同一个 id 对齐，不额外引入映射。

CREATE TABLE IF NOT EXISTS ask_conversation (
    id            VARCHAR(64)  NOT NULL COMMENT '会话 id，即 agentscope sessionId',
    username      VARCHAR(64)  NOT NULL COMMENT '归属人（令牌里的用户名）',
    assistant_id  VARCHAR(32)  NULL     COMMENT '会话绑定的助理',
    title         VARCHAR(255) NOT NULL COMMENT '首问截断后的标题',
    preview       VARCHAR(512) NULL     COMMENT '最后一条消息摘要',
    message_count INT          NOT NULL DEFAULT 0 COMMENT '消息数，也是 seq 分配依据',
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '软删除：1 表示用户已删除',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_conv_user_updated (username, deleted, updated_at),
    KEY idx_conv_user_created (username, deleted, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '问数会话（产品视图）';

CREATE TABLE IF NOT EXISTS ask_message (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(64)  NOT NULL,
    seq             INT          NOT NULL COMMENT '会话内递增，从 1 开始',
    role            VARCHAR(16)  NOT NULL COMMENT 'USER / ASSISTANT',
    kind            VARCHAR(16)  NOT NULL DEFAULT 'question' COMMENT 'question / recompute',
    content         LONGTEXT     NULL     COMMENT '用户问题或助理文字摘要',
    result_json     LONGTEXT     NULL     COMMENT '助理结果事件 JSON，供历史回放',
    status          VARCHAR(16)  NOT NULL COMMENT 'SUCCESS / FAILED / STREAMING',
    failure_code    VARCHAR(32)  NULL,
    failure_message VARCHAR(512) NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_conv_seq (conversation_id, seq),
    KEY idx_msg_conv (conversation_id, seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '问数会话消息';

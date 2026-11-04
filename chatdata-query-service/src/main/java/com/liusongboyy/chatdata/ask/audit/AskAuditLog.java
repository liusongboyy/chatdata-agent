package com.liusongboyy.chatdata.ask.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 一次问数的审计记录。
 *
 * <p>字段取舍见 {@code sql/04-audit.sql} 的说明——尤其是为什么没有「改写前的 SQL」，
 * 以及为什么 {@code roles} 是快照而不是引用。
 */
@Data
@TableName("ask_audit_log")
public class AskAuditLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 提问者身份（SSO 用户名）。 */
    private String username;

    /** 当次请求的角色快照，逗号分隔。权限事后可能变更，审计需要"当时"的凭据。 */
    private String roles;

    /** 提问原文。 */
    private String question;

    private String assistantId;

    private String domainId;

    /** 语义意图（指标/维度/时间/筛选），JSON。 */
    private String dslJson;

    /** 实际执行的 SQL——已包含权限改写（行级规则体现为子查询包裹 + 外层 WHERE）。 */
    private String sqlText;

    /** 返回行数；失败时为空。刻意不存结果集本身。 */
    private Integer rowCount;

    /** 端到端耗时（毫秒）。 */
    private Long durationMs;

    /** {@code SUCCESS} 或 {@code FAILURE}。 */
    private String outcome;

    /** 失败原因摘要；成功时为空。 */
    private String errorMessage;

    private LocalDateTime createdAt;

    // ── 计量（见 sql/24-model-cost.sql）──────────────────────────────
    // **六列全部可空**：加列之前的历史记录没有这些值，失败的请求也可能拿不到 usage。
    // null 是"没采到"，0 是"确实没消耗"——成本看板靠这个区分「未知」与「免费」

    /** 实际使用的模型键。为空表示回落到了 yml 配置（或老数据）。 */
    private String modelKey;

    /** 输入 token。**已包含 {@link #cachedTokens}**，不能相加。 */
    private Integer inputTokens;

    /** 输出 token。**已包含 reasoning tokens**，不能相加。 */
    private Integer outputTokens;

    /** 缓存命中的输入 token，是 {@link #inputTokens} 的子集。 */
    private Integer cachedTokens;

    /** 写缓存的消耗（部分供应商单独计费）。 */
    private Integer cacheCreationTokens;

    /** 推理 token，是 {@link #outputTokens} 的子集。 */
    private Integer reasoningTokens;
}

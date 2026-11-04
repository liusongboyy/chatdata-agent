package com.liusongboyy.chatdata.engine.audit.mapper;

import com.liusongboyy.chatdata.engine.audit.AskAuditRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审计落库。
 *
 * <p>写入的是本项目已有的 {@code ask_audit_log}（见 {@code sql/04-audit.sql}），
 * 与厂商集成那条链路共用一张表——PRD §6 决定复用而非另建，
 * 理由是这个表的语义（谁、问了什么、实际执行了什么、多久、什么结果）
 * 与链路无关。
 */
@Mapper
public interface EngineAuditMapper {

    @Insert("""
            INSERT INTO ask_audit_log
              (username, roles, question, assistant_id, domain_id, dsl_json, sql_text,
               row_count, duration_ms, outcome, error_message, created_at,
               batch_id, step_no, plan_json, narrative,
               model_key, input_tokens, output_tokens, cached_tokens,
               cache_creation_tokens, reasoning_tokens)
            VALUES
              (#{username}, #{roles}, #{question}, #{assistantId}, #{domainId}, #{dslJson}, #{sqlText},
               #{rowCount}, #{durationMs}, #{outcome}, #{errorMessage}, #{createdAt},
               #{batchId}, #{stepNo}, #{planJson}, #{narrative},
               #{modelKey}, #{inputTokens}, #{outputTokens}, #{cachedTokens},
               #{cacheCreationTokens}, #{reasoningTokens})
            """)
    int insert(AskAuditRecord record);
}

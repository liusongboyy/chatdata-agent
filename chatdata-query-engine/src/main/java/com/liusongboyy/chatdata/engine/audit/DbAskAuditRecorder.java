package com.liusongboyy.chatdata.engine.audit;

import com.liusongboyy.chatdata.engine.audit.mapper.EngineAuditMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 审计落库实现。
 *
 * <p><strong>吞掉所有异常。</strong>见 {@link AskAuditRecorder} 的说明：
 * 用户问到了数据却因为审计写不进去而拿不到结果，这个取舍是错的。
 * 写失败记 error 日志——**必须显式记**，否则审计静默丢失就没人知道了。
 */
public class DbAskAuditRecorder implements AskAuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(DbAskAuditRecorder.class);

    private final EngineAuditMapper mapper;

    public DbAskAuditRecorder(EngineAuditMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void record(AskAuditRecord record) {
        try {
            mapper.insert(record);
        } catch (Exception e) {
            // 记 error 而不是 warn：审计丢失是合规问题，不是噪音。
            // 但仍然不向上抛——见类注释里的取舍
            log.error("审计写入失败：user={} outcome={} question={}",
                    record.username(), record.outcome(), abbreviate(record.question()), e);
        }
    }

    /** 日志里的问题只留开头，避免把整段提问灌进去。 */
    private static String abbreviate(String s) {
        if (s == null) {
            return "<null>";
        }
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }
}

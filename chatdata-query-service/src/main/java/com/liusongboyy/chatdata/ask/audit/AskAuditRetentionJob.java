package com.liusongboyy.chatdata.ask.audit;

import com.liusongboyy.chatdata.ask.audit.mapper.AskAuditMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计记录的保留期清理。
 *
 * <p>存在的理由：保留期如果只写在文档里而不执行，审计表会无界增长——
 * 最终要么磁盘告警，要么有人手动清库时把该留的一起删了。把保留期变成会执行的代码，
 * 它才是一个真实的约束。
 */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(AskAuditProperties.class)
public class AskAuditRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AskAuditRetentionJob.class);

    private final AskAuditMapper auditMapper;
    private final AskAuditProperties properties;

    @Scheduled(cron = "${chatdata.audit.retention-cron:0 30 3 * * *}")
    public void purgeExpired() {
        try {
            LocalDateTime before = LocalDateTime.now().minusDays(properties.getRetentionDays());
            int deleted = auditMapper.deleteCreatedBefore(before);
            if (deleted > 0) {
                log.info("审计保留期清理：删除 {} 条早于 {} 的记录", deleted, before);
            }
        } catch (Exception e) {
            // 清理失败不该影响服务可用性，但必须可见——它意味着保留期正在被突破
            log.error("审计保留期清理失败，过期记录仍在累积", e);
        }
    }
}

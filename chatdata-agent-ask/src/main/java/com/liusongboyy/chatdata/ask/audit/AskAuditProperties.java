package com.liusongboyy.chatdata.ask.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chatdata.audit")
public class AskAuditProperties {

    /**
     * 审计记录保留天数。
     *
     * <p>不低于 180 天。这不是一个可以随意调小的运维参数——它是合规承诺，
     * 调小意味着某些历史问数记录无法再被追溯。
     */
    private int retentionDays = 180;

    /** 保留期清理的执行时间（cron）。默认每天 03:30，避开业务高峰。 */
    private String retentionCron = "0 30 3 * * *";

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public String getRetentionCron() {
        return retentionCron;
    }

    public void setRetentionCron(String retentionCron) {
        this.retentionCron = retentionCron;
    }
}

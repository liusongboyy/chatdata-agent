package com.liusongboyy.chatdata.engine.startup;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 引擎启动校验的配置。
 *
 * <p>与厂商那套的 {@code AccessGrantProperties} 同样的取舍：
 * <b>默认拒绝启动</b>。一个漏配授权的域就是一次全量数据暴露，
 * 而它的失败形态是静默的——不报错，只是数据都能看到。
 */
@ConfigurationProperties(prefix = "chatdata.engine.startup")
public class EngineStartupProperties {

    /**
     * 是否在启动时校验引擎配置。
     *
     * <p>默认开。关掉它只在一种场景下有意义：配置已损坏到应用起不来、
     * 而你需要先把应用拉起来才能改配置。**注意本项目的配置在库里，
     * 用 SQL 就能改，所以不存在"起不来就改不了"的死锁**——
     * 见 {@link EngineStartupCheck} 的说明。
     */
    private boolean enabled = true;

    /**
     * 发现违规时是否拒绝启动。
     *
     * <p>默认 true。改成 false 只应作为临时手段：它会把"配置有问题"
     * 降级成一条日志，而日志没人看。
     */
    private boolean failOnViolation = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFailOnViolation() {
        return failOnViolation;
    }

    public void setFailOnViolation(boolean failOnViolation) {
        this.failOnViolation = failOnViolation;
    }
}

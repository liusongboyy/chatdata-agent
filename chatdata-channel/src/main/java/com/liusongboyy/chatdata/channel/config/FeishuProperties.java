package com.liusongboyy.chatdata.channel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 飞书应用配置。
 *
 * <p>{@code encryptKey} <b>必填</b>：不配的话飞书明文推送事件，
 * 而 body 里有用户提问原文与 open_id。启动时校验见 {@code FeishuEventController}。
 */
@ConfigurationProperties(prefix = "channel.feishu")
public class FeishuProperties {

    private boolean enabled = true;

    private String appId;

    private String appSecret;

    /** 加密策略里的 Encrypt Key。用于验签与解密，见 spec §3.3。 */
    private String encryptKey;

    /**
     * 飞书 API 域名。留在配置里是为了将来接 Lark 国际版或测试网关。
     */
    private String baseUrl = "https://open.feishu.cn";

    /**
     * 群聊回复方式。
     *
     * <p>{@code in-group}：直接在群里引用回复（已定，见 spec §6.6）。
     * {@code private-only}：只私聊回复。保留这个开关是因为跨权限的群
     * 应该把机器人移出去，但有些部署想先用更保守的方式起步。
     */
    private String groupReplyMode = "in-group";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String getEncryptKey() {
        return encryptKey;
    }

    public void setEncryptKey(String encryptKey) {
        this.encryptKey = encryptKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getGroupReplyMode() {
        return groupReplyMode;
    }

    public void setGroupReplyMode(String groupReplyMode) {
        this.groupReplyMode = groupReplyMode;
    }
}

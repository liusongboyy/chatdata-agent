package com.liusongboyy.chatdata.iam.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * IAM 的部署配置。
 *
 * <p><strong>密钥没有默认值。</strong>客户端 secret 缺失时启动失败，
 * 而不是用一个开发默认值悄悄跑起来——那会一路带到生产。
 */
@ConfigurationProperties(prefix = "iam")
public class IamProperties {

    /** 当前部署服务的租户。短期只有一个。 */
    private String tenant = "t_default";

    /** 授权服务器对外的 issuer，必须与客户端实际访问的地址一致。 */
    private String issuer = "http://127.0.0.1:9000";

    private final Clients clients = new Clients();

    private final FunctionalPermissions functionalPermissions = new FunctionalPermissions();

    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public Clients getClients() {
        return clients;
    }

    public FunctionalPermissions getFunctionalPermissions() {
        return functionalPermissions;
    }

    /**
     * 功能权限（页面 / 按钮 / tab）的开关与缓存。
     *
     * <p><strong>这个开关是回滚闸。</strong>关闭后 IAM 与 ask 两侧都退回旧的
     * "必须持有 {@code chat2bi}" 行为。存在的理由：这套改动把粗粒度的角色门槛
     * 换成了逐项判定，一旦 IAM 回滚，{@code /iam/principal} 不再返回权限，
     * ask 的管理面会整体 403——那时需要一个不需要改代码就能退回去的开关。
     */
    public static class FunctionalPermissions {

        /** 关闭后 {@code @perm.has(...)} 一律放行，过滤链维持旧的 {@code hasRole}。 */
        private boolean enabled = true;

        /**
         * 权限解析缓存秒数。
         *
         * <p>它就是"改完角色权限多久生效"的上限。默认 5 秒——这是一处 UI 判定，
         * 不值得更慢。
         */
        private long cacheSeconds = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getCacheSeconds() {
            return cacheSeconds;
        }

        public void setCacheSeconds(long cacheSeconds) {
            this.cacheSeconds = cacheSeconds;
        }
    }

    public static class Clients {

        /** Web BFF 客户端。机密客户端，走授权码 + PKCE。 */
        private String bffId = "chatdata-web-bff";
        private String bffSecret;
        private String bffRedirectUri = "http://127.0.0.1:8080/bff/callback";

        /** 资源服务器客户端。用于内省时证明"我是可信服务"。 */
        private String resourceId = "ask-resource-server";
        private String resourceSecret;

        /**
         * Channel 客户端（飞书/钉钉/企微机器人）。
         *
         * <p>它用自定义 grant 换取「代表某个已绑定用户」的令牌。
         * 与资源服务器的区别：资源服务器证明"我是可信服务"，
         * channel 还要证明"这个用户确实绑定在我的渠道上"——见 ChannelGrantAuthenticationProvider。
         */
        private String channelId = "channel-bot";
        private String channelSecret;

        public String getBffId() {
            return bffId;
        }

        public void setBffId(String bffId) {
            this.bffId = bffId;
        }

        public String getBffSecret() {
            return bffSecret;
        }

        public void setBffSecret(String bffSecret) {
            this.bffSecret = bffSecret;
        }

        public String getBffRedirectUri() {
            return bffRedirectUri;
        }

        public void setBffRedirectUri(String bffRedirectUri) {
            this.bffRedirectUri = bffRedirectUri;
        }

        public String getResourceId() {
            return resourceId;
        }

        public void setResourceId(String resourceId) {
            this.resourceId = resourceId;
        }

        public String getResourceSecret() {
            return resourceSecret;
        }

        public void setResourceSecret(String resourceSecret) {
            this.resourceSecret = resourceSecret;
        }

        public String getChannelId() {
            return channelId;
        }

        public void setChannelId(String channelId) {
            this.channelId = channelId;
        }

        public String getChannelSecret() {
            return channelSecret;
        }

        public void setChannelSecret(String channelSecret) {
            this.channelSecret = channelSecret;
        }
    }
}

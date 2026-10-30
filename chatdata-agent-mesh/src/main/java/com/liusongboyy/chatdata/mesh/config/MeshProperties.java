package com.liusongboyy.chatdata.mesh.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Agent mesh 的部署配置。
 *
 * <p><b>密钥没有默认值。</b>缺失时启动失败——与 IAM 的 channel / agent-mesh
 * 客户端注册同一取舍：共用默认密钥等于谁都能冒充。
 */
@ConfigurationProperties(prefix = "mesh")
public class MeshProperties {

    private final Iam iam = new Iam();
    private final Ask ask = new Ask();
    private final Identity identity = new Identity();
    private final A2a a2a = new A2a();
    private final Nacos nacos = new Nacos();

    public Iam getIam() {
        return iam;
    }

    public Ask getAsk() {
        return ask;
    }

    public Identity getIdentity() {
        return identity;
    }

    public A2a getA2a() {
        return a2a;
    }

    public Nacos getNacos() {
        return nacos;
    }

    /** 换令牌用。与 IAM 里注册的 {@code agent-mesh} 客户端一致。 */
    public static class Iam {

        private String baseUrl = "http://127.0.0.1:9000";

        private String clientId = "agent-mesh";

        private String clientSecret;

        /**
         * 令牌提前刷新量。
         *
         * <p>卡在到期那一刻去换，会因为时钟偏差与网络往返拿到一张已过期的令牌，
         * 请求随即 401，而错误看起来像"权限问题"。
         */
        private Duration refreshAhead = Duration.ofSeconds(30);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public Duration getRefreshAhead() {
            return refreshAhead;
        }

        public void setRefreshAhead(Duration refreshAhead) {
            this.refreshAhead = refreshAhead;
        }
    }

    /** 问数接口。 */
    public static class Ask {

        private String baseUrl = "http://127.0.0.1:8081";

        /**
         * 超时。必须大于 ask 的 SSE 超时（180 秒）——比它短的话，
         * 我们会在 ask 还在算的时候先断开，调用方看到超时而实际查询成功了。
         */
        private Duration timeout = Duration.ofSeconds(200);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    /** 身份映射的准入限制。 */
    public static class Identity {

        /**
         * 允许调用本 agent 的客户端白名单。
         *
         * <p><b>这不是安全边界。</b>{@code X-Agent-Client} 头伪造成本为零。
         * 它只防误接。真正的边界是 IAM 的 {@code iam_agent_grant}——
         * 用户必须授权过这个 agent。
         *
         * <p>留空表示不校验（仅限本地开发）。
         */
        private List<String> allowedClients = List.of();

        public List<String> getAllowedClients() {
            return allowedClients;
        }

        public void setAllowedClients(List<String> allowedClients) {
            this.allowedClients = allowedClients == null ? List.of() : allowedClients;
        }
    }

    /** A2A 端点与 AgentCard。 */
    public static class A2a {

        /**
         * AgentCard 里写出去的地址。
         *
         * <p><b>必须能被其他 agent 访问到。</b>写 {@code 127.0.0.1} 时，
         * 别的机器上的 agent 拿到这个地址后连的是它自己——
         * 表现为「注册成功但调用失败」。
         */
        private String advertisedHost = "127.0.0.1";

        private int advertisedPort = 20001;

        private String path = "/a2a";

        /** Agent 名称。其他 agent 在 Nacos 里按它发现。 */
        private String agentName = "chatdata-ask";

        private String agentDescription = "语义层问数：把业务问题翻译成受权限约束的查询";

        private String version = "1.0.0";

        public String getAdvertisedHost() {
            return advertisedHost;
        }

        public void setAdvertisedHost(String advertisedHost) {
            this.advertisedHost = advertisedHost;
        }

        public int getAdvertisedPort() {
            return advertisedPort;
        }

        public void setAdvertisedPort(int advertisedPort) {
            this.advertisedPort = advertisedPort;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getAgentName() {
            return agentName;
        }

        public void setAgentName(String agentName) {
            this.agentName = agentName;
        }

        public String getAgentDescription() {
            return agentDescription;
        }

        public void setAgentDescription(String agentDescription) {
            this.agentDescription = agentDescription;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }
    }

    /** Nacos 注册。 */
    public static class Nacos {

        /**
         * 是否注册到 Nacos。本地开发可以关掉——没有 Nacos 时
         * A2A 端点照样能用（直接按地址调）。
         */
        private boolean enabled = false;

        /**
         * 命名空间。建议独立，与业务配置分开——其他 agent 的客户端
         * 可能按命名空间批量拉取，混在一起会拉到不相关的。
         */
        private String namespace = "public";

        /**
         * Nacos 地址。
         *
         * <p>3.x 的写法：写**映射后的主端口**（如 {@code 127.0.0.1:18848}），
         * 客户端会自动用「主端口 + 1000」连 gRPC。所以 gRPC 端口也必须映射，
         * 否则报「连接超时」——那个错误看不出是端口问题。
         */
        private String serverAddr = "127.0.0.1:18848";

        public String getServerAddr() {
            return serverAddr;
        }

        public void setServerAddr(String serverAddr) {
            this.serverAddr = serverAddr;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }
    }
}

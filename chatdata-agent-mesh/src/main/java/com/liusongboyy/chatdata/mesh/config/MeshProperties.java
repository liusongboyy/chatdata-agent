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
    private final Aistio aistio = new Aistio();

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

    public Aistio getAistio() {
        return aistio;
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

        /**
         * 建连超时。
         *
         * <p>和上面的读超时是两回事：读超时管「连上了但没回」，建连超时管
         * 「根本连不上」。ask 挂掉时如果不设这个，请求会挂在 TCP 握手上，
         * 而那种情况下我们应该几秒内就告诉调用方「服务不可用」，
         * 不该让它等满 200 秒。
         */
        private Duration connectTimeout = Duration.ofSeconds(10);

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

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
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

        /**
         * 这个 agent 背后的数据域 id。
         *
         * <h2>为什么必须显式配</h2>
         *
         * <p>不指定 domainId 时，ask 会用「第一个启用中的域」——那通常是保险域。
         * 于是 AgentCard 声明「门店分析」，调用方问门店问题，拿到的却是保费数字。
         *
         * <p>这类错误的可怕之处在于它**不报错**：SQL 合法、数据真实、数字合理，
         * 只是回答的不是问的那个问题。调用方无从察觉。
         *
         * <p>与 AgentCard 的 skill 必须对应——{@link #skills} 里只声明这一个域的能力。
         */
        private String domainId = "2300";

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

        public String getDomainId() {
            return domainId;
        }

        public void setDomainId(String domainId) {
            this.domainId = domainId;
        }
    }

    /**
     * aistio：接入 agentscope service 的控制面。
     *
     * <p>与 Nacos 是**两条独立通道**：Nacos 给其他 agent 发现用，
     * aistio 给 agentscope service 管理台看/编排用。
     */
    public static class Aistio {

        /** 本地没起 agentscope service 时保持 false。 */
        private boolean enabled = false;

        /** Agent 标识。控制面用它区分不同 agent。 */
        private String agentKey = "chatdata-ask";

        /**
         * gRPC（ASDP 数据面）地址，只填 host:port。
         *
         * <p>和下面的 HTTP 地址<b>不是同一个端口</b>：control 是 Go 服务，
         * REST/控制台在 8081，ASDP gRPC 在 15010。写成同一个端口的表现是
         * HTTP 自注册成功、管理台能看到 agent，但发对话报
         * "asdp: instance not connected"——因为 gRPC 长连接从没建起来。
         *
         * <p><b>不能带 scheme。</b>带 {@code http://} 会让 gRPC 的
         * NameResolver 直接报错。
         */
        private String controlPlane = "127.0.0.1:15010";

        /** 自注册用的 HTTP 地址（带 scheme）。 */
        private String controlPlaneHttp = "http://127.0.0.1:8081";

        /** 控制面的内部令牌，来自 agentscope service 的 BUILDER_INTERNAL_TOKEN。 */
        private String internalToken;

        private String tenant = "default";

        private String namespace = "default";

        /** 是否上报事件流。本期没有 agent 循环可观测，保持 false。 */
        private boolean enableEvents = false;

        /** 控制面回调 agent 的端口（查上下文/消息）。要一个没被占用的本地端口。 */
        private int contractHttpPort = 18090;

        /**
         * 契约端点对外的地址。
         *
         * <p><b>control 在容器里时不能用 127.0.0.1</b>——那会指向容器自己。
         * 必须写 {@code http://host.docker.internal:<contract-port>}。
         * 写在容器外跑 control 的场合才用 127.0.0.1。
         *
         * <p>配错的症状：Dashboard 里能看到 agent，但 Sessions 页永远是空的——
         * 因为 control 的回调打不到我们。
         */
        private String publicBaseUrl = "http://host.docker.internal:18090";

        /**
         * 管理台对话时「代表谁」。
         *
         * <p>aistio 的 runConversationTurn 只有 sessionId 与文本，没有用户身份。
         * 所以用这两个配置项约定一个默认的被代理用户——管理员在看这个 agent 时，
         * 看到的是这个用户权限范围内的数据。
         *
         * <p>必须仍然走「换令牌 → 调 ask」的完整链路，不能绕过权限。
         * 否则管理台就成了能看全部数据的后门，而我们建授权表正是为了防这个。
         */
        private String defaultAgentClient = "report-bot";
        private String defaultUser = "admin";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAgentKey() {
            return agentKey;
        }

        public void setAgentKey(String agentKey) {
            this.agentKey = agentKey;
        }

        public String getControlPlane() {
            return controlPlane;
        }

        public void setControlPlane(String controlPlane) {
            this.controlPlane = controlPlane;
        }

        public String getControlPlaneHttp() {
            return controlPlaneHttp;
        }

        public void setControlPlaneHttp(String controlPlaneHttp) {
            this.controlPlaneHttp = controlPlaneHttp;
        }

        public String getInternalToken() {
            return internalToken;
        }

        public void setInternalToken(String internalToken) {
            this.internalToken = internalToken;
        }

        public String getTenant() {
            return tenant;
        }

        public void setTenant(String tenant) {
            this.tenant = tenant;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public boolean isEnableEvents() {
            return enableEvents;
        }

        public void setEnableEvents(boolean enableEvents) {
            this.enableEvents = enableEvents;
        }

        public int getContractHttpPort() {
            return contractHttpPort;
        }

        public void setContractHttpPort(int contractHttpPort) {
            this.contractHttpPort = contractHttpPort;
        }

        public String getPublicBaseUrl() {
            return publicBaseUrl;
        }

        public void setPublicBaseUrl(String publicBaseUrl) {
            this.publicBaseUrl = publicBaseUrl;
        }

        public String getDefaultAgentClient() {
            return defaultAgentClient;
        }

        public void setDefaultAgentClient(String defaultAgentClient) {
            this.defaultAgentClient = defaultAgentClient;
        }

        public String getDefaultUser() {
            return defaultUser;
        }

        public void setDefaultUser(String defaultUser) {
            this.defaultUser = defaultUser;
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

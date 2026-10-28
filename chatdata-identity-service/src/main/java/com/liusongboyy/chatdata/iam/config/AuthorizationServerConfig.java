package com.liusongboyy.chatdata.iam.config;

import com.liusongboyy.chatdata.iam.channel.ChannelGrantType;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.UUID;

/**
 * 授权服务器的存储与客户端注册。
 *
 * <h2>为什么用 JDBC 而不是内存实现</h2>
 *
 * <p>授权码、access token、refresh token 都必须可查、可撤销、可跨实例共享。
 * 内存实现一旦多实例就互相看不见，重启还会丢——那与"不用 JWT、状态可查"的
 * 目标直接冲突。
 *
 * <h2>access token 是不透明的</h2>
 *
 * <p>每个客户端都显式设置 {@link OAuth2TokenFormat#REFERENCE}。
 * 即便令牌生成器里有 JwtGenerator，客户端设置会决定实际签发哪种令牌。
 */
@Configuration
public class AuthorizationServerConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationServerConfig.class);

    @Bean
    public RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbc) {
        return new JdbcRegisteredClientRepository(jdbc);
    }

    @Bean
    public OAuth2AuthorizationService authorizationService(
            JdbcTemplate jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc, clients);
    }

    @Bean
    public OAuth2AuthorizationConsentService authorizationConsentService(
            JdbcTemplate jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings(IamProperties props) {
        return AuthorizationServerSettings.builder()
                .issuer(props.getIssuer())
                .build();
    }

    /**
     * 签名密钥。
     *
     * <p>access token 是不透明的，这里主要供 OIDC 的 id_token 使用。
     * 当前每次启动重新生成：id_token 只在认证那一刻被消费，重启失效可以接受。
     * 启用 OIDC 前要改成从 KMS/密钥库加载，并支持 kid 轮换。
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        KeyPair pair = generateRsaKey();
        RSAKey key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
        return new ImmutableJWKSet<>(new JWKSet(key));
    }

    private static KeyPair generateRsaKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("无法生成授权服务器签名密钥", e);
        }
    }

    /**
     * 首次启动注册客户端；已存在则不动。
     *
     * <p>不覆盖已有记录：管理员可能已经轮换过 secret，启动时覆盖会把线上客户端踢掉。
     */
    @Bean
    public ApplicationRunner seedClients(RegisteredClientRepository clients,
                                         IamProperties props,
                                         PasswordEncoder passwordEncoder) {
        return args -> {
            seedBff(clients, props, passwordEncoder);
            seedResource(clients, props, passwordEncoder);
            seedChannel(clients, props, passwordEncoder);
        };
    }

    private static void seedBff(RegisteredClientRepository clients, IamProperties props,
                                PasswordEncoder encoder) {
        if (clients.findByClientId(props.getClients().getBffId()) != null) {
            return;
        }
        String secret = props.getClients().getBffSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "缺少 iam.clients.bff-secret（环境变量 IAM_BFF_SECRET）。"
                            + "客户端密钥不设默认值——共用默认值等于谁都能冒充 BFF。");
        }
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(props.getClients().getBffId())
                .clientSecret(encoder.encode(secret))
                .clientName("问数 Web BFF")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(props.getClients().getBffRedirectUri())
                .scope("ask.read")
                .scope("ask.feedback")
                .clientSettings(ClientSettings.builder()
                        // OAuth 2.1：授权码流程强制 PKCE；BFF 是机密客户端也不例外
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(referenceTokens())
                .build();
        clients.save(client);
        log.info("已注册 OAuth 客户端：{}", client.getClientId());
    }

    private static void seedResource(RegisteredClientRepository clients, IamProperties props,
                                     PasswordEncoder encoder) {
        if (clients.findByClientId(props.getClients().getResourceId()) != null) {
            return;
        }
        String secret = props.getClients().getResourceSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "缺少 iam.clients.resource-secret（环境变量 IAM_RESOURCE_SECRET）。");
        }
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(props.getClients().getResourceId())
                .clientSecret(encoder.encode(secret))
                .clientName("问数资源服务器")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("iam.principal")
                .tokenSettings(referenceTokens())
                .build();
        clients.save(client);
        log.info("已注册 OAuth 客户端：{}", client.getClientId());
    }

    /**
     * 渠道机器人（飞书/钉钉/企微）。
     *
     * <p>它只能换「已绑定用户」的令牌——权限边界在
     * {@code ChannelGrantAuthenticationProvider} 里，不在这里。
     *
     * <p>**只管着 ask.read / ask.feedback**，与 BFF 同权。渠道不该比浏览器多任何能力：
     * 它能看到的，就是那个人在 Web 上能看到的。
     */
    private static void seedChannel(RegisteredClientRepository clients, IamProperties props,
                                    PasswordEncoder encoder) {
        if (clients.findByClientId(props.getClients().getChannelId()) != null) {
            return;
        }
        String secret = props.getClients().getChannelSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "缺少 iam.clients.channel-secret（环境变量 IAM_CHANNEL_SECRET）。"
                            + "不设默认值——共用默认值等于谁都能冒充渠道为任意用户签发令牌。");
        }
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(props.getClients().getChannelId())
                .clientSecret(encoder.encode(secret))
                .clientName("渠道机器人")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(ChannelGrantType.CHANNEL)
                .scope("ask.read")
                .scope("ask.feedback")
                // 渠道不签发 refresh token，但 settings 仍是 reference——
                // 决定 access token 的格式
                .tokenSettings(TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                        // 5 分钟。渠道拿到就立刻用，不需要长命；短 TTL 削弱泄露影响面
                        .accessTokenTimeToLive(Duration.ofMinutes(5))
                        .build())
                .build();
        clients.save(client);
        log.info("已注册 OAuth 客户端：{}", client.getClientId());
    }

    private static TokenSettings referenceTokens() {
        return TokenSettings.builder()
                .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                .accessTokenTimeToLive(Duration.ofMinutes(30))
                .refreshTokenTimeToLive(Duration.ofDays(14))
                // OAuth 2.1：刷新即轮换，旧的立即失效
                .reuseRefreshTokens(false)
                .build();
    }
}

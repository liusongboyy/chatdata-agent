-- =====================================================================
-- 演示用的 embed 宿主客户端（**仅本地开发**）
--
-- 生产环境不要用这个脚本：它的密钥是固定的，且授权关系写死了助理 2200。
-- 真实的宿主接入应当走「注册客户端 + 配 iam_embed_client_assistant」两条。
--
-- 用法：验证 embed grant 能不能换到令牌、令牌能不能问数、越权会不会被拒。
-- =====================================================================

-- 客户端。密钥是 demo-secret（BCrypt 哈希见下）。
-- 用 INSERT IGNORE 让它可重复执行
INSERT IGNORE INTO `oauth2_registered_client` (
  id, client_id, client_id_issued_at, client_secret, client_name,
  client_authentication_methods, authorization_grant_types,
  redirect_uris, post_logout_redirect_uris, scopes,
  client_settings, token_settings
) VALUES (
  'demo-embed-host-0001',
  'demo-embed-host',
  NOW(),
  -- BCrypt("demo-secret")。与 channel-bot 同一个哈希算法
  '$2a$10$uMPhalFP1HiPDFtbK7gaK.MYDHp8buK/dusFlbywZL00Ad6FMk9u6',
  '演示宿主系统',
  'client_secret_basic',
  'urn:chatdata:params:oauth:grant-type:embed',
  '', '',
  'ask.read,ask.feedback',
  '{"@class":"java.util.Collections$UnmodifiableMap","settings.client.require-proof-key":true,"settings.client.require-authorization-consent":false}',
  '{"@class":"java.util.Collections$UnmodifiableMap","settings.token.reuse-refresh-tokens":true,"settings.token.x509-certificate-bound-access-tokens":false,"settings.token.id-token-signature-algorithm":["org.springframework.security.oauth2.jose.jws.SignatureAlgorithm","RS256"],"settings.token.access-token-time-to-live":["java.time.Duration","PT5M"],"settings.token.access-token-format":{"@class":"org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat","value":"reference"},"settings.token.refresh-token-time-to-live":["java.time.Duration","PT1H"],"settings.token.authorization-code-time-to-live":["java.time.Duration","PT5M"],"settings.token.device-code-time-to-live":["java.time.Duration","PT5M"]}'
);

-- 授权：这个宿主只能问助理 2200（便利店订单助手）
INSERT IGNORE INTO `iam_embed_client_assistant` (client_id, assistant_id)
VALUES ('demo-embed-host', '2200');

-- 用户映射：用 lisi 而不是 admin。
--
-- **不能复用 admin**：external_provider 是单值的，admin 已经绑了飞书，
-- 改成 embed 会把飞书绑定覆盖掉——实测踩到过，改完飞书就登不进去了。
--
-- 这是既有设计的限制（一个人只能有一个外部身份）。
-- 真实场景里两种接入很少同时用，暂不改表结构。
UPDATE `iam_user` SET external_provider = 'embed', external_id = 'crm-user-1'
WHERE username = 'lisi';

SELECT c.client_id, a.assistant_id, u.username, u.external_id
FROM oauth2_registered_client c
JOIN iam_embed_client_assistant a ON a.client_id = c.client_id
LEFT JOIN iam_user u ON u.external_provider = 'embed'
WHERE c.client_id = 'demo-embed-host';

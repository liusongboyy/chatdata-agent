-- =====================================================================
-- 给 channel-bot 加「自查外部身份」的能力
--
-- 背景：channel 原来直连 IAM 的库查绑定关系
--   SELECT username FROM chatdata_iam.iam_user WHERE external_provider=? AND external_id=?
-- IAM 要独立成平台，这条跨库耦合必须先断掉（见
-- .scratch/iam-platform-extraction/spec.md §6.5），改成调
--   GET /iam/identity/resolve?provider=&externalId=
--
-- 那条路要客户端凭证 + iam.identity scope。
--
-- **为什么不复用 ask-resource-server 的 iam.principal**：
-- 那个 scope 能读全量用户目录（/iam/policy/users）。渠道只需要问
-- 「这一个 open_id 是谁」，给它读全量用户目录是**用不上的权限**——
-- 给多了不是方便，是让一次密钥泄露的后果变大。
--
-- 只在**已存在**的 channel-bot 上追加，不新建客户端：
-- 密钥由 IamProperties 从环境变量读，脚本里不该出现明文。
-- =====================================================================

UPDATE `oauth2_registered_client`
SET authorization_grant_types = 'urn:chatdata:params:oauth:grant-type:channel,client_credentials',
    scopes = 'ask.read,ask.feedback,iam.identity'
WHERE client_id = 'channel-bot';

-- 核对：应看到 channel-bot 有两条 grant、三个 scope
SELECT client_id, authorization_grant_types, scopes
FROM oauth2_registered_client
WHERE client_id = 'channel-bot';

---
Status: accepted
---

# IAM 独立化：OAuth 2.1 + 不透明令牌，网关作为唯一入口

> 本 ADR 是 [`chatdata-agent-design/iam-platform/spec.md`]((https://github.com/liusongboyy/chatdata-agent-design/blob/main/iam-platform/spec.md) 的决策摘要。
> 接受后，ADR-0005（临时密码认证）标记为 superseded。

## 背景

当前身份与权限能力分散在三处：

- **登录**在 `chatdata-query-service/.../auth`：自研 HMAC 令牌，无 scope、无法单独吊销；
- **用户/角色**在 ask 的库表（`chatdata_user` / `sys_role` / `sys_user_role`）；
- **数据权限**在 ask-engine 的 `perm_json`，由 `PermissionGuard` 与 `AskSqlRenderer` 在渲染 SQL 时注入。

ADR-0005 已明确这套登录是临时方案，SSO 落地后应整体删除。现在的要求更进一步：用户、组织、角色、权限、数据权限要成为**独立的可部署模块**，同时服务 Web、开放 API 与微信/飞书/钉钉渠道，网关作为统一入口。

ADR-0002 的结论仍然有效且是硬约束：**行级/列级权限必须在引擎渲染 SQL 时注入**，不能在网关或中间层解析 SQL 后改写。ADR-0004 的角色分类（管理台 / 数据 / 功能）与角色码不可重命名的约束同样保留。

## 决定

1. **新增 `chatdata-identity-service`**：独立部署的认证与权限中心，拥有用户、组织、角色、授权、数据策略、渠道身份绑定，使用自己的库。
2. **网关成为唯一入口**：`chatdata-gateway` 从空壳变为 Spring Cloud Gateway，承担路由、会话/内省、限流、CORS、静态前端与渠道回调入口。
3. **认证与授权采用 OAuth 2.1 + Spring Security**：
   - IAM 用 Spring Authorization Server；
   - 授权码 + PKCE、客户端凭证、刷新轮换、撤销、内省、Token Exchange；
   - 不使用 password / implicit grant。
4. **Access Token 使用不透明引用令牌（`OAuth2TokenFormat.REFERENCE`），不使用 JWT**：
   - `oauth2_authorization`（MySQL）是授权记录的权威来源；
   - Redis 存 BFF 会话、内省缓存、限流计数与吊销广播；
   - 资源服务器通过 RFC 7662 内省校验，失败 fail-closed。
5. **Web 采用 BFF 模式**：浏览器只拿 HttpOnly `SESSION` Cookie；令牌只在网关，由网关注入上游 `Authorization`。
6. **IAM 自身也前后端分离**：新增 `chatdata-identity-web`（Vue 3 + Vite），构建产物放进 IAM 的静态资源目录，与 `chatdata-web → chatdata-query-service` 的做法一致。登录不是服务端模板表单，而是 SPA 调用 JSON 登录接口建立会话，然后浏览器继续 `/oauth2/authorize` 授权码流程。**不使用 Thymeleaf。**
7. **IAM 是权限的事实来源，引擎仍是执行点**：
   - 授权与数据策略集中存储在 IAM；
   - 引擎通过 `iam-client` 获取有效策略，映射成现有 `DomainPermission`；
   - `PermissionGuard` 的判据与 `AskSqlRenderer` 的注入逻辑不变。
8. **短期单租户**：所有 IAM 表带 `tenant_id`（默认 `t_default`），不做租户切换 UI；网关/ask 校验令牌 tenant 与部署配置一致。第二租户另立项目。
9. **飞书 SSO 后置**：公司有飞书通讯录但暂无应用权限。P0–P3 以本地账号为主，预留 `external_id` 与 OIDC 适配；权限到位后做免登 + 通讯录只读同步，角色仍由 IAM 分配。

## 考虑过的其他方案

- **保留自研 HMAC 令牌**：改动最小，但没有 scope、无法单独吊销、第三方接入只能复制密钥。它本来就是临时方案，继续扩展只会把技术债做大。
- **JWT 自包含令牌**：无状态、验签快，但吊销只能等过期，权限变更要等下一次签发；用户明确要求不使用 JWT。且在本项目里"快"不是瓶颈——问数本身是秒级。
- **只把令牌放 Redis，不落库**：实现简单，但授权记录失去权威来源，Redis 故障后无法恢复；也丢掉 Spring Authorization Server 对授权码/同意记录的完整生命周期管理。
- **把数据权限执行挪到 IAM/网关**：违反 ADR-0002，且网关必须理解每个域的物理列，耦合更深、失败形态更隐蔽。
- **纯 SPA + 浏览器持有令牌**：XSS 的后果比 BFF 严重；项目已经在用 HttpOnly Cookie，BFF 是更自然的延续。
- **IAM 登录页用服务端模板（Thymeleaf）**：授权码流程最省事，但与项目其余前端（Vue 3 + Vite）不一致，管理界面还要再写一套服务端模板。改为 SPA + JSON 登录接口，代价是登录接口要显式把 SecurityContext 写进会话并处理 CSRF。

## 后果

- **ADR-0005 被取代**：`ask/auth/**`、`chatdata_user`、`sys_role`、`sys_user_role` 以及本地登录逻辑在 P1 删除；`/sys/**` 契约由 IAM 兼容层承接。
- **Redis 成为关键依赖**：会话与内省缓存都依赖它。需要主从/哨兵与容量规划；缓存 miss 回落内省，**绝不回落成放行**。
- **每个请求多一次内省**（有缓存时通常不跨网络）：IAM 需要独立扩容与限流；内省失败按 503/401 处理。
- **OIDC 例外**：若启用 OIDC（飞书 SSO），`id_token` 按规范仍是签名 JWT；它只用于证明认证，不作为 API 凭据。
- **迁移是渐进的**：`auth.mode=legacy|oauth`、`policy.source=local|iam` 两个开关支持回滚；P2 做"旧配置 vs IAM 策略"双读校验。
  （**2026-10-23 更新**：`policy.source` 已随 ADR-0010 的收尾移除——数据策略回到业务模块，
  只剩 `local` 一条路径；`auth.mode` 的历史部分不改。）
- **SPA 登录需要一个 JSON 登录接口**：`POST /iam/auth/login` 校验密码 + 验证码后，用 `SecurityContextRepository` 把认证写进 Redis 会话，返回 CSRF token 与主体信息；SPA 随后跳转 `/oauth2/authorize`。该接口不签发 access token，只建立会话——令牌仍由授权码流程签发。
- **单租户不是永久约束**：表结构已带 `tenant_id`，加第二租户不需要改 IAM 表；但 ask 多租户仍需独立工程。

## 参考

- 设计全文：`chatdata-agent-design/iam-platform/spec.md`
- ADR-0002：数据权限执行点
- ADR-0004：角色模型
- ADR-0007：直连模式的表级授权

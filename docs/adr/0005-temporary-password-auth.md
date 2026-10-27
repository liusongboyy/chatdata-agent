---
Status: accepted
---

# 临时密码认证：为 vendor 界面实现 `/sys/**` 契约

## 背景

目标架构是「身份从企业统一身份（SSO）获取，角色在本项目维护」。但 SSO 方案**短期无法定案**，
而系统需要一套能上生产的登录方式——此前的 `/dev/login`（读请求头/Cookie 直接认身份）
只存在于 dev profile，绝不能进生产。

第三方 BI 产品 自带界面的登录页调用的是 **vendor-boot 的 `/sys/**` 接口**：

```
GET  /sys/randomImage/{checkKey}       → PNG
POST /sys/login  {username,password,captcha,checkKey}
                                       → {success, result:{token, tenantId}}
GET  /sys/user/getUserInfo             → {success, result:{...}}
GET  /sys/tenant/getCurrentUserTenant  → {success, result:{list:[...]}}
```

已核实：**这些接口既不在 chatbi jar 里，也不在 jimu jar 里**——它们属于宿主框架 vendor-boot。
因此不存在「开启 vendor 登录」这个选项，只能自己实现这套契约。

## 决定

实现 `/sys/**` 契约（`vendorCompatAuthController`），以本地账号密码 + 图形验证码认证，
签发 HMAC 签名的无状态令牌。**同时移除 dev 身份旁路。**

## 关键设计

- **令牌不用 JWT。** 只需要「用户名 + 过期 + 防篡改」三件事，引 JWT 库不划算；
  更关键的是本模块整体是过渡方案，能少一个长期依赖就少一个。
  格式：`base64url(username) . 过期时间戳 . base64url(HMAC-SHA256)`。
- **密钥必填、无默认值。** 共用默认密钥等于任何人都能伪造任意用户的令牌。
  缺失时应用**启动失败**，而不是悄悄用一个弱默认值。
- **用户名不存在时也执行一次 BCrypt 比对。** 否则响应时间差可用来枚举账号。
  两种失败的**提示文案必须完全相同**，有测试断言守护。
- **验证码一次性，且校验失败也销毁。** 否则同一张图可被反复尝试——
  暴力破解的代价会从「每次识别一张图」降到「识别一次、试一万个密码」。
- **验证码校验先于密码校验。** 先做便宜且能挡自动化的检查，再付 BCrypt 的代价（约 100ms）；
  否则攻击者可用无效验证码反复触发 BCrypt，几十并发即可打满 CPU。
- **令牌只放用户名，角色每次请求现查。** 这样角色变更立即生效，不必等令牌过期。

## 移除 dev 旁路

`DevHeaderIdentityResolver`、`DevLoginController`、`DevLoginSecurityConfig` 一并删除。

在真实认证旁边保留一条「读请求头即认身份」的旁路，是这类系统最常见的生产事故来源——
它会一直在那里，直到某次部署忘记关掉 dev profile。既然要做真实登录，旁路就该拆掉而非并存。

## 考虑过的其他方案

- **等 SSO 落地**：不重复造轮子，但在 SSO 定案前系统无法上生产。用户明确 SSO 短期定不了。
- **只做最小登录（无密码无验证码）**：与已被删除的 `/dev/login` 实质相同，安全性没有提升，
  却多一套要拆的代码。

## 后果

- **这是临时方案，SSO 落地后应整体删除。** 届时只需替换 `IdentityResolver` 的实现，
  其余代码不受影响——这是把它隔离在 `ask/auth` 包内、并让它只依赖 `IdentityResolver`
  接口的原因。删除范围：`ask/auth/**`、`sql/06-user.sql`、`chatdata_user` 表、
  `chatdata.auth.*` 配置、`AskSecurityConfig` 中的 `/sys/**` 放行。
- **无状态令牌无法单独吊销。** 签发后在有效期内始终有效，只能换密钥让全部令牌失效。
  这是有意的取舍：真正需要吊销能力时应由 SSO 的会话管理承担，而不是在这里加会话表。
- **验证码存在内存 Map，多实例部署下会话不共享。** 当前单实例无影响；
  若将来多实例，必须换成共享存储，否则用户会在负载均衡下随机遇到「验证码错误」。
- **手机号登录、扫码登录、注册、短信未实现。** 界面有这些入口，但无后端时只会报错，
  不构成安全问题；实现它们等于凭空增加攻击面。
- **`/sys/**` 在 Spring Security 层面整体放行**，鉴权在端点自身：登录校验密码与验证码，
  其余两个在无有效令牌时返回未登录。放行是必需的——登录接口本身不能要求先登录。

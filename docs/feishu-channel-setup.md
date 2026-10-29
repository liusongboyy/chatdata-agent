# 飞书渠道接入：操作指南

> 代码已经写好（`.scratch/feishu-channel/spec.md`），这份文档是**让它在真实飞书里跑起来**的步骤。
> 全部做完大约 40 分钟，其中 20 分钟是等飞书审核权限。

## 0. 你会得到什么

飞书里 @ 一个机器人问「上周华东的订单金额是多少」，它直接回一张带表格与结论的卡片。

数据权限与他本人在问数页面看到的**完全相同**——渠道不额外放权，也不额外收缩。

---

## 1. 前置条件

| 项 | 要求 | 怎么确认 |
|---|---|---|
| MySQL / Redis | 在跑 | `nc -z 127.0.0.1 3306 && nc -z 127.0.0.1 6379` |
| IAM / ask / channel | 三个服务能起来 | 见 §5 |
| 内网穿透 | **必须有**，飞书要回调用公网地址 | 见 §2 |
| 飞书开发者权限 | 能创建企业自建应用 | 飞书管理后台 → 开发者后台 |

> **为什么必须要内网穿透**：本期选的是 webhook 模式，飞书要从公网 POST 事件过来。
> 长连接模式不需要公网地址，但那要改传输层——见 spec §3 的取舍说明。

---

## 2. 起内网穿透

飞书保存回调地址时会**立刻**发一个 challenge 校验请求，穿透没就绪就保存失败。
所以顺序不能反：**先起穿透，再配后台**。

```bash
# 二选一。免费版域名每次重启都变，每次都要回飞书后台改地址
cpolar http 20000
# 或
ngrok http 20000
```

拿到形如 `https://xxxx.cpolar.io` 的地址，记下来。

> **建议用固定域名**（cpolar 付费版 / 自建 frp）。否则每次重启开发机都要
> 回飞书后台改一次回调地址，而忘了改的表现是「消息发出去没反应」。

---

## 3. 在飞书开发者后台创建应用

### 3.1 创建应用

<https://open.feishu.cn/app> → 创建企业自建应用 → 填名称与描述。

进入应用后记下 **凭证与基础信息** 页的：

- `App ID`（形如 `cli_a1b2c3d4e5f6`）
- `App Secret`

### 3.2 开启机器人能力

**添加应用能力** → 机器人 → 启用。

没开这一步，应用收不到 `im.message.receive_v1` 事件。

### 3.3 申请权限

**权限管理** → 搜索并开通下面这些（**自建应用**，都要「应用身份」不是「用户身份」）：

| 权限码 | 用途 | 必需 |
|---|---|---|
| `im:message.p2p_msg:readonly` | 接收用户发给机器人的单聊消息 | ✅ |
| `im:message.group_at_msg:readonly` | 接收群里 @ 机器人的消息 | ✅ 要用群聊时 |
| `im:message:send_as_bot` | 以机器人身份发消息（我们的回复） | ✅ |
| `im:chat:readonly` | 读群信息（拿群名做日志） | 可选 |

> 只做私聊的话，前两条里第一条就够。群聊要加第二条。
>
> **不要**申请 `im:message.group_msg`（读群里**所有**消息）——
> 那会把群里的每一句闲聊都推给我们，既没必要也在扩大数据面。

### 3.4 配置加密策略（**这一步不能跳**）

**事件与回调 → 加密策略**：

1. 设置 `Encrypt Key`（自己生成，32 位以上随机串）
2. 记下 `Verification Token`

> **为什么不配不行**：不配 Encrypt Key 时飞书**明文**推送事件，
> 而 body 里有用户提问原文与 open_id。代码在启动时会检查这一项，
> 没配会直接起不来（不是警告，是拒绝启动）。

生成一个：

```bash
openssl rand -hex 24
```

### 3.5 配置事件订阅

**事件与回调 → 事件配置**：

1. 订阅方式选 **「将事件发送至开发者服务器」**
2. 请求地址填：`https://<你的穿透域名>/channel/feishu/event`
3. 点保存——飞书会立刻发 challenge 校验

**如果保存失败**，按顺序查：

| 现象 | 原因 |
|---|---|
| 「Challenge code 没有返回」 | channel 没起，或穿透没通到 20000 |
| 「请填写有效的 HTTP/HTTPS URL」 | 地址不是公网可达（穿透断了） |
| 保存时 channel 日志有 `验签失败` | 后台填的 Encrypt Key 与服务里的不一致 |

4. 保存成功后，**添加事件** → 搜索「接收消息 v2.0」（`im.message.receive_v1`）→ 添加

### 3.5b 订阅卡片回调（翻页与反馈要用）

同一个页面的**「回调」**标签（不是「事件」）：

1. 点「添加回调」
2. 搜索 **`card.action.trigger`（卡片回传交互）** → 添加

> 配置在同一个请求地址上，不需要再加一条 URL。
>
> **不配的话**：结果卡片上的「上一页/下一页」「有用/答错了」点了没反应。
> 卡片本身能正常显示——因为那是发送，不是回调。

### 3.6 发布应用

**版本管理与发布** → 创建版本 → 申请发布。

企业自建应用的权限通常需要管理员审批，**这一步要等**。
发布前机器人只在「测试企业与人员」里可见。

---

## 4. 配置本机

把飞书给的值填进 `.env`：

```bash
# 飞书渠道
FEISHU_ENABLED=true
FEISHU_APP_ID=cli_xxxxxxxxxxxx
FEISHU_APP_SECRET=xxxxxxxxxxxxxxxx
FEISHU_ENCRYPT_KEY=<3.4 里设的那个>

# 用户在飞书点「立即绑定」时打开的地址。
# 这是**浏览器**访问的地址，不是穿透地址——穿透地址是给飞书回调用的
BIND_BASE_URL=http://127.0.0.1:8080

CHANNEL_PORT=20000
```

`IAM_CHANNEL_SECRET` 第 1 步已经生成过，不用动。

> 两个地址别搞混：
>
> - **穿透地址**（`https://xxx.cpolar.io`）→ 给飞书回调用，只有飞书访问
> - **`BIND_BASE_URL`**（`http://127.0.0.1:8080`）→ 给用户浏览器用，走网关
>
> 填反了的表现：绑定链接点开是 404，或者飞书保存回调地址失败。

---

## 5. 起服务

顺序有依赖：IAM 要先起（channel 要向它换令牌），ask 要在 channel 之前就绪。

```bash
cd /Users/liusongbo/selfProjects/chatdata-agent
set -a; . ./.env; set +a
export JAVA_HOME=/Users/liusongbo/Library/Java/JavaVirtualMachines/azul-21.0.11/Contents/Home

# 首次或改过代码后
mvn -q -DskipTests install

nohup java -jar chatdata-identity-service/target/chatdata-identity-service-1.0.0-SNAPSHOT.jar > /tmp/iam.log 2>&1 &
nohup java -jar chatdata-query-service/target/chatdata-query-service-1.0.0-SNAPSHOT.jar    > /tmp/ask.log 2>&1 &
nohup java -jar chatdata-gateway/target/chatdata-gateway-1.0.0-SNAPSHOT.jar               > /tmp/gw.log  2>&1 &
nohup java -jar chatdata-channel/target/chatdata-channel-1.0.0-SNAPSHOT.jar               > /tmp/ch.log  2>&1 &
```

**channel 建库**（首次）：

```bash
mysql -uroot -p chatdata_ask < sql/21-channel.sql
```

健康检查：

```bash
for p in 9000 8081 8080 20000; do
  curl -s -o /dev/null -w "$p: %{http_code}\n" http://127.0.0.1:$p/actuator/health
done
```

四个都应是 200。

启动日志里应有：

```
飞书渠道已启用：appId=cli_xxx groupReplyMode=in-group
渠道工作线程已启动：8 个，队列容量 200
```

---

## 6. 验证

### 6.1 先自测回调（不经飞书）

```bash
BODY='{"type":"url_verification","challenge":"abc123","token":"t"}'
TS=$(date +%s); NONCE=n$TS
KEY='<你的 FEISHU_ENCRYPT_KEY>'
SIG=$(python3 -c "
import hashlib,sys
print(hashlib.sha256(('$TS$NONCE'+'$KEY'+sys.argv[1]).encode()).hexdigest())" "$BODY")

curl -s -X POST http://127.0.0.1:20000/channel/feishu/event \
  -H 'Content-Type: application/json' \
  -H "X-Lark-Request-Timestamp: $TS" \
  -H "X-Lark-Request-Nonce: $NONCE" \
  -H "X-Lark-Signature: $SIG" \
  -d "$BODY"
```

期望：`{"challenge":"abc123"}`

改成别的 body 用同一个签名 → 应得 401。这说明验签真的在用原始字节。

### 6.2 端到端

1. 飞书里找到你这个机器人（开发者后台 → 我的应用 → 机器人）
2. 私聊发一句「你好」
3. 首次会收到 **「你还没有绑定问数账号」** + [立即绑定]
4. 点链接 → 用 `admin/admin123` 登录 → 确认绑定
5. 回飞书再发一句 → 应见到「正在查询…」然后结果卡片

---

## 6.3 可用的命令

在飞书里发这些消息控制机器人。**命令都是前缀形式**，不会被误判成提问。

| 命令 | 作用 |
|---|---|
| `#助手` | 列出你有权限的助理。多个助理时必须先选一个 |
| `#助手 6` | 切到第 6 个助理（换助理会开新会话） |
| `#分析 <问题>` | 走多步分析：拆成几次查询 + 确定性结论，如 `#分析 为什么华东销量下降` |
| `你可以问哪些数据` | 返回能力清单（示例问题 + 可问字段），不查数据库 |
| `新话题` / `重置` | 开新会话，丢掉上下文 |

**结果卡片上的按钮：**

| 按钮 | 作用 |
|---|---|
| 上一页 / 下一页 | 结果超过 10 行时出现，原地翻页 |
| 有用 / 答错了 | 提交反馈，进管理台的审核队列 |

> 反馈**只有提问者本人**能点。群里其他人点了会提示「只有提问者本人可以操作」——
> 反馈要进审核、沉淀成 SQL 范例，被污染比没有更糟。

## 7. 排障

按**现象**查，比按代码查快。

| 现象 | 可能原因 | 怎么确认 |
|---|---|---|
| 发消息完全没反应 | 应用没发布 / 机器人没启用 / 事件没订阅 | 开发者后台 → 日志检索 → 事件日志，看有没有推 |
| 后台有推送但 channel 没日志 | 回调地址写错、穿透断了 | `tail -f /tmp/ch.log`，同时看穿透的请求日志 |
| channel 日志「验签失败」 | Encrypt Key 两边不一致 | 后台的值 vs `.env` 里的 `FEISHU_ENCRYPT_KEY` |
| 回「无法确认你的身份」 | 用户没绑 open_id，或绑的是别的渠道 | 管理台「用户」页看「渠道」列 |
| 回「账号已停用」 | `iam_user.status != ACTIVE` | 管理台看状态 |
| 回「问数服务暂不可用」 | ask 没起 / 换令牌失败 | `curl 8081/actuator/health`；看 IAM 日志 |
| 绑定链接点开 404 | `BIND_BASE_URL` 填错，或网关没起 | 直接访问 `http://127.0.0.1:8080/iam/app/bind` |
| 绑定链接点开提示「已失效」 | 超过 10 分钟，或已被用过 | 回飞书重发一条消息拿新链接 |
| 同一条消息被答复两次 | 去重没生效（`channel_inbound_dedup` 被清过） | 查那张表有没有对应 `message_id` |
| 结果卡片表格错位 | 列数 >5 被截断 | 点「查看完整结果」，或调 `MAX_COLUMNS` |

### 关键日志

```bash
# 事件是否进来
grep "渠道消息" /tmp/ch.log

# 换令牌是否成功
grep "换取渠道令牌成功\|换令牌被拒" /tmp/ch.log

# 入站被拒（验签/解密）
grep "验签失败\|解密失败" /tmp/ch.log

# 队列是否打满
grep "队列已满" /tmp/ch.log
```

---

## 8. 上线前必做

本地跑通不代表能上线。这几条**在生产部署前**必须确认：

| 项 | 为什么 |
|---|---|
| 只用 HTTPS 暴露 `/channel/feishu/event` | 明文回调会让 open_id 与提问原文暴露在链路上 |
| channel 的**其余端口不暴露公网** | 绑定确认接口没有会话保护时会被滥用——它必须经网关 |
| `BIND_BASE_URL` 改成正式域名 | 填 `127.0.0.1` 时用户点开是白屏 |
| 固定穿透域名 / 直接用公网 IP | 免费穿透域名会变，变了消息就收不到 |
| 确认群聊的使用范围 | **群里发言即公开**：张三问的数据全群可见。跨权限的群应把机器人移出，而不是靠代码兜底（spec §6.6） |
| 配告警：`队列已满`、`验签失败` 突增 | 前者意味着容量不够，后者意味着有人在探测 |

---

## 附：本期没做的东西

这些在 spec 里有设计但**代码还没实现**，别以为能用：

| 项 | 状态 |
|---|---|
| 占位卡片原地更新 | 现在是**再发一条**（见 `MessageHandler.sendCardUpdate`）。用户会看到「正在查询…」和结果两条消息 |
| 「我的账号」解绑页 | 后端接口有（`/iam/channel/bind/unbind`），前端没做页面 |
| 定时推送（日报/预警） | 未开始。IM 相对 Web 最不可替代的能力，但需要订阅配置界面 |
| 钉钉 / 企业微信 | 未开始。抽象留给第二个实现进来时再做——三个都没有时抽出的接口是「想象中的共性」 |

## 附：已知的坑

这些是真机联调踩到的，接新渠道时大概率会再遇到：

| 现象 | 根因 |
|---|---|
| 飞书后台保存回调地址报「Challenge code 没有返回」 | ① `url_verification` 请求**不带签名头**，验签不能排在它前面；② 配了 Encrypt Key 后连 challenge 探测都是加密的（`{"encrypt":"..."}`），**必须先解密再判类型** |
| 卡片发送 400 `240...` / `230099` | 用了 schema 2.0 已废弃的组件（如 `note`）或 1.0 的结构。错误信息里的 `ErrPath` 会精确到第几个元素 |
| 「什么都回答不了」 | 直连模式返回的是**文字答复**（`answer` 字段），不是表格。只读 `columns`/`rows` 会把它显示成「没有符合条件的数据」 |
| `#助手 4` 没生效 | 解析时只去掉 `#` 没去掉后面的空格，`split` 后第一段是空串 |
| 用户问「你能问什么」却回了一堆数据 | 那是能力问题不是数据问题，要拦下来走 `/api/ask/capabilities` |

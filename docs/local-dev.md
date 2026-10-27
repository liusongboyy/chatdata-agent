# 本地启动

三个服务 + 四个库。下面是可以照抄执行的顺序。

## 前置依赖

| 依赖 | 说明 |
|---|---|
| JDK 21 | **必须显式指定**，见下方「Java 版本」 |
| MySQL 8 | 四个库，见「数据库」 |
| Redis | 会话、验证码、内省缓存 |

### Java 版本

本机 shell 默认 `JAVA_HOME` 可能指向 Java 8，直接 `mvn` 会报
`UnsupportedClassVersionError: ... class file version 61.0`。

```bash
export JAVA_HOME=/Users/liusongbo/Library/Java/JavaVirtualMachines/azul-21.0.11/Contents/Home
```

另外 `mvn ... | tail` 的退出码是 `tail` 的，不要用它判断构建成功与否。

## 数据库

四个库按职责分开（详见 [`sql/README.md`](../sql/README.md)）：

| 库 | 内容 |
|---|---|
| `chatdata_ask` | 服务自身的表：语义域、注册表、助理、术语、主题、审计、会话、反馈 |
| `chatdata_iam` | 身份：用户、组织、角色、功能权限、OAuth 授权记录 |
| `chatdata_demo` | 演示业务数据 `ins_policy` / `ins_customer` / `ins_claim` |
| `chatdata_eval` | 评测夹具，由 `eval/fixture/prepare.sh` 重建 |

**为什么配置与业务数据分库**：业务库通常是别人维护的只读库，不该混进我们的配置表。
问数时 ask 从 `chatdata_ask` 读配置，再去数据源指向的库读数据。

> 历史：2026-10-24 之前这些表都建在 `vendor` 库里（借用了 第三方报表工具 的库），
> 与 57 张 第三方报表工具 遗留表混在一起。已拆分迁出，旧库连同遗留表一并删除。
> 备份在 `~/chatdata-backups/vendor-full-20261024-092503.sql`。

### 建库与建表

```bash
mysql -uroot -p -e "
CREATE DATABASE chatdata_ask  DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE chatdata_iam  DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE chatdata_demo DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
"

# ask 的表（SQL 里不写库名，靠命令行参数指定）
for f in sql/04-audit.sql sql/08-ask-engine.sql sql/10-ask-term-topic.sql \
         sql/11-ask-assistant.sql sql/12-ask-agent-session.sql \
         sql/14-ask-sql-example.sql sql/15-ask-sql-example-unique.sql \
         sql/16-ask-feedback.sql sql/17-ask-conversation.sql \
         sql/19-ask-audit-analysis.sql sql/20-ask-table-base-filter.sql
do
  mysql -uroot -p chatdata_ask < "$f"
done

# 种子数据（本地开发用，会建出保险分析域与演示数据源）
mysql -uroot -p chatdata_ask < sql/09-ask-engine-seed.sql

# IAM（顺序有讲究，见 .scratch/iam-platform/spec.md §18）
mysql -uroot -p chatdata_iam < sql/iam/01-schema.sql
mysql -uroot -p chatdata_iam < sql/iam/07-role-permission.sql
mysql -uroot -p chatdata_iam < sql/iam/08-bootstrap.sql
```

### 演示业务数据

`chatdata_demo` 的三张表从旧库备份里恢复。备份是整库的（75 张表），
只捞这三张即可（已实测：恢复后 300 / 80 / 128 行，与迁移前一致）：

```bash
BK=~/chatdata-backups/vendor-full-20261024-092503.sql

for t in ins_policy ins_customer ins_claim; do
  awk -v tbl="$t" '
    $0 ~ "^CREATE TABLE `" tbl "`" {copy=1}
    copy {print}
    copy && /^\) ENGINE/ {copy=0}
    $0 ~ "^INSERT INTO `" tbl "`" {print}
  ' "$BK" | mysql -uroot -p chatdata_demo
done
```

注意用 `awk` 而不是 `grep`：后者在某些环境（如装了命令代理时）输出格式会被改写，
把摘要行混进 SQL 里，导入时会在建表语句中途报 `ERROR 1064`。

### 数据源密码

`ask_datasource.password_enc` 用 `CHATDATA_ENGINE_DS_SECRET` 做 AES-GCM 加密。
换过密钥后旧密文解不开，症状是"解密数据源密码失败"或
`Connection is not available, total=0`。

重新加密（只针对评测夹具库的脚本；业务数据源在管理台里改密码即可）：

```bash
set -a; . ~/.chatdata-agent.env; set +a
./eval/fixture/set-datasource-password.py
```

## 环境变量

全部放在 `~/.chatdata-agent.env`：

| 变量 | 用途 |
|---|---|
| `ARK_API_KEY` | 火山方舟 |
| `LLM_API_KEY` | 问数模型密钥（本地复用 `ARK_API_KEY`） |
| `CHATDATA_ENGINE_DS_SECRET` | 数据源密码的加解密密钥，**必填** |
| `IAM_BFF_SECRET` | 网关 BFF 客户端密钥 |
| `IAM_RESOURCE_SECRET` | 服务间读取角色/用户目录的密钥 |

## 启动

端口分工（2026-10-21 起 8080 归网关）：

| 服务 | 端口 | 说明 |
|---|---|---|
| gateway | 8080 | 唯一入口 |
| ask | 8081 | 只被网关代理，不直接对外 |
| IAM | 9000 | 账号中心 |

```bash
cd /Users/liusongbo/selfProjects/chatdata-agent
set -a; . ~/.chatdata-agent.env; set +a
export JAVA_HOME=/Users/liusongbo/Library/Java/JavaVirtualMachines/azul-21.0.11/Contents/Home

mvn -q -DskipTests install     # 前端产物已打进 ask/iam 的 static，改前端要先 npm run build

nohup java -jar chatdata-agent-iam/target/chatdata-agent-iam-1.0.0-SNAPSHOT.jar     > /tmp/iam.log 2>&1 &
nohup java -jar chatdata-agent-ask/target/chatdata-agent-ask-1.0.0-SNAPSHOT.jar     > /tmp/ask.log 2>&1 &
nohup java -jar chatdata-agent-gateway/target/chatdata-agent-gateway-1.0.0-SNAPSHOT.jar > /tmp/gw.log 2>&1 &
```

顺序无所谓，但 IAM 要能连上 Redis，ask 要能连上 MySQL。

### 前端

两个前端都是独立 npm 工程（不是 Maven module），构建产物输出到后端 `static/`：

```bash
(cd chatdata-agent-web     && npm run build)   # → chatdata-agent-ask/src/main/resources/static/app
(cd chatdata-agent-iam-web && npm run build)   # → chatdata-agent-iam/src/main/resources/static/iam/app
```

**必须先 build 再 `mvn install`**，否则打进 jar 的是旧产物。

## 验证

```bash
for p in 9000 8081 8080; do curl -s -o /dev/null -w "$p: %{http_code}\n" http://127.0.0.1:$p/actuator/health; done
```

三个都应是 200。

| 入口 | 地址 |
|---|---|
| 问数 | http://127.0.0.1:8080/app/ |
| 账号中心 | http://127.0.0.1:8080/iam/app/ |

本地账号 `admin / admin123`（登录页有图形验证码）。

**登录只有一处**：浏览器访问 `/bff/login` 走授权码 + PKCE 到 IAM。
ask 没有本地登录，`/api/**` 由资源服务器内省 IAM 令牌。

启动时应看到 `引擎配置校验通过`。如果报违规，按提示改库里的配置
（`ask_domain` / `ask_domain_table` / `ask_assistant`），不需要改代码。

## 改完代码为什么没生效

**这是最容易浪费时间的一类问题**：代码改了、测试也过了，跑起来的服务用的还是旧逻辑。

`java -jar` 跑的是 `target/*-SNAPSHOT.jar` 里的东西。Maven 增量构建在
"源码时间戳没变"或"只是下游模块变了"时**可能跳过 repackage**，于是 fat jar 里
嵌的还是上一版引擎模块。实测踩到过：改了 `ask-engine` 的解析器，
`mvn install` 报成功，但 `chatdata-agent-ask/target/*.jar` 的时间戳没变，
运行中的服务行为与修复前完全一致——而评测（走 `spring-boot:run`，用 target/classes）
却是通过的，两边对不上，很容易误判成"配置问题"。

**改完后确认三件事：**

```bash
# ① 引擎模块的 jar 时间戳
ls -l chatdata-agent-ask-engine/target/*.jar

# ② ask 的 fat jar 时间戳（必须是刚刚）
ls -l chatdata-agent-ask/target/chatdata-agent-ask-*.jar

# ③ fat jar 里嵌的引擎是不是新版
unzip -p chatdata-agent-ask/target/chatdata-agent-ask-*.jar \
  BOOT-INF/lib/chatdata-agent-ask-engine-1.0.0-SNAPSHOT.jar > /tmp/eng.jar
unzip -p /tmp/eng.jar com/liusongboyy/chatdata/engine/agent/DslParser.class > /tmp/p.class
python3 -c "print(b'granularity' in open('/tmp/p.class','rb').read())"
```

第 ③ 步别用 `strings | grep`：macOS 上 `strings` 读管道时行为不稳定，
实际会返回空结果，看起来像"没匹配到"。直接按字节查关键词更可靠。

改完引擎或宿主代码后，用**强制重新打包**而不是 `install`：

```bash
rm -f chatdata-agent-ask/target/chatdata-agent-ask-*.jar
mvn -q -pl chatdata-agent-ask -am -DskipTests package
```

`-am`（also make）会把依赖的引擎模块一起构建；`rm` 是为了绕开增量判断。
打包后**必须重启进程**——`java -jar` 不会热加载。

### 评测与线上为什么可能不一致

| | 用什么 |
|---|---|
| `./eval/run.sh` | `mvn spring-boot:run` + `target/classes`（总是最新） |
| 线上 / 本地 `java -jar` | fat jar（可能过期） |

所以"评测通过但页面不对"时，先查 jar 时间戳，不要先怀疑配置。


## 常见问题

| 症状 | 原因 |
|---|---|
| `UnsupportedClassVersionError ... 61.0` | `JAVA_HOME` 指向 Java 8 |
| `Connection is not available, total=0` | 数据源 URL 指错库，或密码解不开 |
| 问数报「找不到可用的日期列」 | 域内没有别名为 `生效日期`/`统计日期` 等的日期列 |
| 问数报「匹配到多处」 | 两张表用了同一个业务别名，改其中一个 |
| `/actuator/health` 500 或 401 | 缺 actuator 依赖，或资源服务器没放行该路径 |
| 管理台提示"权限由 IAM 提供" | 已废弃：数据策略归 ask，见 ADR-0010 |
| **改完代码行为没变，但测试/评测通过** | fat jar 没重新打包，见上文「改完代码为什么没生效」 |
| 服务起不来，日志报「N 项引擎配置违规」 | 库里有未配完的域（启用但没绑数据源/没注册表）。改该域为 `disabled`，或补齐配置 |

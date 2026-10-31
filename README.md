/# chat-data-agent

**一个不让模型写 SQL 的问数系统。**

业务人员用中文提问，得到表格、图表与结论。模型只产出**结构化的查询意图（DSL）**——
查什么指标、按什么维度、什么时间范围——SQL 由确定性渲染器生成。结果是：
答案可校验、权限可控、同一份配置永远生成同样的 SQL。

[![Java](https://img.shields.io/badge/Java-21-blue)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-green)](https://spring.io/projects/spring-boot)
[![agentscope-java](https://img.shields.io/badge/agentscope--java-2.0.4-orange)](https://github.com/agentscope-ai/agentscope-java)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

---

## 这个项目解决什么问题

企业里的业务人员想问数据，但只有分析师会写 SQL。市面上多数 NL2SQL 产品让模型
**直接写 SQL**——快，但错了不报错，只是数字不对，而用户没法察觉。

这个项目的做法不同：**模型只产出结构化的查询意图（DSL），SQL 由我们渲染。**

```
中文问题 → 问数 DSL（指标/维度/时间/筛选）→ 我们渲染 SQL → 注入权限 → 执行
                    ↑                                        ↓
              可校验、可审计                          行级规则、列脱敏
```

带来三个直接后果：

| | 让模型写 SQL | 本项目 |
|---|---|---|
| 模型能不能算错 | 能，且静默 | 不能——它只说"查什么"，不说"怎么查" |
| 权限能不能保证 | 难以保证（SQL 是模型自由生成的） | 能——渲染器固定注入行级/列级规则 |
| 同一个问题两次结果 | 可能不同 | 同一份配置生成逐字节相同的 SQL |

## 功能

- **语义建模**：把物理表抽象成业务可理解的指标与维度，口径在建模层定死
- **问数**：一句话得到表格 / 图表 / 结论；「我理解成了」回显系统实际理解的口径
- **分析编排**：把"为什么下降"这类问题拆成多步取数，再由 Java 算出确定性结论
- **权限**：行级规则（支持组织层级动态变量）、列级脱敏、fail-closed
- **审计**：每次提问、每步查询、每句 SQL 都可追溯
- **IM 渠道**：接入飞书，群里 @ 机器人即可问数；权限与 Web 端完全一致
- **评测基线**：199 条用例，分数据集报告通过率与失败原因

## 快速开始

需要 **Java 21**、**Maven**、**MySQL 8**、**Redis**。

```bash
# 1) 起依赖（默认非标准端口：MySQL 13306 / Redis 16379，
#    避免与本机已装的 3306/6379 撞车）
docker compose up -d

# 2) 配置
cp .env.example .env          # 填入数据库密码与 LLM API Key
set -a; . ./.env; set +a

# 3) 建库
mysql -uroot -p -e "
CREATE DATABASE chatdata_ask  DEFAULT CHARSET utf8mb4;
CREATE DATABASE chatdata_iam  DEFAULT CHARSET utf8mb4;
CREATE DATABASE chatdata_demo DEFAULT CHARSET utf8mb4;"
for f in sql/04-audit.sql sql/08-ask-engine.sql sql/10-ask-term-topic.sql \
         sql/11-ask-assistant.sql sql/12-ask-agent-session.sql \
         sql/14-ask-sql-example.sql \
         sql/16-ask-feedback.sql sql/17-ask-conversation.sql \
         sql/19-ask-audit-analysis.sql sql/20-ask-table-base-filter.sql \
         sql/21-channel.sql
do mysql -uroot -p chatdata_ask < "$f"; done
mysql -uroot -p chatdata_ask < sql/09-ask-engine-seed.sql   # 演示数据（可选）

# 用 docker 起的库，种子里的数据源地址要改端口（库里存的是绝对地址）：
#   UPDATE ask_datasource SET url = REPLACE(url, ':3306/', ':13306/') WHERE id = 1;
mysql -uroot -p chatdata_iam < sql/iam/01-schema.sql
mysql -uroot -p chatdata_iam < sql/iam/07-role-permission.sql
mysql -uroot -p chatdata_iam < sql/iam/02-seed-dev.sql   # 建 admin / admin123
mysql -uroot -p chatdata_iam < sql/iam/08-bootstrap.sql  # 会打印角色与超管核对报告

# 4) 构建并启动
mvn -DskipTests package
java -jar chatdata-identity-service/target/chatdata-identity-service-*-SNAPSHOT.jar     &
java -jar chatdata-query-service/target/chatdata-query-service-*-SNAPSHOT.jar     &
java -jar chatdata-gateway/target/chatdata-gateway-*-SNAPSHOT.jar &

# 5) 可选：飞书渠道（需要飞书应用与公网回调地址，见文档）
# java -jar chatdata-channel/target/chatdata-channel-*-SNAPSHOT.jar &
```

打开 <http://127.0.0.1:8080/app/>，默认账号 `admin` / `admin123`。

> `08-bootstrap.sql` 最后会打印一份核对报告。**确认「超级管理员持有者数」>= 1**——
> 为 0 的话没人能进管理台。生产部署不要用 `02-seed-dev.sql` 里的固定密码。

**详细步骤、常见问题见 [docs/local-dev.md](docs/local-dev.md)。**

## 架构

```
  浏览器 ──→ gateway (8080) ──┐         唯一入口：路由、BFF 会话、令牌中继
                             │
                             ├──→ ask (8081) ──→ ask-engine
                             │        │            语义模型 → DSL → SQL 渲染
  飞书 ────→ channel (20000) ┘        │              → 权限注入 → 执行 → 审计
   (webhook)      │                   │ 内省令牌
                  └──换代表用户的令牌──┴──→ iam (9000)
                                            身份 / 角色 / 功能权限
```

浏览器与飞书是**两个入口、两条鉴权路径**：浏览器走会话 Cookie + 网关 BFF；
飞书走事件验签 + 换一张"代表该用户"的令牌。两条最后都汇到 ask，由它统一判权限。

**渠道不判断权限。** 它只做「哪个用户问了什么 → 拿他的令牌去问 → 把结果排版回去」。
行级规则、列脱敏、角色判定全在 ask 里——渠道看不到也不该看到。
这是刻意的：一旦渠道开始判断"这个人能不能查"，就出现了第二个权限判定点。

| 模块 | 说明 |
|---|---|
| `chatdata-gateway` | 唯一入口：路由、BFF 会话、令牌中继 |
| `chatdata-query-service` | 问数服务：SSE、管理台 API、会话历史 |
| `chatdata-query-engine` | 问数引擎：语义模型、DSL、SQL 渲染、权限执行 |
| `chatdata-identity-service` | 身份：用户、组织、角色、功能权限、OAuth 2.1 |
| `chatdata-identity-client` | 服务间读取身份与目录的客户端 |
| `chatdata-channel` | IM 渠道：飞书 webhook 接入、卡片渲染、翻页与反馈 |
| `chatdata-web` | 问数前端（Vue 3） |
| `chatdata-identity-web` | 账号中心前端（Vue 3） |

## 文档

| 文档 | 内容 |
|---|---|
| [GLOSSARY.md](GLOSSARY.md) | 术语表（问数、语义建模、行级权限、直连模式…） |
| [docs/local-dev.md](docs/local-dev.md) | 本地启动：依赖、建库、环境变量、常见问题 |
| [docs/modeling-coverage.md](docs/modeling-coverage.md) | **建模覆盖度**：准确率最大的杠杆、新建域检查清单 |
| [docs/feishu-channel-setup.md](docs/feishu-channel-setup.md) | **飞书渠道接入**：建应用、配权限与回调、排障 |
| [docs/agentscope-local-build.md](docs/agentscope-local-build.md) | **本地构建 agentscope 管理台**：patch 用法、端口、排障 |
| [docs/adr/](docs/adr/) | 架构决策记录——每个决定的**为什么** |
| [sql/README.md](sql/README.md) | 库的划分与建表顺序 |
| [eval/README.md](eval/README.md) | 评测基线：夹具、用例、指标口径、能力缺口 |
| [demo/adventureworks/](demo/adventureworks/) | 复杂业务域：28 表 / 172 列 / 30 关联的销售分析场景 |
| [.scratch/](.scratch/) | 设计过程：每轮的 spec、取舍、被否决的方案 |
| [AGENTS.md](AGENTS.md) | 给 AI agent 的工作约定 |

## 开发

```bash
mvn test                                    # 844 个测试
npm --prefix chatdata-web run build   # 前端（产物要一起提交，见下）
```

**前端产物必须提交。** Maven 不构建前端（没有 frontend-maven-plugin），
`src/main/resources/static/**` 里的文件是 `npm run build` 生成的。
删掉它们，clone 下来就是白屏。改了前端要重新 build 再提交。

## 设计取向

这个项目里有几条反复出现的原则，看代码时会遇到：

1. **宁可拒绝，不要猜。** 字段有歧义、时间口径不明、权限规则求值失败——都直接拒绝，
   而不是选一个"看起来合理"的。猜错的表现是数字不对，而用户无从察觉。
2. **失败方向必须安全。** 没配授权 = 拒绝访问（不是放开）；认不出的聚合方式 = 报错（不是当维度）；
   敏感列默认关闭（不是默认开启）。
3. **不静默。** 降级要说明、截断要提示、理解错了要回显。「我理解成了」那一块就是为此存在的。
4. **可复现。** 同一份配置生成逐字节相同的 SQL；评测夹具改一次就重跑全量基线。

## 许可

[Apache License 2.0](LICENSE)

## chat-data-agent 智能问数 agent 系统

面向业务人员的自然语言数据问答系统。用户用中文提问，系统返回表格、图表与文字结论，
不要求用户理解或编写 SQL。

## 文档入口

| 文档 | 内容 |
|---|---|
| [GLOSSARY.md](GLOSSARY.md) | 术语表（问数、语义建模、行级权限、直连模式…） |
| [docs/local-dev.md](docs/local-dev.md) | **本地启动**：依赖、建库、环境变量、运行、常见问题 |
| [sql/README.md](sql/README.md) | 库的划分、建表顺序、已退役脚本 |
| [eval/README.md](eval/README.md) | 评测基线：夹具、用例、指标口径、能力缺口 |
| [docs/adr/](docs/adr/) | 架构决策记录 |
| [AGENTS.md](AGENTS.md) | 给 agent 的工作约定（issue、triage、领域文档） |

## 模块

| 模块 | 说明 |
|---|---|
| `chatdata-agent-gateway` | 唯一入口：路由、BFF 会话、令牌中继 |
| `chatdata-agent-ask` | 问数服务：SSE、管理台 API、会话历史 |
| `chatdata-agent-ask-engine` | 问数引擎：语义模型、DSL、SQL 渲染、权限执行 |
| `chatdata-agent-iam` | 身份：用户、组织、角色、功能权限、OAuth 2.1 |
| `chatdata-agent-iam-client` | 服务间读取身份与目录的客户端 |
| `chatdata-agent-web` | 问数前端（Vue 3，独立 npm 工程） |
| `chatdata-agent-iam-web` | 账号中心前端（Vue 3，独立 npm 工程） |

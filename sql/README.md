# 数据库与建库脚本

## 库的划分

整套系统用**四个库**，按职责分开：

| 库 | 内容 | 谁写 |
|---|---|---|
| `chatdata_ask` | 服务自身的表：语义域、注册表、助理、术语、主题、审计、会话、反馈、策略审计，以及渠道的去重/会话/答案表 | ask、channel |
| `chatdata_iam` | 身份：用户、组织、角色、功能权限、OAuth 授权记录 | IAM 服务 |
| `chatdata_demo` | **演示业务数据**：`ins_policy` / `ins_customer` / `ins_claim` | 无（只读被查） |
| `chatdata_eval` | 评测夹具：工单与保险数据，由 `eval/fixture/prepare.sh` 重建 | 评测 |

问数时，ask 从 `chatdata_ask` 读**配置**，再去数据源指向的库（如 `chatdata_demo`）
读**业务数据**。两者刻意分开：业务库通常是别人维护的只读库，不该混进我们的配置表。

> 历史说明：早期这些表都建在 `vendor` 库里（借用了 第三方报表工具 的库）。
> 2026-10-24 已拆分迁出，旧库连同 57 张 第三方报表工具 遗留表一并删除，
> 备份见 `~/chatdata-backups/vendor-full-20261024-092503.sql`。

## 全新部署

```bash
# 1) 建库
mysql -uroot -p -e "
CREATE DATABASE chatdata_ask  DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE chatdata_iam  DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE chatdata_demo DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
"

# 2) ask 的表（按序号执行，SQL 里不写库名，靠命令行参数指定）
for f in sql/04-audit.sql sql/08-ask-engine.sql sql/10-ask-term-topic.sql \
         sql/11-ask-assistant.sql sql/12-ask-agent-session.sql \
         sql/14-ask-sql-example.sql \
         sql/16-ask-feedback.sql sql/17-ask-conversation.sql \
         sql/19-ask-audit-analysis.sql sql/20-ask-table-base-filter.sql \
         sql/21-channel.sql
do
  mysql -uroot -p chatdata_ask < "$f"
done

# 3) 种子数据（可选，本地开发用）
mysql -uroot -p chatdata_ask < sql/09-ask-engine-seed.sql

# 4) IAM（顺序有讲究，见 .scratch/iam-platform/spec.md）
mysql -uroot -p chatdata_iam < sql/iam/01-schema.sql
mysql -uroot -p chatdata_iam < sql/iam/07-role-permission.sql
mysql -uroot -p chatdata_iam < sql/iam/08-bootstrap.sql
```

## 渠道表（`21-channel.sql`）

渠道与 ask 共用 `chatdata_ask` 库——它们都是「渠道侧状态」，单独建库会让一次问数
跨三个库，排查时要连三次。

| 表 | 用途 |
|---|---|
| `channel_inbound_dedup` | 入站去重。**保留 24 小时**：飞书最晚一次重推在 6 小时后，窗口短于它会让重推被当成新消息 |
| `channel_session` | `chat_key` → `ask_conversation.id` 的映射，含当前选中的助理 |
| `channel_answer` | 已发送的答案。翻页与反馈靠它——飞书卡片回调只带 `answer_id`，不带原始问题 |

`channel_answer` 存的是**完整结果行**（ask 最多返回 1000 行）。不把它塞进卡片 JSON：
几百 KB 会撞飞书的卡片体积限制，而回传的 `value` 更放不下。

## 不要执行的历史脚本

早期版本的建库脚本（授权独立表、用户/组织本地表、IAM 数据策略路径）已经从仓库里移除——
它们对应的能力都收敛到了现行 schema（授权并入 `perm_json`、用户与组织归 IAM）。
如果你在别处看到引用，那些文档描述的是迁移当时的状态。

## 改库名时要注意

库名出现在四个地方，漏一个就会在运行期报"表不存在"：

1. `application.yml` 的 `MYSQL_DB` / `IAM_DB` 默认值
2. **`ask_datasource` 表里的 JDBC URL**（数据源 1 指向业务库）
3. 测试里的硬编码连接串（`*RealDbTest`）
4. **channel 的 `channel.iam.database`**——它直连 IAM 库查 `iam_user` 的绑定关系，
   而主数据源连的是 ask 库，所以要跨库查询

第二处最容易漏——它不在代码里，改了配置也不会跟着变。

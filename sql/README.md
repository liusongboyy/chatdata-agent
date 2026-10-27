# 数据库与建库脚本

## 库的划分

整套系统用**四个库**，按职责分开：

| 库 | 内容 | 谁写 |
|---|---|---|
| `chatdata_ask` | 服务自身的表：语义域、注册表、助理、术语、主题、审计、会话、反馈、策略审计 | ask 服务 |
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
         sql/19-ask-audit-analysis.sql sql/20-ask-table-base-filter.sql
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

## 不要执行的历史脚本

早期版本的建库脚本（授权独立表、用户/组织本地表、IAM 数据策略路径）已经从仓库里移除——
它们对应的能力都收敛到了现行 schema（授权并入 `perm_json`、用户与组织归 IAM）。
如果你在别处看到引用，那些文档描述的是迁移当时的状态。

## 改库名时要注意

库名出现在三个地方，漏一个就会在运行期报"表不存在"：

1. `application.yml` 的 `MYSQL_DB` / `IAM_DB` 默认值
2. **`ask_datasource` 表里的 JDBC URL**（数据源 1 指向业务库）
3. 测试里的硬编码连接串（`*RealDbTest`）

第二处最容易漏——它不在代码里，改了配置也不会跟着变。

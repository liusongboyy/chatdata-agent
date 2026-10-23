# 反馈闭环

用户发现答案不对时，可以顺手给出正确 SQL。这条信息经管理员审核后进入该域的
SQL 范例库，参与之后相似问题的检索——**这是问数准确率唯一会随时间自动变好的机制**。

## 为什么必须有人工审核

执行成功 ≠ 语义正确。用户改的 SQL 也可能漏条件、理解反口径，但照样能跑出数字。
如果自动收进范例库，一个人的误解会被放大成**所有相似问题的模板**。

所以流程只有一个入口，且中间有一道人的闸门：

```
问数结果页
   │  用户点「答得不对」/「有帮助」
   ▼
ask_feedback (PENDING)
   │  管理员在「反馈审核」逐条看
   ├── 拒绝 → REJECTED（只留痕，不写范例）
   └── 通过 → APPROVED
          │  仅 SQL_CORRECTION 类型
          ▼
ask_sql_example (source=FEEDBACK, enabled=1)
   │
   ▼
下次相似问题：SqlExampleSelector 命中 → 进 prompt（few-shot）
```

**没有任何"自动收录"路径。**这与 `sql/14`、`sql/16` 里的约定一致。

## 数据

- `ask_feedback`（`sql/16-ask-feedback.sql`）：反馈原文、类型、状态、审核人
- `ask_sql_example`：审核通过后的范例。`source` 新增取值 `FEEDBACK`

类型：

| 类型 | 含义 | 审核通过后 |
|---|---|---|
| `HELPFUL` | 答案有帮助 | 只用于统计 |
| `WRONG_ANSWER` | 答案不对，没给正确 SQL | 只留痕 |
| `SQL_CORRECTION` | 答案不对，并给了修正 SQL | 写入/更新范例 |

## 接口

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/ask/feedback` | 登录 | 提交反馈 |
| GET | `/api/engine/admin/feedback?status=PENDING` | 管理台角色 | 列表 |
| POST | `/api/engine/admin/feedback/{id}/approve` | 管理台角色 | 通过 |
| POST | `/api/engine/admin/feedback/{id}/reject` | 管理台角色 | 拒绝 |

## 审核时的校验

服务端**不信任提交时的结果**，审核时重新校验：

1. **只读**：只允许 `SELECT` / `WITH`（与提交时同一个判据 `SqlTools.rejectUnsafe`）
2. **表白名单**：SQL 引用的表必须在该域启用的注册表内；解析不了就拒绝（fail-closed）
3. **同问法冲突**：
   - 已有 `MANUAL`/`AUDIT` 范例 → 拒绝，避免用户反馈覆盖人工确认过的口径
   - 已有 `FEEDBACK` 范例 → 更新那一条（后一次反馈通常是对前一次的修正）

审核是**带条件的事务**（`status='PENDING'` + `@Transactional`）：
两个人同时点通过时，第二次影响 0 行并报"已被处理"，不会静默覆盖。

## 界面上刻意不做的两件事

- **重算轮次不给反馈**：重算回答的是"放宽条件后会怎样"，不是原问题的正确答案。
  把它收进范例库会教模型把原问题写成放宽后的查询。
- **能力答复不给 SQL 修正框**：「这里能分析哪些数据」不是数据问题，
  给它配一条 SQL 范例会让下一个能力问题被教着写 SQL（与 prompt 规则 6 相反）。
  这类答复仍可以点「有帮助 / 答得不对」，只是走 `WRONG_ANSWER`。

## 运维

- **部署前必须执行 `sql/16-ask-feedback.sql`**（应用不会自动建表；缺表时提交反馈会返回 500）
- 反馈列表按 `status + created_at` 与 `domain_id + created_at` 建了索引
- 评测/压测流量不会进这张表；评测的审计身份是 `eval:<数据集名>`
- 反馈表暂不自动清理：它是"哪些问法长期答不好"的数据来源，
  建议至少保留一个季度再按需归档

## 测试

- `FeedbackServiceTest`：状态机、只读/白名单闸门、并发审核
- `AdminSqlExampleFeedbackTest`：与手工范例的冲突策略
- 端到端手工验证：提交 → 待审 → 通过 → `ask_sql_example` 出现 `source=FEEDBACK`

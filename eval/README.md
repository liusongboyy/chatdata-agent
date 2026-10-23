# 问数评测集

准确率不能靠"问一句看看"判断。这个目录存放**验收基线**：每个数据集一个 JSON，
每条用例是一个中文问题加一条已确认正确的 gold SQL，跑分时按**结果集**比对（EX）。

## 快速开始

```bash
# 仓库根目录
./eval/run.sh                    # 跑 eval/ 下全部数据集
./eval/run.sh --limit 5          # 只跑前 5 条，调 prompt 时用
```

`run.sh` 会先安装 ask-engine，再以 `eval` profile 启动应用。模型密钥与数据源密钥
从环境变量读取（本地可用 `~/.chatdata-agent.env`）。

## 指标口径

| 指标 | 定义 | 为什么要单列 |
|---|---|---|
| 严格 EX 通过率 | 列数、行数、值全部一致 /（总数 - gold 错误） | 与 BIRD / Spider 的 EX 口径一致，是可对外比较的主指标 |
| 包含式 EX 通过率 | gold 的列都能在实际结果里匹配上，**多返回的列不算错** / 同分母 | 把"答案对但话多"与"答案错"分开。模型常会多带几列上下文，严格口径会把它们算成错 |
| 空结果率 | 执行成功但 0 行，而 gold 有数据 | 真实场景最大杀手，常被总分掩盖 |
| 静默语义错误率 | 执行成功、无异常，但结果不对（含空结果） | 不报错、数字看着正常，是数据系统最危险的失败形态 |
| 分档通过率 | SIMPLE / MEDIUM / HARD | 总分会被简单题淹没，短板通常在 HARD |

## 新增一个数据集

1. 在 `eval/` 下新建 `<数据集名>.json`（文件名任意，按名称排序执行）
2. 填 `domainId`（`ask_domain.id`）、跑分身份 `userId` / `roles`、用例列表
3. 跑 `./eval/run.sh`，确认没有 `GOLD_ERROR`

**不需要改代码或配置**——目录里所有 `*.json` 都会被加载。这也是这套 harness
面向"以后接很多数据源/数据集"的扩展点。

### 文件格式

```json
{
  "name": "智能工单域基线",
  "domainId": "1372",
  "userId": "admin",
  "roles": ["管理员", "chat2bi"],
  "assistantId": null,
  "cases": [
    {
      "id": "ticket-001",
      "difficulty": "SIMPLE",
      "question": "工单总数是多少？",
      "goldSql": "SELECT COUNT(*) AS cnt FROM ticket WHERE deleted = 0",
      "ordered": false,
      "columnOrder": "ANY",
      "tags": ["count"],
      "note": "口径说明，不参与判定"
    }
  ]
}
```

- `difficulty`：`SIMPLE`（单表聚合）/ `MEDIUM`（join、时间窗、分组）/ `HARD`（业务口径）
- `ordered`：行序是否参与比对。**只有带 LIMIT 的"前 N 名"才需要 true**
- `columnOrder`：`ANY`（默认，允许列重排）/ `STRICT`（列序必须一致）
- `roles` 必须能通过目标域的授权，否则每条都会是 `DENIED`
- **跑分身份必须对目标域有完整可见性**（无行级规则、无脱敏列）。
  否则系统结果会被改写（行被过滤、列变成 `***`），与 gold 不可比——
  这类差异不是准确率问题。要评"某角色看到什么"，应单独建数据集，
  并把 gold 改成按该角色的权限改写后的结果

### gold SQL 编写规范

- **只读**，一条 `SELECT` / `WITH`
- **结果确定**：不要依赖 `NOW()`、当天日期、随机值；时间范围写死具体日期
- **列要少而准**：问题问什么就返回什么，不要 `SELECT *`
- 有 `LIMIT` 时必须有唯一排序键，否则并列值的顺序不稳定，会制造假失败
- 先手工执行一遍确认有结果；如果预期就是空结果，在 `note` 里写清楚

## 报告

- 终端：总览 + 每个数据集一段 + 未通过明细
- JSON：`chatdata-agent-ask/target/eval-report.json`
  - 顶层是 `EvalSuiteReport`（多数据集汇总）
  - `reports[]` 每个数据集一份 `EvalReport`
  - 每条用例带 `goldSql`、`generatedSql`、行数、耗时、失败原因，
    以及 `status`（严格 EX）与 `containsPassed`（包含式 EX）两个判定
- 两种口径都会打印。若 `包含式通过 > 严格通过`，差额就是"数据正确但多返回了列"的条数

## CI 门禁

在 `application-eval.yml` 或环境变量里设置下限：

```bash
CHATDATA_EVAL_MIN_PASS_RATE=0.8 ./eval/run.sh
```

低于阈值时进程返回非 0。建议先把当前分数记为基线，再逐步抬高——
**每次改 prompt / 换模型都跑一遍**，否则"感觉变准了"没有任何证据。

## 已知局限

- **静默语义错误率是近似值**：定义为"执行成功但结果不一致"。它抓得住"漏了条件导致数字不同"，
  但抓不住"错得恰好与原结果相同"的极小概率情况
- **EX 不等于业务正确**：gold SQL 本身写错时，评测会把正确答案判错。所以 gold 必须人工审核
- **要花模型调用**：每条用例一次问数。全量 39 条约 2–3 分钟、几十次模型调用
- **评测会写审计**：与真实问数一样落 `ask_audit_log`，审计身份是 `eval:<数据集名>`，
  可用 `WHERE username LIKE 'eval:%'` 过滤掉

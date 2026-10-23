# 权限配置在哪里、怎么配

> **状态：历史文档。** 本文描述的是 **JimuChatBI（厂商）** 的权限存储位置与实测行为——
> `chat2bi_domain.perm_json` / `chat2bi_assistant.perm_json` 那两张表。
> 厂商依赖已于 2026-10-20 从本工程移除（见 `docs/adr/0007` 的同批变更），
> 因此**本文的操作指引对当前系统不再适用**。
>
> 保留它的理由：文中「七个实测的坑」是 `docs/adr/0002` 的实证来源，
> 而那份 ADR 的**结论被自研引擎继承**（默认拒绝、行/列规则语义、角色名逐字符匹配等）。
> 自研引擎的权限配置在 `ask_domain.perm_json` / `ask_assistant.perm_json`，机制见 PRD §7。

> 本文记录 JimuChatBI 权限的**实际存储位置与字段含义**，以及七个实测踩出来的坑。
> 七个坑都不在厂商文档里，且失败形态都是**静默**的——不报错，只是规则不生效或过度放行。
>
> 其中 **⑤（`admin` 绕过）最危险**：它会让人误判脱敏没生效，或让一个无意命名的角色
> 静默获得全量数据可见性。
>
> 相关决策见 [ADR-0002](adr/0002-data-permission-enforcement-point.md)（权限强制点）
> 与 [ADR-0004](adr/0004-role-model.md)（角色模型）。

## 一、权限没有独立的表

全部存在两个 **JSON 字段**里，都是 `text` 类型：

| 表 | 字段 | 管什么 |
|---|---|---|
| `chat2bi_domain` | `perm_json` | **域级**：访问授权 + 行级权限 + 列级权限 + 敏感词 |
| `chat2bi_assistant` | `perm_json` | **助手级**：访问授权 |

这就是为什么在库里找不到"权限表"——它不存在。想查权限，就是查这两个字段。

```sql
-- 查看某个域的全部权限配置（格式化）
SELECT JSON_PRETTY(perm_json)
FROM chat2bi_domain
WHERE name = '保险分析域';
```

## 二、域的 `perm_json` 字段参考

```json
{
  "grantRoles": ["区域经理", "分析师"],
  "grantUsers": [],
  "isPublic": false,
  "rowRules": [
    {"id": 1, "role": "区域经理", "field": "region", "op": "=",
     "value": "华东", "dynamic": false, "sysVar": ""}
  ],
  "colRules": [
    {"id": 1, "role": "分析师", "field": "premium", "action": "脱敏"}
  ],
  "sensWords": ["王总"],
  "sensCats": {"politics": true, "abuse": true},
  "sensitivity": {}
}
```

### 访问授权（粗粒度：能不能用这个域）

| 字段 | 类型 | 含义 |
|---|---|---|
| `grantRoles` | `string[]` | 允许访问的角色码列表 |
| `grantUsers` | `string[]` | 允许访问的用户名列表（与 `grantRoles` 是**或**关系） |
| `isPublic` | `bool` | `true` 时忽略上面两个，对所有已认证用户开放 |

### `rowRules`（行级：能看哪些行）

| 字段 | 含义 |
|---|---|
| `role` | 命中哪个角色时应用此规则 |
| `field` | 被限定的字段（**列名**，不是中文别名） |
| `op` | 操作符。实测支持：`=` `!=` `<>` `<` `<=` `>` `>=` `like` `LIKE` `in` |
| `value` | 限定值 |
| `dynamic` | 是否使用动态变量 |
| `sysVar` | 动态变量名。**实测 `sysVar` 为空或不支持时该条规则会被跳过并打日志** |

生效方式：把生成的查询**整体包成子查询**，在外层追加 `WHERE <field> <op> <value>`。对 `GROUP BY` 语义正确——过滤的是结果维度而非基表行。

```sql
-- 实际下发的语句（区域经理查保费排名）
SELECT * FROM (
    SELECT region AS "大区", SUM(premium) AS "保费合计"
    FROM ins_policy GROUP BY region ORDER BY 2 DESC
) c2b_sub WHERE `大区` = '华东'
```

### `colRules`（列级：能看哪些列）

| 字段 | 含义 |
|---|---|
| `role` | 命中哪个角色时应用此规则 |
| `field` | 被处理的字段（**列名**） |
| `action` | `脱敏`（替换为 `***`）或 `hide`（整列不返回） |

**脱敏发生在结果层而非 SQL 层**：SQL 仍是 `SUM(premium)`，但输出值与 `insight` 文字里的数值都被替换为 `***`。真实值不进入 LLM 输出。

### 敏感词

| 字段 | 含义 |
|---|---|
| `sensWords` | 命中即拒答的词。判定方式是对**提问文本做子串匹配**（`String.contains`） |
| `sensCats` | 敏感类别开关（如 `politics` / `abuse`），值为 `true` 表示启用 |
| `sensitivity` | **按列的敏感级别**，见下方专节 |

### `sensitivity`（按列的敏感级别）

**它不是独立功能，而是 `colRules` 的另一种写法**——两者最终合并成同一个
`Map<列名, "hide"|"mask">`，驱动结果集的列隐藏与打码。

```json
"sensitivity": { "premium": "高", "cust_name": "中" }
```

| 级别值 | 动作 |
|---|---|
| `高`（精确匹配）/ `high`（忽略大小写） | **隐藏整列** |
| `中`（精确匹配）/ `mid` 或 `medium`（忽略大小写） | **脱敏为 `***`** |
| 其他（含 `低`） | **无动作** |

`低` 是合法的、可存储的级别，界面会显示为绿色，但**后端对它没有任何处理**——
界面的 `defMask` 对 `低` 也返回 `null`，属于设计如此而非遗漏。

键是**列名**，可选带表前缀（`"t.col"` 会同时注册 `"col"`）。

> **与 `colRules` 的两处关键差异：**
> 1. **`sensitivity` 不按角色过滤**——它对**所有用户**生效（管理员除外）。
>    `colRules` 必须匹配 `role` 才生效。想让某个列对所有人隐藏，用 `sensitivity`；
>    只想对特定角色隐藏，用 `colRules`。
> 2. **键冲突时 `colRules` 优先**——`sensitivity` 先写入、`colRules` 后写入，后者覆盖前者。

## 三、助手的 `perm_json` 字段参考

```json
{"enabled": true, "grantRoles": ["区域经理", "分析师"], "grantUsers": []}
```

| 字段 | 含义 |
|---|---|
| **`enabled`** | **主开关。必须为 `true`，否则整个授权配置失效**（见坑②） |
| `grantRoles` | 允许使用该助手的角色 |
| `grantUsers` | 允许使用该助手的用户 |

助手层管的是「**能用哪个助手**」（功能可见性），**不是数据保护点**——数据保护由域层承担。
被拦下时返回明确错误（"您没有该助理的访问权限"），而非空结果。

## 四、与 IAM 角色目录的关联方式

**权限按角色名字符串匹配**，配置靠角色码关联：

```
iam_role.role_code              ← IAM 维护（管理台 /iam/admin/roles）
        ↕ 必须逐字符一致（不 trim、不改大小写）
perm_json.rowRules[].role       ← 数据权限配置
perm_json.colRules[].role
perm_json.grantRoles
chat2bi_assistant.perm_json.grantRoles
```

**角色码不一致 → 规则静默不生效**，不报错、不打日志。

> **现成的反例**：演示数据 `系统用户分析域` 的 `colRules` 引用了角色 `财务`，
> 而 IAM 角色目录里**没有这个角色**——该规则永远不会生效。
> 配置权限前先确认角色码存在于 IAM（`ask` 管理台的输入框会列出可选角色并当场标错）。
>
> **自研引擎（`ask_*`）已由启动校验兜住这一点**（2026-10-16 加）。
> 校验读 IAM 角色目录（`GET /iam/policy/roles`），把**运行时生效的
> `perm_json`** 里引用的角色逐个比对（ADR-0010 之后只剩本地这一份；IAM 的
> 数据策略路径已于 2026-10-23 下线），并按**引用的位置**分两种严重度：
>
> | 引用位置 | 失效后果 | 处理 |
> |---|---|---|
> | 授权名单 `grantRoles` / `unrestrictedRoles` | **少给**了权限，用户来报障 | WARNING，只记日志，不拦启动 |
> | 行列规则 `rowRules[].role` / `colRules[].role` | **多给**了权限——限制整条不生效，受限角色看到全量/明文，**没人会发现** | ERROR，默认拒绝启动 |
>
> 两类都当错误会让一个打字错误拦住启动；都当警告则放过了真正的数据暴露。
> 顺带一提，`rowRules` / `colRules` 的 `role` 写成空串同样会被报出来——
> 它一样匹配不上任何主体。
>
> **IAM 不可用时跳过而不是拦启动**：校验会记一条 `POLICY_SOURCE_UNAVAILABLE`（WARNING）
> 并跳过该资源。拦启动会把一次 IAM 抖动变成崩溃循环，静默跳过又会让"校验通过"变成空话。
> 注意"取不到策略"与"IAM 明确说没有策略"不同：后者按拒绝处理（`NO_GRANT`，ERROR）。
>
> 本文上半部分描述的**厂商那条链路（`chat2bi_*`）没有这道校验**，
> 它的 `AccessGrantStartupCheck` 只管"授权是否为空"。上面的反例仍在。

## 五、七个实测的坑

> 这些坑厂商文档里都没有，且失败形态**全是静默的**——不报错，只是规则不生效、
> 或过度放行。前六条都经过开/关对照实验验证。

### ① `grantRoles` 为空 = 对所有人开放，不是拒绝

这是「默认放行」而非「默认拒绝」。实测：域 `grantRoles: []` 且 `isPublic: false` 时，
一个**无任何角色**的用户能查到与管理员完全相同的全量数据。

把 `grantRoles` 设为 `["区域经理"]` 后重测，未获授权的用户立刻返回 0 行
——**闸门本身是失败关闭的，问题在于默认值**。

> 已由 `AccessGrantStartupCheck` 在启动时校验：任一启用域或在线助手的授权为空则**拒绝启动**。
> 见 [ADR-0002](adr/0002-data-permission-enforcement-point.md) 的 R1。

### ② 助手的 `enabled` 不设 = 对所有人开放

比①更隐蔽。实测 `{"grantRoles": ["区域经理"]}`（**缺 `enabled`**）时，
`canAccess` 对**任何角色集**返回 `true`——包括空集与不存在的角色。

加上 `enabled: true` 后才按 `grantRoles` / `grantUsers` 判定。

**两层的判据不同，不是笔误**：域层看「有没有授权内容」，助手层看「`enabled` 开关是否打开」。
这是厂商各自默认值的差异。

### ③ `rowRules` / `colRules` 按字段匹配，等价字段会漏

同一个业务口径在语义层里可能存在**多个字段**，而规则只覆盖被显式列出的那一个。

实测：`保险分析域` 中

| 指标（中文别名） | 实际字段 | 表 |
|---|---|---|
| 年保费（元） | `premium` | `ins_policy` |
| 保费合计 | `premium_total` | `v_ins_premium_trend` |

`colRules` 只配了 `premium`，于是受限制的用户**换一个等价指标就能看到同样的数值**。

**这不是代码问题，是语义建模的纪律问题**：一个业务口径应对应一个字段。
若确需保留多个等价字段，**每个都必须单独配置权限规则**。

### ⑤ ⚠️ 用户名或角色里含 `admin` 会**跳过全部行列权限**

厂商在权限助手的最前面有一条硬编码的绕过：

```java
if (user.equalsIgnoreCase("admin") || roles.stream().anyMatch(r -> r.contains("admin")))
    return;   // 直接返回，行级与列级规则一律不应用
```

**实测确认**：给 `admin` 账号加上 `分析师` 角色（该角色配置了脱敏）后，它**仍然看到明文**；
同一时刻 `lisi`（仅 `分析师`）看到的是 `***`。

**两个后果：**

1. **本项目内置的 `admin` 账号看不到任何脱敏效果。** 演示权限时不要用它，
   否则会误以为脱敏没生效。
2. **任何名字里含 `admin` 的角色都会成为绕过通道。** 例如若建一个叫
   `admin_assistant`（本意是"管理员的助理"）的角色，它会**静默地获得全量数据可见性**。
   **角色命名时必须避开 `admin` 子串**，除非确实想要绕过。

### ⑥ `sensitivity` 不按角色过滤，`colRules` 才过滤

想让某个列对**所有人**隐藏，用 `sensitivity`；只想对**特定角色**隐藏，用 `colRules`。
用错会导致"本意只限制一个角色，结果限制了所有人"（或反之）。

### ⑦ 权限只作用于语义层已登记的表

`chat2bi_table` 中 `mode: sql` 的虚拟表（如 `v_ins_premium_trend`）在权限上与普通表等价，
但 `field` 必须写**虚拟表暴露的列名**，不是底层物理表的列名。

实测：`v_ins_premium_trend` 暴露 `premium_total` 列（底层来自 `ins_policy.premium` 的聚合），
给 `premium_total` 配规则生效，给 `premium` 配则对该虚拟表无效。

## 六、怎么改

### 界面

管理台 → 语义域 → 权限配置。

> **当前限制**：`canPermEdit` 实测对所有角色均为 `false`，即**权限规则的编辑界面默认关闭**。
> 语义域管理员目前无法通过界面修改行/列权限规则，只能用 SQL。
> 开启条件未查明——若需要界面化配置权限，需先解决这一点。

### SQL

```sql
-- 修改域访问授权
UPDATE chat2bi_domain
SET perm_json = JSON_SET(perm_json, '$.grantRoles', JSON_ARRAY('区域经理', '分析师'))
WHERE id = '6a01a7bce0174875a413f60e5bdec16b';

-- 新增一条列级脱敏规则
UPDATE chat2bi_domain
SET perm_json = JSON_ARRAY_APPEND(
        perm_json, '$.colRules',
        JSON_OBJECT('id', 3, 'role', '分析师', 'field', 'coverage_amount', 'action', '脱敏'))
WHERE id = '6a01a7bce0174875a413f60e5bdec16b';

-- 启用助手授权（enabled 必须为 true）
UPDATE chat2bi_assistant
SET perm_json = JSON_OBJECT(
        'enabled', TRUE,
        'grantRoles', JSON_ARRAY('区域经理'),
        'grantUsers', JSON_ARRAY())
WHERE id = '956f9607116047ada54619734d85f99f';
```

### 改完必须验证

权限配置的失败形态是静默的，**改完不验证等于没改**。最小验证方式：
用两个不同角色的账号问同一个问题，确认返回的数据确实不同。

```bash
# 域访问授权是否生效：未授权用户应返回 0 行
# 行级规则是否生效：受限角色应只看到其范围内的行
# 列级规则是否生效：受限角色的敏感列应显示为 ***
```

## 七、审计能回答什么

`ask_audit_log` 记录了每次问数的**角色快照**（`roles` 列）与实际执行的 SQL。
排查「某用户为什么看到了这些数据」时，先查审计里的角色快照，
再对照当时的 `perm_json` —— 权限配置可能已经变了，审计里的是当时的值。

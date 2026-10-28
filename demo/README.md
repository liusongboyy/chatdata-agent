# 组织层级动态权限：演示环境

一套可复现的演示数据，用来验证「同一张表，不同层级的用户看到不同范围的数据」。
配置正确时，**五个账号问同一个问题会得到五个不同答案**——这是唯一可靠的验收方式。

## 它演示什么

便利店订单落在**门店**级，门店是组织树的叶子节点。往上依次是
组 → 使用部门 → 子公司 → 便利群。

```
便利群 /1/100/  ← admin2（区域经理）看到全部 16800
├─ 华东子公司 /1/100/110/  ← zhangsan（区域经理）看到 3600
│  └─ 使用部门A .../120/
│     └─ 华东A组 .../130/
│        ├─ 上海门店1 .../140/  ← reviewer（分析师，精确匹配）看到 1000
│        └─ 上海门店2 .../141/
├─ 华北子公司 /1/100/111/
│  └─ 使用部门A .../121/ └ 华北A组 .../131/  ← auditor（区域经理）看到 4600
│     └─ 北京门店1 .../150/
└─ 华南子公司 /1/100/112/  └ ... └ 广州门店1 .../160/
```

## 两份组织数据必须一致

| 位置 | 用途 |
|---|---|
| `chatdata_iam.iam_org` | 决定**当前登录用户**在哪个节点（令牌里的 `orgPaths`） |
| `chatdata_cvs.org` | 决定**每一行订单**属于哪个节点（行规则过滤的 `org_path`） |

两边路径必须逐字符相同，否则前缀匹配不到——**表现是"什么都查不出来"，
而不是报错**。生产里 `chatdata_cvs.org` 由同步任务维护。

不做跨库 join 是刻意的：业务库通常是别人维护的只读库，
而且跨库查询在多实例部署下会绑死两个库的部署拓扑。

## 装起来

```bash
mysql -uroot -p < demo/01-cvs-database.sql        # 业务库：组织副本 + 门店 + 订单
mysql -uroot -p < demo/02-iam-org-and-users.sql   # 组织树 + 用户绑定 + 角色
mysql -uroot -p < demo/03-ask-domain.sql          # 数据源 + 语义域
./demo/04-encrypt-datasource.sh                   # 加密数据源密码（必须）
```

四个脚本都是幂等的，可以反复执行。

**`04` 不能省。** `03` 是"先删后插"，会把 `password_enc` 清成 NULL；
不加密的话连接池建不起来，报 `Connection is not available, total=0`——
那个报错指向数据库连不上，与真实原因（密码没加密）看不出关系。

## 验证

装好后重启 ask 服务，用不同账号在问数页提问**「交易金额合计是多少？」**：

| 账号 | 密码 | 角色 | 组织 | 应看到 |
|---|---|---|---|---|
| `admin2` | `admin123` | 区域经理 | 便利群 `/1/100/` | **16800** |
| `zhangsan` | `zhangsan123` | 区域经理 | 华东子公司 `/1/100/110/` | **3600** |
| `auditor` | `auditor123` | 区域经理 | 华北A组 `/1/100/111/121/131/` | **4600** |
| `reviewer` | `admin123` | **分析师** | 上海门店1 `.../130/140/` | **1000** |
| `admin` | `admin123` | super_admin | —（在 `unrestrictedRoles` 里） | **16800** |

分店小计：上海门店1 = 1000、上海门店2 = 2600、北京门店1 = 4600、广州门店1 = 8600，
全库合计 16800。

### 为什么是这几个数

金额刻意做成互不相同，这样**看错层级会立刻暴露**：如果华东的用户看到 16800，
说明前缀范围放大到了整棵树；如果看到 0，说明路径对不上。

### 两种运算符的差异

`区域经理` 配 `starts_with`，`分析师` 配 `=`：

| 角色 | 规则 | 生成的谓词 | 语义 |
|---|---|---|---|
| 区域经理 | `starts_with` + `orgPaths` | `org_path LIKE '/1/100/110/%'` | 覆盖**整棵子树** |
| 分析师 | `=` + `orgPaths` | `org_path = '/1/100/110/120/130/140/'` | 只有**本级** |

同一条 `starts_with` 规则，对便利群 / 华东 / 华北A组会自动生成三个不同的前缀——
**不需要为每个层级单独配规则**，这是物化路径 + 前缀匹配的价值。

`reviewer` 用 `=` 而不是 `starts_with`，是为了演示「组织树以后在门店下加子节点时，
店长不该跟着看到」。这条差异由角色区分，不需要新机制。

### id 路径 vs 编码路径

上面用的是 `orgPaths`（id 物化路径，`/1/100/110/`）。真实业务库通常存组织**编码**，
那时要用 `orgCodePaths`，字段指向 `dim_org.org_code_path`（`/HQ/HQ-CVS/SUB-EAST/`）。
两者语义完全一致，只是 id 换成 code——选哪个取决于业务表里存的是什么。

`dim_org` 两列都有，可以现场对同一份数据切换着验证。**两列必须与
`chatdata_iam.iam_org` 的 `path` / `code_path` 对得上**，否则规则静默匹配 0 行。

## 常见错误

| 症状 | 原因 |
|---|---|
| 所有人都看到全量 | 用户角色在 `unrestrictedRoles` 里，或行规则角色名写错（静默不匹配） |
| 所有人都是 0 | `chatdata_cvs.org.path` 与 `iam_org.path` 不一致；或配了 `orgCodePath(s)` 但字段指向 id 路径（应指向 `code_path`） |
| 报 `找不到字段 org_path` | 虚拟表 SQL 里 SELECT 了 `org_path`，但没登记到 `columns_json` |
| 报 `找不到可用的日期列` | 「生效日期」列被删了；时间条件需要它 |
| `Connection is not available` | 忘了跑 `04-encrypt-datasource.sh` |

## 卸载

```bash
mysql -uroot -p -e "
DELETE FROM chatdata_ask.ask_domain_table WHERE domain_id=2200;
DELETE FROM chatdata_ask.ask_domain WHERE id=2200;
DELETE FROM chatdata_ask.ask_datasource WHERE id=120;
DROP DATABASE IF EXISTS chatdata_cvs;"
```

IAM 的组织与用户绑定**故意保留**：删掉会让演示账号失去归属，
下次要演示又得重配一遍。要彻底清理按 `02` 里的 id 逐条删。

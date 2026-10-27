# AdventureWorks 销售分析域

一个**接近真实企业规模**的演示 / 评测域：**28 张表、172 个列定义、30 条关联**，
覆盖星型、雪花、自关联、多对多桥接四种结构。

用来测什么：现有评测域（保险 3 表、工单 4 表）规模太小，
测不出"表多、关系绕、口径复杂"时产品会不会崩。

## 数据来源

Microsoft AdventureWorks OLTP 示例库（MySQL 移植版）：

```
/Users/liusongbo/Downloads/AdventureWorks-MySQL-with-FKs.zip
```

原始 dump 有两个问题，导入前要处理：

1. **外键定义有缺陷**——`my_fk_48` 引用的列在 `specialofferproduct` 上缺唯一键，
   导入会中断。问数不需要外键，直接删掉 FK 子句即可。
2. **删 FK 子句会留下尾部多余逗号**（`, )`），要一并修掉。

处理脚本见 `import.sh`。

导入后的实际规模：

| 表 | 行数 |
|---|---|
| `salesorderdetail` | 121,609 |
| `salesorderheader` | 31,290 |
| `customer` | 19,434 |
| `purchaseorderheader` | 3,968 |
| `productinventory` | 1,069 |
| `product` | 504 |
| `employee` | 290 |

## 结构

### 销售链（星型 + 雪花）
```
salesorderdetail → product → productsubcategory → productcategory   ← 雪花三层
       ↓              ↓
salesorderheader   productmodel / productinventory / billofmaterials（BOM 自关联）
       ↓
   customer → salesterritory → stateprovince → countryregion          ← 地理三层
       ↓            ↓
     store     salesperson
       ↓
  creditcard / shipmethod / salesorderheadersalesreason → salesreason  ← 多对多桥接
```

### 采购链
```
purchaseorderdetail → purchaseorderheader → vendor
                            ↓
                        employee → employeedepartmenthistory → department   ← 自关联 ManagerID
                            ↓
                       shipmethod
```

### 其他
`specialofferproduct`（促销 ↔ 产品多对多）、`address`、`salestaxrate`。

## 建域

```bash
python3 demo/adventureworks/build-domain.py
```

脚本做三件事：注册数据源（id=140）、写 11 张注册表、写 7 条业务术语。
**幂等**：重复跑会先删掉旧配置。

建完重启 ask 服务，域 id 是 `2100`。

## 评测

```bash
./eval/run.sh --path demo/adventureworks/eval-fixture.json
```

`eval-fixture.json` 的 10 条用例覆盖：单表计数、时间窗、三层雪花 JOIN、
两跳 JOIN、三跳 JOIN（采购→员工→部门）、多对多桥接、条件占比、Top-N、
空结果表。当前 **10/10**。

## 建域时踩到的两个坑

**① 时间口径必须显式声明**

`OrderDate` 的别名不能叫「生效日期」（那是引擎按名字猜的候选之一），
必须用术语声明 `apply_kind=time`。否则问「2003 年的销售总额」会报
"本域内找不到可用的日期列"——域里有三个日期列，引擎不敢猜。

**② 原始数据的值是带尾随空格的**

`ProductLine` 实际取值是 `'R '`、`'S '`（带空格），不是 `'R'`。
这是真实企业数据里极常见的形态。查询时 `WHERE ProductLine = 'R'` 会查不到，
得用 `LIKE 'R%'` 或 `TRIM`。**没有为它打补丁**——这类脏数据正是要被测出来的。

**③ 有些表是空的**

`vendor`（供应商）、`transactionhistory`、`workorder` 在这个 dump 里是 0 行。
保留了 `vendor`——问「各供应商采购金额」预期返回空结果，
测的是"结果为空时会不会说清楚"，而不是把空表删掉绕开。

## 这个域测出过一个评测缺陷

`aw-007`「各供应商采购金额」曾判失败：gold 算出 `25793.748`，
模型那条算出 `25793.747999999984`——**同一个数**，只是 double 累加顺序不同。

`ResultSetComparator` 当时用全精度做字符串相等，于是 37 行被判成不同。
已修：浮点数按 **6 位有效数字** 归一。

容差没有宽到放过真错误——单测里 `25793.748` vs `25793.758` 仍然判错。

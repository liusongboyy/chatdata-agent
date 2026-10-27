# AdventureWorks 销售分析域

一个**接近真实企业规模**的演示 / 评测域：11 张表、80 个列定义、11 条关联，
覆盖星型 + 雪花两种结构。

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

雪花三层（这是原来那几个域都没有的）：

```
salesorderdetail → product → productsubcategory → productcategory
       ↓
salesorderheader → customer → salesterritory
       ↓              ↓
  salesperson    store（门店客户）
       ↓
  specialoffer      productinventory
```

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

`eval-fixture.json` 的 6 条用例覆盖：单表计数、时间窗、三层雪花 JOIN、
两跳 JOIN、条件占比、Top-N。当前 **6/6**。

## 建域时踩到的两个坑

**① 时间口径必须显式声明**

`OrderDate` 的别名不能叫「生效日期」（那是引擎按名字猜的候选之一），
必须用术语声明 `apply_kind=time`。否则问「2003 年的销售总额」会报
"本域内找不到可用的日期列"——域里有三个日期列，引擎不敢猜。

**② 原始数据的值是带尾随空格的**

`ProductLine` 实际取值是 `'R '`、`'S '`（带空格），不是 `'R'`。
这是真实企业数据里极常见的形态。查询时 `WHERE ProductLine = 'R'` 会查不到，
得用 `LIKE 'R%'` 或 `TRIM`。**没有为它打补丁**——这类脏数据正是要被测出来的。

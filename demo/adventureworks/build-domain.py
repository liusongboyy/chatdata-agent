import json, subprocess

def mysql(sql, db="chatdata_ask"):
    r = subprocess.run(["mysql","-uroot","-proot","-N","--batch",db,"-e",sql],
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit(r.stderr)
    return r.stdout

def esc(s):
    return s.replace("\\","\\\\").replace("'","\\'")

DOMAIN_ID = 2100

def col(c, typ, agg, alias, **kw):
    d = {"col": c, "type": typ, "agg": agg, "alias": alias, "enabled": True}
    d.update(kw)
    return d

def derived(alias, kind, **kw):
    d = {"col": "", "type": "decimal", "agg": "NONE", "alias": alias, "enabled": True,
         "derived": dict(kind=kind, **kw)}
    return d

def cond(c, typ, agg, alias, field, op, value):
    return {"col": c, "type": typ, "agg": agg, "alias": alias, "enabled": True,
            "condition": {"field": field, "op": op, "value": value}}

# ── 表 ──
tables = []

# 1. 销售订单主表
tables.append((210001, "salesorderheader", "销售订单", "TABLE", None, [
    col("SalesOrderID", "int", "COUNT", "订单数"),
    col("SubTotal", "double", "SUM", "小计金额"),
    col("SubTotal", "double", "AVG", "平均订单金额"),
    col("TaxAmt", "double", "SUM", "税额"),
    col("Freight", "double", "SUM", "运费"),
    col("TotalDue", "double", "SUM", "应收总额"),
    col("TotalDue", "double", "AVG", "平均应收"),
    col("OrderDate", "datetime", "", "下单时间"),
    col("DueDate", "datetime", "", "应交付时间"),
    col("ShipDate", "datetime", "", "发货时间"),
    col("Status", "tinyint", "", "订单状态"),
    col("OnlineOrderFlag", "bit", "", "线上订单标记"),
    col("CustomerID", "int", "", "客户ID"),
    col("SalesPersonID", "int", "", "销售员ID"),
    col("TerritoryID", "int", "", "销售区域ID"),
    derived("订单履约时长", "duration", start="下单时间", end="发货时间", unit="HOUR"),
    cond("SalesOrderID", "int", "COUNT", "线上订单数", "线上订单标记", "=", "1"),
    derived("线上订单占比", "ratio", left="线上订单数", right="订单数"),
], None))

# 2. 销售订单明细
tables.append((210002, "salesorderdetail", "销售订单明细", "TABLE", None, [
    col("SalesOrderDetailID", "int", "COUNT", "明细行数"),
    col("OrderQty", "smallint", "SUM", "销售数量"),
    col("OrderQty", "smallint", "AVG", "平均数量"),
    col("UnitPrice", "double", "AVG", "平均单价"),
    col("UnitPriceDiscount", "double", "AVG", "平均折扣率"),
    col("LineTotal", "double", "SUM", "行金额"),
    col("ProductID", "int", "", "产品ID"),
    col("SalesOrderID", "int", "", "订单ID"),
    col("SpecialOfferID", "int", "", "促销ID"),
], None))

# 3. 产品（雪花：→ 子类 → 大类）
tables.append((210003, "product", "产品", "TABLE", None, [
    col("ProductID", "int", "COUNT", "产品数"),
    col("Name", "varchar", "", "产品名称"),
    col("ListPrice", "double", "AVG", "平均标价"),
    col("StandardCost", "double", "AVG", "平均成本"),
    col("StandardCost", "double", "SUM", "成本合计"),
    col("SafetyStockLevel", "smallint", "AVG", "平均安全库存"),
    col("DaysToManufacture", "int", "AVG", "平均制造天数"),
    col("Color", "varchar", "", "颜色"),
    col("ProductLine", "varchar", "", "产品线"),
    col("Class", "varchar", "", "产品等级"),
    col("ProductSubcategoryID", "int", "", "产品子类ID"),
    col("SellStartDate", "datetime", "", "上市时间"),
    col("SellEndDate", "datetime", "", "停售时间"),
    derived("毛利率", "diff", left="平均标价", right="平均成本"),
], None))

# 4. 产品子类 → 大类
tables.append((210004, "productsubcategory", "产品子类", "TABLE", None, [
    col("ProductSubcategoryID", "int", "COUNT", "子类数"),
    col("Name", "varchar", "", "子类名称"),
    col("ProductCategoryID", "int", "", "产品大类ID"),
], None))

tables.append((210005, "productcategory", "产品大类", "TABLE", None, [
    col("ProductCategoryID", "int", "COUNT", "大类数"),
    col("Name", "varchar", "", "大类名称"),
], None))

# 6. 客户
tables.append((210006, "customer", "客户", "TABLE", None, [
    col("CustomerID", "int", "COUNT", "客户数"),
    col("CustomerType", "varchar", "", "客户类型"),
    col("TerritoryID", "int", "", "销售区域ID"),
    cond("CustomerID", "int", "COUNT", "门店客户数", "客户类型", "=", "S"),
    cond("CustomerID", "int", "COUNT", "个人客户数", "客户类型", "=", "I"),
    derived("个人客户占比", "ratio", left="个人客户数", right="客户数"),
], None))

# 7. 门店
tables.append((210007, "store", "门店", "TABLE", None, [
    col("CustomerID", "int", "COUNT", "门店数"),
    col("Name", "varchar", "", "门店名称"),
    col("SalesPersonID", "int", "", "负责销售员ID"),
], None))

# 8. 销售区域
tables.append((210008, "salesterritory", "销售区域", "TABLE", None, [
    col("TerritoryID", "int", "COUNT", "区域数"),
    col("Name", "varchar", "", "区域名称"),
    col("CountryRegionCode", "varchar", "", "国家代码"),
    col("Group", "varchar", "", "大区"),
    col("SalesYTD", "double", "SUM", "区域年度销售额"),
    col("SalesLastYear", "double", "SUM", "区域去年销售额"),
], None))

# 9. 销售员
tables.append((210009, "salesperson", "销售员", "TABLE", None, [
    col("SalesPersonID", "int", "COUNT", "销售员数"),
    col("SalesQuota", "double", "AVG", "平均销售配额"),
    col("SalesYTD", "double", "SUM", "销售员年度销售额"),
    col("Bonus", "double", "SUM", "奖金合计"),
    col("CommissionPct", "double", "AVG", "平均佣金率"),
    col("TerritoryID", "int", "", "销售区域ID"),
], None))

# 10. 促销
tables.append((210010, "specialoffer", "促销活动", "TABLE", None, [
    col("SpecialOfferID", "int", "COUNT", "促销数"),
    col("Description", "varchar", "", "促销描述"),
    col("DiscountPct", "double", "AVG", "平均折扣率"),
    col("Type", "varchar", "", "促销类型"),
    col("Category", "varchar", "", "促销类别"),
    col("MinQty", "int", "AVG", "最低数量"),
    col("MaxQty", "int", "AVG", "最高数量"),
    col("StartDate", "datetime", "", "开始时间"),
    col("EndDate", "datetime", "", "结束时间"),
], None))

# 11. 库存
tables.append((210011, "productinventory", "产品库存", "TABLE", None, [
    col("ProductID", "int", "COUNT", "库存记录数"),
    col("Quantity", "smallint", "SUM", "库存总量"),
    col("Quantity", "smallint", "AVG", "平均库存"),
    col("LocationID", "smallint", "", "库位ID"),
], None))

# ── 表关联（星型/雪花）──
joins = [
    {"id":1,"left":"210002","right":"210001","leftCol":"SalesOrderID","rightCol":"SalesOrderID","type":"INNER JOIN"},
    {"id":2,"left":"210002","right":"210003","leftCol":"ProductID","rightCol":"ProductID","type":"INNER JOIN"},
    {"id":3,"left":"210003","right":"210004","leftCol":"ProductSubcategoryID","rightCol":"ProductSubcategoryID","type":"LEFT JOIN"},
    {"id":4,"left":"210004","right":"210005","leftCol":"ProductCategoryID","rightCol":"ProductCategoryID","type":"LEFT JOIN"},
    {"id":5,"left":"210001","right":"210006","leftCol":"CustomerID","rightCol":"CustomerID","type":"INNER JOIN"},
    {"id":6,"left":"210006","right":"210008","leftCol":"TerritoryID","rightCol":"TerritoryID","type":"LEFT JOIN"},
    {"id":7,"left":"210001","right":"210009","leftCol":"SalesPersonID","rightCol":"SalesPersonID","type":"LEFT JOIN"},
    {"id":8,"left":"210009","right":"210008","leftCol":"TerritoryID","rightCol":"TerritoryID","type":"LEFT JOIN"},
    {"id":9,"left":"210002","right":"210010","leftCol":"SpecialOfferID","rightCol":"SpecialOfferID","type":"LEFT JOIN"},
    {"id":10,"left":"210003","right":"210011","leftCol":"ProductID","rightCol":"ProductID","type":"LEFT JOIN"},
    {"id":11,"left":"210006","right":"210007","leftCol":"CustomerID","rightCol":"CustomerID","type":"LEFT JOIN"},
]

perm = {"v":1,"isPublic":False,"grantRoles":["super_admin","分析师"],"grantUsers":[],
        "unrestrictedRoles":["super_admin"],"rowRules":[],"colRules":[]}

print("tables:", len(tables), "joins:", len(joins))

# 清理旧数据
mysql(f"DELETE FROM ask_domain_table WHERE domain_id={DOMAIN_ID}")
mysql(f"DELETE FROM ask_domain WHERE id={DOMAIN_ID}")

# ── 业务术语 ──
mysql(f"DELETE FROM ask_term WHERE domain_id={DOMAIN_ID}")
terms = [
    ("下单时间", ["订单日期", "下单日期"], "订单创建日期，销售分析的主时间轴", "time"),
    ("发货时间", ["发货日期"], "订单实际发货日期", "time"),
    ("销售区域", ["区域"], "按销售区域划分，含国家与大区", "none"),
    ("客户类型", ["客户类别"], "S=门店（经销商），I=个人客户", "none"),
    ("产品大类", ["大类", "品类"], "Bikes/Components/Clothing/Accessories", "none"),
    ("产品线", ["产品系列"], "R=公路车 S=山地车 M=童车 T=旅行车", "none"),
    ("促销类型", ["折扣类型"], "No Discount / Volume Discount / Seasonal Discount 等", "none"),
]
for i, (name, syn, desc, kind) in enumerate(terms):
    mysql(f"""INSERT INTO ask_term (domain_id, name, synonyms_json, description, apply_kind, sort_no, created_by)
VALUES ({DOMAIN_ID}, '{name}', '{esc(json.dumps(syn, ensure_ascii=False))}', '{desc}', '{kind}', {i}, 'demo')""")

mysql(f"""INSERT INTO ask_domain (id, name, description, datasource_id, status, mode, joins_json, perm_json, sort_no, created_by)
VALUES ({DOMAIN_ID}, 'AdventureWorks 销售分析域',
'基于 Microsoft AdventureWorks 示例库的真实销售业务：订单、明细、产品层级、客户、区域、销售员、促销、库存',
140, 'enabled', 'MODELED', '{esc(json.dumps(joins, ensure_ascii=False))}',
'{esc(json.dumps(perm, ensure_ascii=False))}', 0, 'demo')""")

for tid, name, label, mode, sqltext, cols, base in tables:
    cj = esc(json.dumps(cols, ensure_ascii=False))
    st = "NULL" if sqltext is None else f"'{esc(sqltext)}'"
    bf = "NULL" if base is None else f"'{esc(base)}'"
    mysql(f"""INSERT INTO ask_domain_table (id, domain_id, name, label, mode, sql_text, columns_json, enabled, sort_no, created_by, base_filter)
VALUES ({tid}, {DOMAIN_ID}, '{name}', '{label}', '{mode}', {st}, '{cj}', 1, 0, 'demo', {bf})""")
print("domain inserted")

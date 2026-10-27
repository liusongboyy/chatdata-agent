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
tables.append((240001, "salesorderheader", "销售订单", "TABLE", None, [
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
tables.append((240002, "salesorderdetail", "销售订单明细", "TABLE", None, [
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
tables.append((240003, "product", "产品", "TABLE", None, [
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
tables.append((240004, "productsubcategory", "产品子类", "TABLE", None, [
    col("ProductSubcategoryID", "int", "COUNT", "子类数"),
    col("Name", "varchar", "", "子类名称"),
    col("ProductCategoryID", "int", "", "产品大类ID"),
], None))

tables.append((240005, "productcategory", "产品大类", "TABLE", None, [
    col("ProductCategoryID", "int", "COUNT", "大类数"),
    col("Name", "varchar", "", "大类名称"),
], None))

# 6. 客户
tables.append((240006, "customer", "客户", "TABLE", None, [
    col("CustomerID", "int", "COUNT", "客户数"),
    col("CustomerType", "varchar", "", "客户类型"),
    col("TerritoryID", "int", "", "销售区域ID"),
    cond("CustomerID", "int", "COUNT", "门店客户数", "客户类型", "=", "S"),
    cond("CustomerID", "int", "COUNT", "个人客户数", "客户类型", "=", "I"),
    derived("个人客户占比", "ratio", left="个人客户数", right="客户数"),
], None))

# 7. 门店
tables.append((240007, "store", "门店", "TABLE", None, [
    col("CustomerID", "int", "COUNT", "门店数"),
    col("Name", "varchar", "", "门店名称"),
    col("SalesPersonID", "int", "", "负责销售员ID"),
], None))

# 8. 销售区域
tables.append((240008, "salesterritory", "销售区域", "TABLE", None, [
    col("TerritoryID", "int", "COUNT", "区域数"),
    col("Name", "varchar", "", "区域名称"),
    col("CountryRegionCode", "varchar", "", "国家代码"),
    col("Group", "varchar", "", "大区"),
    col("SalesYTD", "double", "SUM", "区域年度销售额"),
    col("SalesLastYear", "double", "SUM", "区域去年销售额"),
], None))

# 9. 销售员
tables.append((240009, "salesperson", "销售员", "TABLE", None, [
    col("SalesPersonID", "int", "COUNT", "销售员数"),
    col("SalesQuota", "double", "AVG", "平均销售配额"),
    col("SalesYTD", "double", "SUM", "销售员年度销售额"),
    col("Bonus", "double", "SUM", "奖金合计"),
    col("CommissionPct", "double", "AVG", "平均佣金率"),
    col("TerritoryID", "int", "", "销售区域ID"),
], None))

# 10. 促销
tables.append((240010, "specialoffer", "促销活动", "TABLE", None, [
    col("SpecialOfferID", "int", "COUNT", "促销数"),
    col("Description", "varchar", "", "促销描述"),
    col("DiscountPct", "double", "AVG", "促销平均折扣率"),
    col("Type", "varchar", "", "促销类型"),
    col("Category", "varchar", "", "促销类别"),
    col("MinQty", "int", "AVG", "最低数量"),
    col("MaxQty", "int", "AVG", "最高数量"),
    col("StartDate", "datetime", "", "开始时间"),
    col("EndDate", "datetime", "", "结束时间"),
], None))

# 11. 库存
tables.append((240011, "productinventory", "产品库存", "TABLE", None, [
    col("ProductID", "int", "COUNT", "库存记录数"),
    col("Quantity", "smallint", "SUM", "库存总量"),
    col("Quantity", "smallint", "AVG", "平均库存"),
    col("LocationID", "smallint", "", "库位ID"),
], None))

# ── 扩展：采购链、员工层级、地理层级、销售归因、BOM ──

# 12. 采购订单
tables.append((240012, "purchaseorderheader", "采购订单", "TABLE", None, [
    col("PurchaseOrderID", "int", "COUNT", "采购单数"),
    col("SubTotal", "double", "SUM", "采购小计"),
    col("SubTotal", "double", "AVG", "平均采购金额"),
    col("TaxAmt", "double", "SUM", "采购税额"),
    col("Freight", "double", "SUM", "采购运费"),
    col("TotalDue", "double", "SUM", "采购应付总额"),
    col("OrderDate", "datetime", "", "采购时间"),
    col("ShipDate", "datetime", "", "采购到货时间"),
    col("Status", "tinyint", "", "采购状态"),
    col("EmployeeID", "int", "", "采购员ID"),
    col("VendorID", "int", "", "供应商ID"),
    col("ShipMethodID", "int", "", "运输方式ID"),
    derived("采购履约时长", "duration", start="采购时间", end="采购到货时间", unit="HOUR"),
], None))

# 13. 采购明细
tables.append((240013, "purchaseorderdetail", "采购明细", "TABLE", None, [
    col("PurchaseOrderDetailID", "int", "COUNT", "采购明细行数"),
    col("OrderQty", "smallint", "SUM", "采购数量"),
    col("UnitPrice", "double", "AVG", "平均采购单价"),
    col("LineTotal", "double", "SUM", "采购行金额"),
    col("ReceivedQty", "smallint", "SUM", "已收货数量"),
    col("RejectedQty", "smallint", "SUM", "退货数量"),
    col("StockedQty", "smallint", "SUM", "入库数量"),
    col("ProductID", "int", "", "产品ID"),
    col("PurchaseOrderID", "int", "", "采购单ID"),
    derived("采购退货率", "ratio", left="退货数量", right="已收货数量"),
], None))

# 14. 供应商（0 行，但结构在——问数会返回空，这本身是要测的）
tables.append((240014, "vendor", "供应商", "TABLE", None, [
    col("VendorID", "int", "COUNT", "供应商数"),
    col("Name", "varchar", "", "供应商名称"),
    col("CreditRating", "tinyint", "AVG", "平均信用评级"),
    col("PreferredVendorStatus", "bit", "", "优先供应商标记"),
    col("ActiveFlag", "bit", "", "是否启用"),
    cond("VendorID", "int", "COUNT", "活跃供应商数", "是否启用", "=", "1"),
], None))

# 15. 员工（自关联：ManagerID → EmployeeID）
tables.append((240015, "employee", "员工", "TABLE", None, [
    col("EmployeeID", "int", "COUNT", "员工数"),
    col("Title", "varchar", "", "职位"),
    col("Gender", "varchar", "", "性别"),
    col("MaritalStatus", "varchar", "", "婚姻状况"),
    col("HireDate", "datetime", "", "入职时间"),
    col("BirthDate", "datetime", "", "出生日期"),
    col("VacationHours", "smallint", "AVG", "平均年假时数"),
    col("SickLeaveHours", "smallint", "AVG", "平均病假时数"),
    col("SalariedFlag", "bit", "", "是否月薪制"),
    col("CurrentFlag", "bit", "", "是否在职"),
    col("ManagerID", "int", "", "主管ID"),
    col("DepartmentID", "int", "", "部门ID"),
    derived("司龄天数", "duration", start="入职时间", end="出生日期", unit="DAY"),
    cond("EmployeeID", "int", "COUNT", "在职员工数", "是否在职", "=", "1"),
    derived("在职率", "ratio", left="在职员工数", right="员工数"),
], None))

# 16. 部门
tables.append((240016, "department", "部门", "TABLE", None, [
    col("DepartmentID", "int", "COUNT", "部门数"),
    col("Name", "varchar", "", "部门名称"),
    col("GroupName", "varchar", "", "部门分组"),
], None))

# 17. 员工部门履历
tables.append((240017, "employeedepartmenthistory", "员工部门履历", "TABLE", None, [
    col("EmployeeID", "int", "COUNT", "履历记录数"),
    col("DepartmentID", "int", "", "部门ID"),
    col("ShiftID", "tinyint", "", "班次ID"),
    col("StartDate", "datetime", "", "开始时间"),
    col("EndDate", "datetime", "", "结束时间"),
], None))

# 18. 地址
tables.append((240018, "address", "地址", "TABLE", None, [
    col("AddressID", "int", "COUNT", "地址数"),
    col("City", "varchar", "", "城市"),
    col("PostalCode", "varchar", "", "邮编"),
    col("StateProvinceID", "int", "", "州省ID"),
], None))

# 19. 州省
tables.append((240019, "stateprovince", "州省", "TABLE", None, [
    col("StateProvinceID", "int", "COUNT", "州省数"),
    col("Name", "varchar", "", "州省名称"),
    col("StateProvinceCode", "varchar", "", "州省代码"),
    col("CountryRegionCode", "varchar", "", "国家代码"),
    col("TerritoryID", "int", "", "销售区域ID"),
], None))

# 20. 国家地区
tables.append((240020, "countryregion", "国家地区", "TABLE", None, [
    col("CountryRegionCode", "varchar", "", "国家代码"),
    col("Name", "varchar", "", "国家名称"),
    col("CountryRegionCode", "varchar", "COUNT", "国家数"),
], None))

# 21. 销售原因
tables.append((240021, "salesreason", "销售原因", "TABLE", None, [
    col("SalesReasonID", "int", "COUNT", "原因类型数"),
    col("Name", "varchar", "", "原因名称"),
    col("ReasonType", "varchar", "", "原因分类"),
], None))

# 22. 订单销售原因（多对多桥接）
tables.append((240022, "salesorderheadersalesreason", "订单销售原因", "TABLE", None, [
    col("SalesOrderID", "int", "COUNT", "归因订单数"),
    col("SalesReasonID", "int", "", "原因ID"),
], None))

# 23. 促销产品（多对多桥接）
tables.append((240023, "specialofferproduct", "促销产品", "TABLE", None, [
    col("SpecialOfferID", "int", "COUNT", "促销产品数"),
    col("ProductID", "int", "", "产品ID"),
], None))

# 24. 产品型号
tables.append((240024, "productmodel", "产品型号", "TABLE", None, [
    col("ProductModelID", "int", "COUNT", "型号数"),
    col("Name", "varchar", "", "型号名称"),
], None))

# 25. 物料清单（BOM：产品由哪些零件构成，自关联）
tables.append((240025, "billofmaterials", "物料清单", "TABLE", None, [
    col("BillOfMaterialsID", "int", "COUNT", "BOM 行数"),
    col("ProductAssemblyID", "int", "", "装配产品ID"),
    col("ComponentID", "int", "", "组件产品ID"),
    col("BOMLevel", "smallint", "AVG", "平均 BOM 层级"),
    col("PerAssemblyQty", "decimal", "SUM", "单件用量合计"),
    col("StartDate", "datetime", "", "生效时间"),
    col("EndDate", "datetime", "", "失效时间"),
], None))

# 26. 信用卡
tables.append((240026, "creditcard", "信用卡", "TABLE", None, [
    col("CreditCardID", "int", "COUNT", "信用卡数"),
    col("CardType", "varchar", "", "卡类型"),
    col("ExpYear", "smallint", "AVG", "平均到期年"),
], None))

# 27. 运输方式
tables.append((240027, "shipmethod", "运输方式", "TABLE", None, [
    col("ShipMethodID", "int", "COUNT", "运输方式数"),
    col("Name", "varchar", "", "运输方式名称"),
    col("ShipBase", "double", "AVG", "平均基础运费"),
    col("ShipRate", "double", "AVG", "平均运费费率"),
], None))

# 28. 销售税率
tables.append((240028, "salestaxrate", "销售税率", "TABLE", None, [
    col("SalesTaxRateID", "int", "COUNT", "税率条数"),
    col("Name", "varchar", "", "税率名称"),
    col("TaxType", "tinyint", "", "税种"),
    col("TaxRate", "double", "AVG", "平均税率"),
    col("StateProvinceID", "int", "", "州省ID"),
], None))

# ── 表关联（星型/雪花）──
joins = [
    {"id":1,"left":"240002","right":"240001","leftCol":"SalesOrderID","rightCol":"SalesOrderID","type":"INNER JOIN"},
    {"id":2,"left":"240002","right":"240003","leftCol":"ProductID","rightCol":"ProductID","type":"INNER JOIN"},
    {"id":3,"left":"240003","right":"240004","leftCol":"ProductSubcategoryID","rightCol":"ProductSubcategoryID","type":"LEFT JOIN"},
    {"id":4,"left":"240004","right":"240005","leftCol":"ProductCategoryID","rightCol":"ProductCategoryID","type":"LEFT JOIN"},
    {"id":5,"left":"240001","right":"240006","leftCol":"CustomerID","rightCol":"CustomerID","type":"INNER JOIN"},
    {"id":6,"left":"240006","right":"240008","leftCol":"TerritoryID","rightCol":"TerritoryID","type":"LEFT JOIN"},
    {"id":7,"left":"240001","right":"240009","leftCol":"SalesPersonID","rightCol":"SalesPersonID","type":"LEFT JOIN"},
    {"id":8,"left":"240009","right":"240008","leftCol":"TerritoryID","rightCol":"TerritoryID","type":"LEFT JOIN"},
    {"id":9,"left":"240002","right":"240010","leftCol":"SpecialOfferID","rightCol":"SpecialOfferID","type":"LEFT JOIN"},
    {"id":10,"left":"240003","right":"240011","leftCol":"ProductID","rightCol":"ProductID","type":"LEFT JOIN"},
    {"id":11,"left":"240006","right":"240007","leftCol":"CustomerID","rightCol":"CustomerID","type":"LEFT JOIN"},
    # 采购链
    {"id":12,"left":"240013","right":"240012","leftCol":"PurchaseOrderID","rightCol":"PurchaseOrderID","type":"INNER JOIN"},
    {"id":13,"left":"240013","right":"240003","leftCol":"ProductID","rightCol":"ProductID","type":"INNER JOIN"},
    {"id":14,"left":"240012","right":"240014","leftCol":"VendorID","rightCol":"VendorID","type":"LEFT JOIN"},
    {"id":15,"left":"240012","right":"240015","leftCol":"EmployeeID","rightCol":"EmployeeID","type":"LEFT JOIN"},
    {"id":16,"left":"240012","right":"240027","leftCol":"ShipMethodID","rightCol":"ShipMethodID","type":"LEFT JOIN"},
    # 员工层级与部门
    {"id":17,"left":"240017","right":"240015","leftCol":"EmployeeID","rightCol":"EmployeeID","type":"INNER JOIN"},
    {"id":18,"left":"240017","right":"240016","leftCol":"DepartmentID","rightCol":"DepartmentID","type":"INNER JOIN"},
    # 地理层级：地址 → 州省 → 国家
    {"id":19,"left":"240018","right":"240019","leftCol":"StateProvinceID","rightCol":"StateProvinceID","type":"LEFT JOIN"},
    {"id":20,"left":"240019","right":"240020","leftCol":"CountryRegionCode","rightCol":"CountryRegionCode","type":"LEFT JOIN"},
    {"id":21,"left":"240019","right":"240008","leftCol":"TerritoryID","rightCol":"TerritoryID","type":"LEFT JOIN"},
    # 销售归因（多对多）
    {"id":22,"left":"240022","right":"240001","leftCol":"SalesOrderID","rightCol":"SalesOrderID","type":"INNER JOIN"},
    {"id":23,"left":"240022","right":"240021","leftCol":"SalesReasonID","rightCol":"SalesReasonID","type":"INNER JOIN"},
    # 促销 ↔ 产品（多对多）
    {"id":24,"left":"240023","right":"240010","leftCol":"SpecialOfferID","rightCol":"SpecialOfferID","type":"INNER JOIN"},
    {"id":25,"left":"240023","right":"240003","leftCol":"ProductID","rightCol":"ProductID","type":"INNER JOIN"},
    # 产品型号
    {"id":26,"left":"240003","right":"240024","leftCol":"ProductModelID","rightCol":"ProductModelID","type":"LEFT JOIN"},
    # BOM：产品自关联
    {"id":27,"left":"240025","right":"240003","leftCol":"ProductAssemblyID","rightCol":"ProductID","type":"INNER JOIN"},
    # 税率 → 州省
    {"id":28,"left":"240028","right":"240019","leftCol":"StateProvinceID","rightCol":"StateProvinceID","type":"LEFT JOIN"},
    # 订单信用卡
    {"id":29,"left":"240001","right":"240026","leftCol":"CreditCardID","rightCol":"CreditCardID","type":"LEFT JOIN"},
    # 订单运输方式
    {"id":30,"left":"240001","right":"240027","leftCol":"ShipMethodID","rightCol":"ShipMethodID","type":"LEFT JOIN"},
]

perm = {"v":1,"isPublic":False,"grantRoles":["super_admin","分析师"],"grantUsers":[],
        "unrestrictedRoles":["super_admin"],"rowRules":[],"colRules":[]}

print("tables:", len(tables), "joins:", len(joins))

# 清理旧数据
# 幂等：按域清干净。**不能按 id 清**——id 是手工分配的，
# 改过表清单之后旧 id 会残留，再插入就撞主键
mysql(f"DELETE FROM ask_domain_table WHERE domain_id={DOMAIN_ID}")
mysql(f"DELETE FROM ask_term WHERE domain_id={DOMAIN_ID}")
mysql(f"DELETE FROM ask_domain WHERE id={DOMAIN_ID}")

# ── 业务术语 ──
mysql(f"DELETE FROM ask_term WHERE domain_id={DOMAIN_ID}")
# **术语名必须是真实存在的列别名**——它是指向，不是新造的名字。
# 一开始我把「产品大类」「销售区域」当术语名，但列里叫「大类名称」「区域名称」，
# 于是模型按术语名去找字段，报"找不到字段"。用户的说法才该放近义词里
terms = [
    ("下单时间", ["订单日期", "下单日期"], "订单创建日期，销售分析的主时间轴", "time"),
    ("发货时间", ["发货日期"], "订单实际发货日期", "time"),
    ("区域名称", ["区域", "销售区域"], "按销售区域划分，含国家与大区", "none"),
    ("客户类型", ["客户类别"], "S=门店（经销商），I=个人客户", "none"),
    ("大类名称", ["大类", "品类", "产品大类"], "Bikes/Components/Clothing/Accessories", "none"),
    ("子类名称", ["子类", "产品子类"], "产品的第二层分类", "none"),
    ("产品线", ["产品系列"], "R=公路车 S=山地车 M=童车 T=旅行车", "none"),
    ("促销类型", ["折扣类型"], "No Discount / Volume Discount / Seasonal Discount 等", "none"),
    ("部门名称", ["部门"], "员工所属部门", "none"),
    ("供应商名称", ["供应商"], "外部供应商", "none"),
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

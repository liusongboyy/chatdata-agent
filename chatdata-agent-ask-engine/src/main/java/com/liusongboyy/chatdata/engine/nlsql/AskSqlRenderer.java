package com.liusongboyy.chatdata.engine.nlsql;

import com.liusongboyy.chatdata.engine.datasource.dialect.SqlDialect;
import com.liusongboyy.chatdata.engine.permission.PermissionDecision;
import com.liusongboyy.chatdata.engine.permission.RowScope;
import com.liusongboyy.chatdata.engine.permission.SqlPredicate;
import com.liusongboyy.chatdata.engine.semantic.FieldRef;
import com.liusongboyy.chatdata.engine.semantic.JoinPlanner;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.DerivedMetric;
import com.liusongboyy.chatdata.engine.semantic.MetricRef;
import com.liusongboyy.chatdata.engine.semantic.SemanticException;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import com.liusongboyy.chatdata.engine.semantic.TableJoin;
import com.liusongboyy.chatdata.engine.semantic.Term;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把问数 DSL 渲染成 SQL。
 *
 * <h2>权限是**构造的一部分**，不是后处理</h2>
 *
 * <p>这是本项目与「模型直接写 SQL」路线的根本差别。我们自己写 WHERE 子句，
 * 所以行规则谓词是**拼进去**的，不需要"解析 SQL → 找注入点 → 改 AST"。
 * ADR-0002 评估过后者（用 JSqlParser 注入谓词）并放弃，理由是
 * "要处理 CTE、UNION、窗口函数等，正确性风险高于收益"。
 *
 * <h2>脱敏在查询前，不是在查询后</h2>
 *
 * <p>{@code PermissionDecision.Allowed.maskedColumns()} 给出的列**不进 SELECT**——
 * 直接渲染成常量 {@code '***'}。查询后再打码意味着敏感数据已经出库、经过网络与内存，
 * 审计上说不清；查询前处理则数据根本不出现。
 *
 * <h2>最小版本的边界</h2>
 *
 * <p>只支持**单表**。多表 join 需要 {@code joinsJson} 的解释与表选择逻辑，
 * 留给后续版本。域内有多张可见表时，本渲染器按 DSL 里字段所属的表选择唯一一张；
 * 若字段跨表则抛异常（而不是拼一个自己都没验证过的 join）。
 */
public final class AskSqlRenderer {

    /** 超过这个数量的明细行数没有意义，默认加 LIMIT。 */
    private static final int DEFAULT_LIMIT = 1000;

    private AskSqlRenderer() {
    }

    /**
     * 渲染。
     *
     * @param dsl        问数 DSL
     * @param model      语义模型（域内字段解析）
     * @param dialect    SQL 方言
     * @param decision   权限判定结果，**必须是 Allowed**——Denied 的请求不该走到渲染
     * @param today      参考日期，用于解析「上个月」这类相对时间。传入以便测试
     */
    public static RenderedSql render(AskDsl dsl,
                                     SemanticModel model,
                                     SqlDialect dialect,
                                     PermissionDecision decision,
                                     LocalDate today) {

        if (!(decision instanceof PermissionDecision.Allowed allowed)) {
            // 渲染器不接受 Denied：能走到这里说明调用方漏了权限检查。
            // 抛异常比"渲染出一个不带权限的 SQL"安全得多。
            throw new IllegalArgumentException(
                    "渲染前必须先通过权限判定。当前判定结果是拒绝，不应进入渲染阶段");
        }

        if (dsl == null || dsl.isEmpty()) {
            throw new IllegalArgumentException("DSL 为空，无法渲染");
        }

        // ① 解析字段 → 确定涉及哪些表
        List<MetricRef> metricRefs = dsl.metrics().stream()
                .map(model::resolveMetricRef)
                .toList();
        List<FieldRef> dimRefs = dsl.dimensions().stream()
                .map(model::resolveDimension)
                .toList();

        Set<String> involvedAliases = new LinkedHashSet<>();
        for (MetricRef m : metricRefs) {
            involvedAliases.addAll(aliasesOf(m));
        }
        dimRefs.forEach(r -> involvedAliases.add(r.tableAlias()));

        // ② 过滤条件里的字段也要参与"涉及哪些表"的判断
        List<ResolvedFilter> filters = new ArrayList<>();
        for (AskDsl.Filter f : dsl.filters()) {
            FieldRef ref = model.resolveDimension(f.field());
            involvedAliases.add(ref.tableAlias());
            // 与字段比较时右侧也是列：它所在的表同样要参与 JOIN 规划，
            // 否则就是"条件里的列不在 FROM 里"
            FieldRef right = null;
            if (f.comparesWithField()) {
                right = model.resolveDimension(f.rightField());
                involvedAliases.add(right.tableAlias());
            }
            filters.add(new ResolvedFilter(ref, f.field(), f.op(), f.value(), right));
        }

        // ③ 收集用到的**表 id**。别名是渲染期分配的，而 join 配置里存的是表 id，
        //    故这里要换回 id 才能规划 join 路径
        Set<String> involvedTableIds = new LinkedHashSet<>();
        for (String alias : involvedAliases) {
            involvedTableIds.add(model.tableIdOfAlias(alias));
        }
        if (involvedTableIds.isEmpty()) {
            throw new IllegalArgumentException("DSL 没有解析出任何字段");
        }

        // ③.5 **把行规则引用的表也拉进来。**
        //
        // 星型模型里事实表只有 store_id，组织层级在维表上——行规则只能配在维表上。
        // 但用户问「订单金额」时 DSL 只涉及 f_order，维表不在候选里，
        // 于是谓词里的 org_path 引用的别名根本没有出现在 FROM 中，
        // 执行时报 Unknown column。
        //
        // 语义建模层面这本来是**正确**的做法：一条行规则配在维表上，
        // 所有事实表自动受约束。缺的就是这一步。
        involvedTableIds.addAll(permissionTableIds(allowed.rowScope(), model));

        // ④ 规划 join 路径。连不通或必须反向遍历 LEFT 边时在这里失败。
        //    preferredRoot = 第一个指标所在的表：INNER 场景下根的选择不影响语义，
        //    但以事实表为根生成的 SQL 读起来自然（FROM 事实表 JOIN 维表），排查时人要读它。
        //    若指标为空（纯维度查询）则退化为无偏好，仍按 id 排序保证确定性。
        String preferredRoot = metricRefs.isEmpty()
                ? null
                : model.tableIdOfAlias(aliasesOf(metricRefs.get(0)).iterator().next());

        JoinPlanner.JoinPlan plan = JoinPlanner.plan(model.domain(), involvedTableIds, preferredRoot);
        String alias = model.aliasOfTable(plan.rootTableId());

        // ④ 时间范围与时间列。同环比要在 SELECT 里用它们，故比 WHERE 提前解析。
        //    resolveTime 可能返回 null（口径词解析不出来），此时没有任何时间条件
        ResolvedTime rt = dsl.timeRange() == null ? null : resolveTime(dsl.timeRange(), today);
        String timeCol = rt == null
                ? null
                : resolveTimeColumn(model, dialect, involvedTableIds, dsl.timeRange());

        AskDsl.Compare compare = dsl.compare();
        ResolvedTime prevRt = null;
        // 把**对比期**的行平移到本期时间轴所用的单位与数量。没有分组时用不到
        String shiftUnit = null;
        int shiftAmount = 0;
        if (compare != null) {
            if (rt == null || timeCol == null) {
                // 没有时间范围就无从"与上期比"。猜一个（如减去一年）会给出一个
                // 用户没问过的对比，而它看起来完全正常
                throw new IllegalArgumentException(
                        "同环比需要明确的时间范围（如「今年」「上季度」「2025 年」），当前问题没有给出");
            }
            switch (compare.mode()) {
                case YOY -> {
                    prevRt = new ResolvedTime(
                            rt.startInclusive().minusYears(1), rt.endExclusive().minusYears(1));
                    shiftUnit = "YEAR";
                    shiftAmount = 1;
                }
                case MOM -> {
                    prevRt = new ResolvedTime(
                            rt.startInclusive().minusMonths(1), rt.endExclusive().minusMonths(1));
                    shiftUnit = "MONTH";
                    shiftAmount = 1;
                }
                case CUSTOM -> {
                    TimeRangeResolver.ResolvedRange baseline = TimeRangeResolver.resolve(
                            null, compare.start(), compare.end(), today);
                    if (baseline == null) {
                        throw new IllegalArgumentException(
                                "自定义对比期解析失败：" + compare.start() + " ~ " + compare.end());
                    }
                    prevRt = new ResolvedTime(
                            baseline.startInclusive(), baseline.endExclusive());

                    if (dimRefs.stream().anyMatch(model::isTemporal)) {
                        // 分组键要对齐，就得知道"把对比期平移多久才落到本期"。
                        // 优先按整月，其次按整天；都不是整数关系就拒绝——
                        // 近似出来的对齐会让两组数据错位合并，且不报错
                        long months = java.time.temporal.ChronoUnit.MONTHS.between(
                                prevRt.startInclusive(), rt.startInclusive());
                        if (prevRt.startInclusive().plusMonths(months).equals(rt.startInclusive())
                                && prevRt.endExclusive().plusMonths(months).equals(rt.endExclusive())) {
                            shiftUnit = "MONTH";
                            shiftAmount = Math.toIntExact(months);
                        } else {
                            long days = java.time.temporal.ChronoUnit.DAYS.between(
                                    prevRt.startInclusive(), rt.startInclusive());
                            if (prevRt.startInclusive().plusDays(days).equals(rt.startInclusive())
                                    && prevRt.endExclusive().plusDays(days).equals(rt.endExclusive())) {
                                shiftUnit = "DAY";
                                shiftAmount = Math.toIntExact(days);
                            } else {
                                throw new IllegalArgumentException(
                                        "对比期（" + compare.start() + " ~ " + compare.end()
                                                + "）与本期长度或起止相位不同，无法按分组对齐。"
                                                + "请改用长度相同的两段——整年对整年、整月对整月");
                            }
                        }
                    }
                }
            }

            // 没有时间维度时，粒度与平移都无关（就是"本期总额 vs 对比期总额"），
            // 这条限制只在真的按时间分组时才有意义
            boolean hasTemporalDim = dimRefs.stream().anyMatch(model::isTemporal);
            if (hasTemporalDim && dsl.granularity() == AskDsl.Granularity.DAY
                    && shiftUnit != null && !"DAY".equals(shiftUnit)) {
                throw new IllegalArgumentException(
                        "按天分组时只能做**按天平移**的对比：按" + ("YEAR".equals(shiftUnit) ? "年" : "月")
                                + "平移会把不同的天并进同一组（月末 31 日 → 30 日、闰年 2 月 29 日），"
                                + "增长率会静默算错。请按月或按年分组，或改用相差整天的两段对比期");
            }
        }

        // ⑤ SELECT 子句。脱敏列渲染成常量，数据不出库
        Set<String> masked = allowed.maskedColumns();
        List<RenderedSql.OutputColumn> outColumns = new ArrayList<>();
        List<String> selectParts = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        // 维度表达式**只在这里算一次**，SELECT 与 GROUP BY 共用同一份。
        // 分开算的话，按粒度截断时会出现 SELECT 里是 DATE_FORMAT(...)、
        // GROUP BY 里却是原始列——MySQL 容忍这种写法，于是它不会报错，
        // 只是分组口径与显示口径不再一致
        List<String> dimExprs = new ArrayList<>();
        // 每个维度表达式里如果有占位符，记下对应的参数值。
        // 同一个表达式会出现在 SELECT 与 GROUP BY **两处**，参数必须跟着各追加一次
        List<LocalDate> dimShiftParams = new ArrayList<>();
        for (int i = 0; i < dimRefs.size(); i++) {
            FieldRef r = dimRefs.get(i);
            String name = dsl.dimensions().get(i);
            // 必须用 r.tableAlias()——该列自己所属的表，**不是**根表的别名。
            // 单表时两者恒等，多表时用根别名会指向一张没有这个列的表
            String expr = dialect.quoteQualified(r.tableAlias() + "." + r.column());
            LocalDate shiftParam = null;
            if (compare != null && model.isTemporal(r)) {
                // 把上一期的行平移到本期：2024-03 → 2025-03，两者才落在同一组。
                // 判据用 timeCol 而不是这一列本身——窗口是由 timeCol 划定的，
                // 维度列可能是另一个日期字段
                String shifted = dialect.addInterval(shiftUnit, expr, shiftAmount);
                expr = "CASE WHEN " + timeCol + " < ? THEN " + shifted + " ELSE " + expr + " END";
                shiftParam = rt.startInclusive();
                params.add(shiftParam);
                expr = dialect.bucket(expr, dsl.granularity().name().toLowerCase(Locale.ROOT));
            } else {
                expr = bucketIfTemporal(expr, r, model, dialect, dsl.granularity());
            }
            dimExprs.add(expr);
            dimShiftParams.add(shiftParam);
            addColumn(selectParts, outColumns, dialect, name, expr, false);
        }

        // 占比：必须是本次查询的指标，否则模型会凭空要一个没算的占比
        List<String> shareOf = dsl.shareOf();
        for (String shareName : shareOf) {
            if (!dsl.metrics().contains(shareName)) {
                throw new SemanticException(SemanticException.Reason.FIELD_NOT_FOUND,
                        "shareOf 里的「" + shareName + "」不是本次查询的指标。"
                                + "可用的指标：" + dsl.metrics());
            }
        }
        if (!shareOf.isEmpty() && metricRefs.isEmpty()) {
            throw new IllegalArgumentException("shareOf 需要至少一个指标");
        }

        for (int i = 0; i < metricRefs.size(); i++) {
            MetricRef m = metricRefs.get(i);
            String name = dsl.metrics().get(i);
            boolean maskedOut = isMetricMasked(m, masked);

            // 同环比时每个指标都要按窗口过滤，故把"窗口条件"做成包装器：
            // 每包一次就把两个参数追加一次，保证占位符与参数一一对应
            ColumnWrapper curWrap = IDENTITY;
            ColumnWrapper prevWrap = IDENTITY;
            // prevRt 在上面的 if 里赋值，捕获进 lambda 前先固定下来
            final ResolvedTime prevRange = prevRt;
            if (compare != null) {
                String curCond = timeCol + " >= ? AND " + timeCol + " < ?";
                String prevCond = timeCol + " >= ? AND " + timeCol + " < ?";
                curWrap = p -> {
                    p.add(rt.startInclusive());
                    p.add(rt.endExclusive());
                    return col -> "CASE WHEN " + curCond + " THEN " + col + " END";
                };
                prevWrap = p -> {
                    p.add(prevRange.startInclusive());
                    p.add(prevRange.endExclusive());
                    return col -> "CASE WHEN " + prevCond + " THEN " + col + " END";
                };
            }

            if (compare == null) {
                addColumn(selectParts, outColumns, dialect, name,
                        maskedOut ? "'***'" : renderMetric(m, dialect, IDENTITY, params), maskedOut);
            } else {
                // 本期 / 上期 / 增长率。只给增长率看不出量级，只给两期又要用户自己算
                String curExpr = maskedOut ? "'***'" : renderMetric(m, dialect, curWrap, params);
                String prevExpr = maskedOut ? "'***'" : renderMetric(m, dialect, prevWrap, params);
                String growthExpr = maskedOut ? "'***'"
                        : "(" + renderMetric(m, dialect, curWrap, params)
                        + " - " + renderMetric(m, dialect, prevWrap, params) + ") / NULLIF("
                        + renderMetric(m, dialect, prevWrap, params) + ", 0)";
                addColumn(selectParts, outColumns, dialect, name, curExpr, maskedOut);
                addColumn(selectParts, outColumns, dialect, name + "上期", prevExpr, maskedOut);
                addColumn(selectParts, outColumns, dialect, name + compare.label(), growthExpr, maskedOut);
            }

            if (shareOf.contains(name)) {
                // 占比相对**本次结果的总和**：SUM(expr) OVER () 是在聚合结果上再开窗，
                // 五种库都支持；换成自连接求总和会把权限谓词再复制一遍，更容易出错
                String shareName = name + "占比";
                String shareExpr;
                if (maskedOut) {
                    // 与派生指标同理：占比能反推出被脱敏指标的相对分布，必须一起脱敏
                    shareExpr = "'***'";
                } else {
                    ColumnWrapper w = compare == null ? IDENTITY : curWrap;
                    shareExpr = "(" + renderMetric(m, dialect, w, params) + ") / NULLIF(SUM("
                            + renderMetric(m, dialect, w, params) + ") OVER (), 0)";
                }
                addColumn(selectParts, outColumns, dialect, shareName, shareExpr, maskedOut);
            }
        }

        // 输出列名必须唯一：指标名、占比、上期、同比都是用户要看的列，
        // 撞名时 MySQL 只保留一个，另一个静默消失
        Set<String> columnNames = new java.util.HashSet<>();
        for (RenderedSql.OutputColumn c : outColumns) {
            if (!columnNames.add(c.name())) {
                throw new IllegalArgumentException(
                        "输出列名重复：「" + c.name() + "」。请检查指标名是否与「占比」「上期」「同比」等后缀冲突");
            }
        }

        if (selectParts.isEmpty()) {
            throw new IllegalArgumentException("DSL 没有可渲染的输出列");
        }

        // 行数上限：DSL 的 limit 只能**收紧**，不能突破系统上限
        int rowLimit = dsl.limit() == null ? DEFAULT_LIMIT : Math.min(dsl.limit(), DEFAULT_LIMIT);

        // ⑥ FROM + JOIN
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(dialect.limitPrefix(rowLimit));
        sql.append(String.join(", ", selectParts));
        appendFromAndJoins(sql, plan, model, dialect);

        // ⑥ WHERE
        List<String> whereParts = new ArrayList<>();

        // 6a. 时间条件 —— 左闭右开，见 TimeRangeResolver 的说明。
        //     同环比要**同时**取本期与上期，故两个区间用 OR 连起来
        if (rt != null) {
            if (compare == null) {
                whereParts.add(timeCol + " >= ?");
                params.add(rt.startInclusive());
                whereParts.add(timeCol + " < ?");
                params.add(rt.endExclusive());
            } else {
                whereParts.add("((" + timeCol + " >= ? AND " + timeCol + " < ?) OR ("
                        + timeCol + " >= ? AND " + timeCol + " < ?))");
                params.add(rt.startInclusive());
                params.add(rt.endExclusive());
                params.add(prevRt.startInclusive());
                params.add(prevRt.endExclusive());
            }
        }

        // 6b. 筛选条件
        for (ResolvedFilter f : filters) {
            String col = dialect.quoteQualified(f.ref().tableAlias() + "." + f.ref().column());
            String op = normalizeFilterOp(f.op());

            if (f.right() != null) {
                // 列与列比较：两侧都是列引用，没有取值也就不产生参数。
                // 不在这里做类型校验——库自己会报，而我们没有可靠的类型信息
                whereParts.add(col + " " + op + " "
                        + dialect.quoteQualified(f.right().tableAlias() + "." + f.right().column()));
                continue;
            }

            // 空值判断：**没有取值**，也不该产生参数。
            // `col = NULL` 永远为假，所以必须走 IS NULL 而不是通用的 `col op ?`
            if ("IS NULL".equals(op) || "IS NOT NULL".equals(op)) {
                whereParts.add(col + " " + op);
                continue;
            }

            boolean temporal = model.isTemporal(f.ref());

            if ("IN".equals(op)) {
                // **`IN` 不能走 `col IN ?`。**那是非法 SQL（`IN` 后面必须是括号列表），
                // 而 DSL 里的 value 是一个字符串——多个取值挤在里面。
                // 这条路径此前和 `=` 共用一行代码，所以直到模型第一次产出 `in` 才暴露。
                List<String> values = splitInValues(f.value());
                for (String v : values) {
                    params.add(temporal ? temporalValue(f, v) : v);
                }
                whereParts.add(col + " IN (" + String.join(", ", Collections.nCopies(values.size(), "?")) + ")");
            } else {
                whereParts.add(col + " " + op + " ?");
                params.add(temporal ? temporalValue(f, f.value()) : f.value());
            }
        }

        // 6c. **行级权限谓词** —— 这是权限真正生效的地方
        appendRowScope(whereParts, params, allowed.rowScope(), model, dialect);

        // 6d. **表的默认过滤** —— "什么算有效数据"的业务口径。
        //
        // 实测反复出现同一类错：问「2025 年保费合计」，模型自己补上
        // `AND status = '生效'`（它觉得过期的保单不算保费）。那个判断不算离谱，
        // 但它是**猜的**——SQL 合法、数字合理，只是少了三分之一，而没人知道。
        //
        // 配在建模里之后，模型不必猜；这里固定拼进 WHERE，每次都对。
        //
        // 片段来自管理员配置，不经模型产出，因此没有注入面。但仍然只接受
        // **不含参数的纯条件文本**——带 ? 的片段会打乱参数顺序。
        appendBaseFilters(whereParts, model, plan, dialect);

        if (!whereParts.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", whereParts));
        }

        // ⑦ GROUP BY（有指标时才需要）。用与 SELECT 完全相同的表达式
        if (!dimExprs.isEmpty() && !metricRefs.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", dimExprs));
            // SELECT 里那个占位符的参数已经加过，GROUP BY 是同一表达式第二次出现，
            // 故这里再按顺序补一遍。漏掉这一步的表现是「占位符比参数多一个」——
            // RenderedSql 的构造器会直接拒绝，不会带着错位的参数去执行
            for (LocalDate p : dimShiftParams) {
                if (p != null) {
                    params.add(p);
                }
            }
        }

        // ⑧ ORDER BY。**按输出列名排序**（别名在五种库里都能用），
        // 不能按 DSL 里的原始字段名——占比、同比这些列在 DSL 里没有对应字段
        if (!dsl.sort().isEmpty()) {
            List<String> orderParts = new ArrayList<>();
            for (AskDsl.Sort sort : dsl.sort()) {
                boolean known = outColumns.stream().anyMatch(c -> c.name().equals(sort.field()));
                if (!known) {
                    throw new SemanticException(SemanticException.Reason.FIELD_NOT_FOUND,
                            "排序字段「" + sort.field() + "」不在本次查询的输出里。可用的输出列："
                                    + outColumns.stream().map(RenderedSql.OutputColumn::name).toList());
                }
                orderParts.add(dialect.quoteIdentifier(sort.field())
                        + ("desc".equals(sort.direction()) ? " DESC" : " ASC"));
            }
            sql.append(" ORDER BY ").append(String.join(", ", orderParts));
        }

        // ⑨ 行数限制后缀（SQL Server 为空，它用前缀 TOP）
        String suffix = dialect.limitSuffix(rowLimit);
        if (!suffix.isEmpty()) {
            sql.append(' ').append(suffix);
        }

        return new RenderedSql(sql.toString(), params, outColumns, alias);
    }

    /**
     * 维度是日期列时，按 DSL 的粒度截断。
     *
     * <h2>为什么必须有这一步</h2>
     *
     * <p>实测：问「<strong>按月</strong>看保费趋势」，模型把维度填成「生效日期」、
     * 粒度填成 {@code MONTH}——但若渲染时忽略粒度，SQL 会 {@code GROUP BY 生效日期}
     * 按<strong>天</strong>分组，返回几百个点。**数字都对，只是不是用户要的粒度**，
     * 而用户只能从行数上察觉。
     *
     * <h2>只对日期列截断</h2>
     *
     * <p>对 {@code varchar} 之类的列套 {@code DATE_FORMAT} 会直接报错，
     * 或者更糟——在会隐式转换的库上悄悄得到一堆 {@code NULL}。
     * 故先问语义模型"这列是不是日期"。
     *
     * <p>粒度取值不认识时**不截断**（{@code SqlDialect.bucket} 的约定）：
     * 猜成按月会让「按周看」静默变成「按月看」，比不截断更糟。
     */
    private static String bucketIfTemporal(String expr, FieldRef ref, SemanticModel model,
                                           SqlDialect dialect, AskDsl.Granularity granularity) {
        if (granularity == null || granularity == AskDsl.Granularity.DAY) {
            return expr;
        }
        if (!model.isTemporal(ref)) {
            return expr;
        }
        return dialect.bucket(expr, granularity.name().toLowerCase(Locale.ROOT));
    }

    /** @param name 模型写的原始字段名。报错时要用它——用户认识的是这个名字，不是物理列名 */
    /**
     * 已解析的筛选条件。
     *
     * @param right 与字段比较时的右侧列；与常量比较时为 null
     */
    private record ResolvedFilter(FieldRef ref, String name, String op, String value, FieldRef right) {
    }

    /**
     * 渲染 FROM 子句与所有 JOIN。
     *
     * <p>JOIN 类型由配置决定，**方向不做任何调整**——{@code JoinPlanner} 已经保证了
     * 每一步的 {@code fromTableId} 必然在此前的表集合里，也就是 LEFT JOIN 的左操作数
     * 一定是已经出现过的表。若为了连通而反向走，语义会从 LEFT 变成 RIGHT，
     * 行数跟着变且不报错。
     */
    private static void appendFromAndJoins(StringBuilder sql,
                                           JoinPlanner.JoinPlan plan,
                                           SemanticModel model,
                                           SqlDialect dialect) {
        sql.append(" FROM ");
        appendTableRefWithAlias(sql, plan.rootTableId(), model, dialect);

        for (JoinPlanner.JoinStep step : plan.steps()) {
            TableJoin join = step.join();
            String joinKeyword = join.type() == TableJoin.JoinType.LEFT ? " LEFT JOIN " : " INNER JOIN ";

            sql.append(joinKeyword);
            appendTableRefWithAlias(sql, step.toTableId(), model, dialect);

            String leftAlias = dialect.quoteIdentifier(model.aliasOfTable(join.leftTableId()));
            String rightAlias = dialect.quoteIdentifier(model.aliasOfTable(join.rightTableId()));
            sql.append(" ON ")
                    .append(leftAlias).append('.').append(dialect.quoteIdentifier(join.leftColumn()))
                    .append(" = ")
                    .append(rightAlias).append('.').append(dialect.quoteIdentifier(join.rightColumn()));
        }
    }

    /**
     * 渲染表引用**并**追加渲染期别名。
     *
     * <p>物理表的别名在这里加；虚拟表的别名已由 {@link #appendTableRef} 加过，
     * 不能再加一次——那样就是 {@code (SELECT ...) `t1` `t1`}。
     */
    private static void appendTableRefWithAlias(StringBuilder sql, String tableId,
                                                SemanticModel model, SqlDialect dialect) {
        appendTableRef(sql, tableId, model, dialect);
        RegisteredTable t = model.domain().tableById(tableId).orElseThrow();
        if (t.isPhysical()) {
            sql.append(' ').append(dialect.quoteIdentifier(model.aliasOfTable(tableId)));
        }
    }

    /**
     * 渲染一张表的引用：物理表直接写名字，SQL 虚拟表包成子查询。
     *
     * <p><strong>本方法不负责别名。</strong>调用方统一在引用之后追加渲染期别名
     * （{@code t1}/{@code t2}…）。这里曾给虚拟表顺手加了个 {@code referenceName()} 别名，
     * 于是虚拟表渲染成 {@code (SELECT ...) `ticket` `t1`}——两个连续标识符，
     * MySQL 直接报语法错误。
     *
     * <p>物理表那条路径上加别名看不出来：{@code `ticket` `t1`} 恰好是合法写法，
     * 所以这个 bug 只在虚拟表上暴露，而线上此前没有域使用虚拟表。
     */
    private static void appendTableRef(StringBuilder sql, String tableId,
                                       SemanticModel model, SqlDialect dialect) {
        RegisteredTable t = model.domain().tableById(tableId)
                .orElseThrow(() -> new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                        "join 引用了域内不存在的注册表 id：" + tableId));

        if (t.isPhysical()) {
            sql.append(dialect.quoteIdentifier(t.referenceName()));
        } else {
            // 子查询必须带**自己**的别名：派生表在 MySQL 里不能没有别名。
            // 用渲染期别名而不是 referenceName()——后者会和调用方追加的那个撞车。
            sql.append('(').append(t.sqlText()).append(") ")
                    .append(dialect.quoteIdentifier(model.aliasOfTable(tableId)));
        }
    }

    private record ResolvedTime(LocalDate startInclusive, LocalDate endExclusive) {
    }

    private static ResolvedTime resolveTime(AskDsl.TimeRange tr, LocalDate today) {
        TimeRangeResolver.ResolvedRange r = TimeRangeResolver.resolve(
                tr.kind(), tr.start(), tr.end(), today);
        return r == null ? null : new ResolvedTime(r.startInclusive(), r.endExclusive());
    }

    /** 域内可能被当作时间列的候选别名，按优先级。 */
    private static final List<String> TIME_COLUMN_CANDIDATES =
            List.of("生效日期", "统计日期", "业务日期", "日期", "时间");

    /**
     * 时间条件落在哪个列上。
     *
     * <p>最小版本：按名字在域内找候选时间列，取第一个可解析的。
     *
     * <p>找不到就**抛异常**——绝不能静默丢掉时间条件。丢掉的后果是返回全时段数据，
     * 数字看着正常而用户无从察觉，属于 PRD §12 里说的「静默语义错误」。
     *
     * <p>正式实现应当用**业务术语**（{@code Term} 的 {@code apply_kind=time}）显式声明
     * 时间口径对应哪个列，而不是按名字猜。等 {@code semantic} 包的术语部分落地后替换这里。
     */
    private static String resolveTimeColumn(SemanticModel model, SqlDialect dialect,
                                            Set<String> involvedTableIds, AskDsl.TimeRange tr) {
        // ① **术语显式声明优先。** 管理员在术语上写了 apply_kind=time，
        //    就是明确说了"这个说法落在哪一列"，比按别名猜可靠得多。
        //    实测：域里同时有「生效日期」「报案日期」时，按名字猜会一律落到生效日期，
        //    于是「按报案日期查」得到的是另一段时间的数据——数字看着正常，只是错的。
        for (Term term : model.domain().terms()) {
            if (term.applyKind() != Term.ApplyKind.TIME) {
                continue;
            }
            // 术语名本身就指一个时间列（如「投保时间」→ 生效日期）。
            // 名称在某些域里是"口径名"而非列名，解析不到就跳到下一个
            String col = tryTimeColumn(model, dialect, involvedTableIds, term.name());
            if (col != null) {
                return col;
            }
        }

        // ② 没有声明的，退回按别名猜
        for (String candidate : TIME_COLUMN_CANDIDATES) {
            String col = tryTimeColumn(model, dialect, involvedTableIds, candidate);
            if (col != null) {
                return col;
            }
        }
        throw new SemanticException(SemanticException.Reason.FIELD_NOT_FOUND,
                "问数里带了时间条件（" + tr.kind() + "），但本域内找不到可用的日期列。"
                        + "请在语义建模中把日期列的别名设为「生效日期」「统计日期」等，"
                        + "或把业务术语的应用方式设为「时间口径」并指向要用的日期列");
    }

    /**
     * 试解析一个名字为时间列；不可用返回 null。
     *
     * <p>**时间列必须落在本次参与 FROM 的表上。** 否则渲染出
     * {@code FROM ins_customer WHERE t2.effect_date >= ?}——t2 不在 FROM 里，
     * MySQL 报 Unknown column，用户看到的是"查询失败"而不是"这个域查不了时间"。
     */
    private static String tryTimeColumn(SemanticModel model, SqlDialect dialect,
                                        Set<String> involvedTableIds, String name) {
        try {
            FieldRef r = model.resolveDimension(name);
            if (!involvedTableIds.contains(model.tableIdOfAlias(r.tableAlias()))) {
                return null;
            }
            return dialect.quoteQualified(r.tableAlias() + "." + r.column());
        } catch (SemanticException e) {
            return null;
        }
    }

    /**
     * 把参与本次查询的表上的默认过滤条件拼进 WHERE。
     *
     * <p>只拼**本次真的用到的表**：没参与 FROM 的表拼它的条件会得到
     * {@code Unknown column}，而那个错误离"某张表配了默认过滤"很远。
     *
     * <p>条件里若含占位符 {@code ?} 就拒绝——参数顺序会乱，而且我们没有它的值。
     * 这是管理员手写片段唯一需要防的形态。
     */
    private static void appendBaseFilters(List<String> whereParts, SemanticModel model,
                                          JoinPlanner.JoinPlan plan, SqlDialect dialect) {
        Set<String> used = new LinkedHashSet<>();
        used.add(plan.rootTableId());
        plan.steps().forEach(step -> used.add(step.toTableId()));

        for (String tableId : used) {
            RegisteredTable t = model.domain().tableById(tableId).orElse(null);
            if (t == null || t.baseFilter() == null) {
                continue;
            }
            if (t.baseFilter().contains("?")) {
                throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                        "表「" + t.displayName() + "」的默认过滤条件里不能带占位符："
                                + t.baseFilter());
            }
            // 物理表给条件里的裸列名补上别名——多表时裸列名会歧义或注错表。
            // 虚拟表包在子查询里，条件本来就在子查询作用域内，不加别名
            whereParts.add(t.isPhysical()
                    ? "(" + aliasColumns(t.baseFilter(), model.aliasOfTable(tableId), dialect) + ")"
                    : "(" + t.baseFilter() + ")");
        }
    }

    /**
     * 给条件里的裸列名补上表别名。
     *
     * <p>只处理形如 {@code status = '生效'} 这种简单形态——识别标识符，跳过
     * 字符串字面量、数字、SQL 关键字。**不做完整 SQL 解析**：这是管理员手写的
     * 一小段条件，不是任意 SQL。
     */
    private static String aliasColumns(String condition, String alias, SqlDialect dialect) {
        StringBuilder out = new StringBuilder(condition.length() + 16);
        int i = 0;
        while (i < condition.length()) {
            char c = condition.charAt(i);
            if (c == '\'') {
                // 字符串字面量原样搬运（含转义的单引号）
                int end = i + 1;
                while (end < condition.length()) {
                    if (condition.charAt(end) == '\'') {
                        if (end + 1 < condition.length() && condition.charAt(end + 1) == '\'') {
                            end += 2;
                            continue;
                        }
                        break;
                    }
                    end++;
                }
                out.append(condition, i, Math.min(end + 1, condition.length()));
                i = end + 1;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int end = i;
                while (end < condition.length()
                        && (Character.isLetterOrDigit(condition.charAt(end))
                            || condition.charAt(end) == '_')) {
                    end++;
                }
                String word = condition.substring(i, end);
                if (SQL_KEYWORDS.contains(word.toUpperCase(Locale.ROOT))) {
                    out.append(word);
                } else {
                    // 列名与别名都要按方言加引号——不加的话 Oracle/PostgreSQL
                    // 会折叠大小写，SQL Server 遇到保留字还会直接报错
                    out.append(dialect.quoteQualified(alias + "." + word));
                }
                i = end;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 条件片段里会出现的关键字，不当作列名。 */
    private static final Set<String> SQL_KEYWORDS = Set.of(
            "AND", "OR", "NOT", "IN", "IS", "NULL", "LIKE", "BETWEEN",
            "TRUE", "FALSE", "CASE", "WHEN", "THEN", "ELSE", "END");

    /**
     * 行规则的谓词引用了哪些表。
     *
     * <p>谓词里的列是占位符形式（{@code {col:org_path}}），名字已按白名单校验过，
     * 这里通过语义模型解析成具体表 id。解析不到或有多处匹配时抛异常——
     * 与 {@link #appendRowScope} 的判定同源，不在这里另做一套。
     *
     * <p>只在 {@link RowScope.Restricted} 时有内容：显式全量、隐式全量都不附加谓词，
     * 也就不该把维表拉进来——多 JOIN 一张表会让行数与用户预期不符（INNER 时甚至少行）。
     */
    private static Set<String> permissionTableIds(RowScope scope, SemanticModel model) {
        if (!(scope instanceof RowScope.Restricted restricted)) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String col : restricted.predicate().columnRefs()) {
            ids.add(model.tableIdOfAlias(model.resolveAny(col).tableAlias()));
        }
        return ids;
    }

    /**
     * 把行级权限谓词拼进 WHERE。
     *
     * <p>谓词里的列是**占位符**形式（如 <code>{col:region}</code>），
     * 这里通过语义模型把它解析成「哪张表的哪个列」，再按方言引用成
     * <code>`t1`.`region`</code>。
     *
     * <p><strong>为什么必须经过语义模型，而不是直接用规则里的字段名：</strong>
     * 多表查询下 {@code region} 可能出现在多张表上，不加限定会歧义，
     * 或者注到错误的表上——**权限注错表比查询失败危险得多**。
     * 解析不到或有多处匹配时抛异常，绝不猜。
     */
    private static void appendRowScope(List<String> whereParts, List<Object> params,
                                       RowScope scope, SemanticModel model, SqlDialect dialect) {
        if (scope instanceof RowScope.Restricted restricted) {
            SqlPredicate resolved = restricted.predicate().mapColumns(col -> {
                FieldRef ref = model.resolveAny(col);
                return dialect.quoteQualified(ref.tableAlias() + "." + ref.column());
            });

            if (resolved.hasUnresolvedColumns()) {
                // 占位符没替换干净说明谓词里混进了未校验的列引用，绝不能带着它去执行
                throw new IllegalStateException(
                        "权限谓词里仍有未解析的列占位符：" + resolved.sql());
            }

            whereParts.add("(" + resolved.sql() + ")");
            params.addAll(resolved.params());
        }
        // Unrestricted / ImplicitUnrestricted：不附加谓词。
        // 隐式全量在 PermissionGuard 里已记 WARN 日志，这里不再重复。
        // Denied 到不了这里——PermissionGuard 已经把它转成 Denied 决策了。
    }

    /**
     * 一个指标引用涉及的表别名。
     *
     * <p>派生指标要把所有输入所在的表算上；带条件的指标还要把**条件字段**所在的表算上——
     * 星型模型里状态、渠道这类条件字段通常在维表上，不拉进来就是
     * {@code Unknown column t5.status}。
     */
    private static Set<String> aliasesOf(MetricRef m) {
        Set<String> out = new LinkedHashSet<>();
        collectAliases(m, out);
        return out;
    }

    private static void collectAliases(MetricRef m, Set<String> out) {
        if (m instanceof MetricRef.Column cm) {
            out.add(cm.ref().tableAlias());
            if (cm.condition() != null) {
                for (MetricRef.Term t : cm.condition().terms()) {
                    out.add(t.left().tableAlias());
                    if (t.comparesWithField()) {
                        out.add(t.right().tableAlias());
                    }
                }
            }
            return;
        }
        ((MetricRef.Derived) m).inputs().forEach(input -> collectAliases(input, out));
    }

    /**
     * 把一个已引用的列表达式再包一层。同环比用它加窗口过滤。
     *
     * <p><strong>两段式</strong>：先 {@link #begin} 追加参数，再 {@code apply} 包表达式。
     * 同环比的 CASE 在外层、指标的统计条件在内层，而 SQL 文本里外层占位符在前——
     * 若等包完再追加参数，两边的参数顺序就反了，查询会静默算出错的数。
     */
    @FunctionalInterface
    private interface ColumnWrapper {
        java.util.function.UnaryOperator<String> begin(List<Object> params);
    }

    /** 不包装。绝大多数查询走这一条。 */
    private static final ColumnWrapper IDENTITY = params -> java.util.function.UnaryOperator.identity();

    /** 按指标类型渲染表达式。包装器对普通列与派生指标的每个输入都生效。 */
    private static String renderMetric(MetricRef m, SqlDialect dialect,
                                       ColumnWrapper w, List<Object> params) {
        if (m instanceof MetricRef.Column cm) {
            return renderColumn(cm, dialect, w, params);
        }
        return renderDerived((MetricRef.Derived) m, dialect, w, params);
    }

    /**
     * 一列指标：{@code AGG(包装(CASE 条件 THEN 列 END))}。
     *
     * <p>条件用 CASE 而不是 WHERE：它只作用于**这一列**，
     * 同一条查询里的其它指标、以及分组都不受影响。
     */
    private static String renderColumn(MetricRef.Column cm, SqlDialect dialect,
                                       ColumnWrapper w, List<Object> params) {
        java.util.function.UnaryOperator<String> shell = w.begin(params);
        String expr = columnRef(cm.ref(), dialect);
        if (cm.condition() != null) {
            expr = conditionCase(cm.condition(), expr, dialect, params);
        }
        return cm.ref().agg().wrap(shell.apply(expr));
    }

    /**
     * 把统计条件渲染成 ARG 位置上的 CASE。
     *
     * <p>多个条件用 AND / OR 连起来并整体加括号；取值一律走占位符，
     * 字段与字段比较则两侧都是列引用、不产生参数。
     */
    private static String conditionCase(MetricRef.Condition c, String columnExpr,
                                        SqlDialect dialect, List<Object> params) {
        List<String> parts = new ArrayList<>(c.terms().size());
        for (MetricRef.Term t : c.terms()) {
            parts.add(termSql(t, dialect, params));
        }
        String cond = parts.size() == 1
                ? parts.get(0)
                : "(" + String.join(" " + c.logic().keyword() + " ", parts) + ")";
        return "CASE WHEN " + cond + " THEN " + columnExpr + " END";
    }

    /** 一个条件项。 */
    private static String termSql(MetricRef.Term t, SqlDialect dialect, List<Object> params) {
        String left = columnRef(t.left(), dialect);
        String op = "!=".equals(t.op()) ? " <> " : " " + t.op() + " ";

        if (t.comparesWithField()) {
            // 列与列比较：没有取值，也就没有参数
            return left + op + columnRef(t.right(), dialect);
        }
        if ("IN".equals(t.op())) {
            List<String> values = splitInValues(t.value());
            if (values.isEmpty()) {
                throw new IllegalStateException("统计条件 in 的取值为空：" + t.value());
            }
            values.forEach(params::add);
            return left + " IN (" + String.join(", ",
                    Collections.nCopies(values.size(), "?")) + ")";
        }
        params.add(t.value());
        return left + op + "?";
    }

    /** 该指标是否必须脱敏。派生指标看它的**全部**输入。 */
    private static boolean isMetricMasked(MetricRef m, Set<String> masked) {
        if (m instanceof MetricRef.Column cm) {
            return isMasked(masked, cm.ref().column());
        }
        return ((MetricRef.Derived) m).inputs().stream()
                .anyMatch(input -> isMetricMasked(input, masked));
    }

    private static void addColumn(List<String> selectParts,
                                  List<RenderedSql.OutputColumn> outColumns,
                                  SqlDialect dialect, String name, String expr, boolean masked) {
        selectParts.add(expr + " AS " + dialect.quoteIdentifier(name));
        outColumns.add(new RenderedSql.OutputColumn(name, expr, masked));
    }

    /**
     * 展开派生指标。
     *
     * <p>比值用 {@code NULLIF(…, 0)} 兜底：分母为 0 时返回 NULL，
     * 而不是抛"除数为零"——那会让用户以为系统坏了，而实际只是这批数据里没有分母。
     *
     * <p>差值与比值都先各自聚合再运算（{@code SUM(a)/SUM(b)}），
     * 不是逐行相除再求和——后者在多数场景下是错的，且错得不明显。
     */
    private static String renderDerived(MetricRef.Derived m, SqlDialect dialect,
                                        ColumnWrapper w, List<Object> params) {
        List<MetricRef> in = m.inputs();
        if (m.spec().kind() == DerivedMetric.Kind.DURATION) {
            // 起止各自包一层：窗口外的行两端都成 NULL，TIMESTAMPDIFF 得 NULL，
            // AVG 自动忽略——比在整条差值外面包 CASE 更直白
            return "AVG(" + dialect.timeDiffToNumber(m.spec().unit(),
                    durationOperand(in.get(0), dialect, w, params),
                    durationOperand(in.get(1), dialect, w, params)) + ")";
        }
        // 输入自己可能带统计条件（例如「已解决占比」的分子），
        // 走 renderMetric 才能把条件带上
        String left = renderMetric(in.get(0), dialect, w, params);
        String right = renderMetric(in.get(1), dialect, w, params);
        return switch (m.spec().kind()) {
            case RATIO -> "(" + left + ") / NULLIF(" + right + ", 0)";
            case DIFF -> "(" + left + ") - (" + right + ")";
            case DURATION -> throw new IllegalStateException("已在上面处理");
        };
    }

    /** 时长的输入是时间列本身（agg=NONE），包装后直接取用，不再套聚合。 */
    private static String durationOperand(MetricRef m, SqlDialect dialect,
                                          ColumnWrapper w, List<Object> params) {
        if (!(m instanceof MetricRef.Column cm)) {
            throw new IllegalStateException("时长的输入必须是物理列");
        }
        return w.begin(params).apply(columnRef(cm.ref(), dialect));
    }

    private static String columnRef(FieldRef r, SqlDialect dialect) {
        return dialect.quoteQualified(r.tableAlias() + "." + r.column());
    }

    private static boolean isMasked(Set<String> masked, String column) {
        for (String m : masked) {
            if (m.equalsIgnoreCase(column)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 规范化筛选运算符。
     *
     * <h2>为什么要认中文写法</h2>
     *
     * <p>schema 里写的是「运算符：= / != / &gt; / &lt; / in / like」，但**模型仍会填中文**——
     * 实测撞到过 {@code "op":"在"}（意为 {@code in}），而当时这里只认 ASCII，
     * 于是抛 {@link IllegalArgumentException}。
     *
     * <p>后果与「等价字段脱敏遗漏」是同一类：异常类型不在编排层的 catch 分支里，
     * 用户看到的是「处理失败，请稍后重试」，**并且这条失败连审计都进不去**。
     *
     * <h2>认不出来时抛 SemanticException，不抛 IllegalArgumentException</h2>
     *
     * <p>这个区别是刻意的：{@code SemanticException} 会被
     * {@code AskService.renderWithRetry} 接住并**带着纠正提示重问一次**，
     * 而它的 message 正是那段纠正提示的内容——所以这里要把可用取值列清楚，
     * 让模型下一次能改对。抛 {@code IllegalArgumentException} 则直接穿到最外层兜底。
     *
     * <p>未知取值仍然拒绝，**不猜**：猜错会拼出语法错误的 SQL，或者更糟——
     * 拼出一条语法正确但语义不同的 SQL。
     */
    /**
     * 作用于**日期列**的筛选取值：必须是具体日期，且按 {@code LocalDate} 绑定。
     *
     * <h2>它挡的是什么</h2>
     *
     * <p>实测撞到过：模型把时间条件写成筛选条件——
     * {@code {"field":"生效日期","op":"在","value":"上个月"}}，而 {@code timeRange} 留空。
     * 直接把「上个月」绑进 SQL，MySQL 报 <b>Incorrect DATE value: '上个月'</b>，
     * 用户看到的是"查询失败，请稍后重试"——错因离现场很远，而且这句话误导人去重试。
     *
     * <p>所以在**渲染阶段**就拒绝，并让异常消息直接告诉模型该怎么写：
     * 那条消息会原样进入 {@code renderWithRetry} 的纠正提示。
     *
     * <h2>为什么不"帮忙"把「上个月」翻译成日期区间</h2>
     *
     * <p>能翻译（{@link TimeRangeResolver} 认得），但**那是猜**：DSL 里有专门的
     * {@code timeRange} 字段表达时间范围，而 {@code filters} 表达的是逐行筛选。
     * 把两者混着用会让审计里的 DSL、界面上的「我理解成了」与实际下发的 SQL
     * 各说一套。宁可让模型重写一次——这正是 §8 要求「DSL 校验 + 重试」的原因。
     */
    private static LocalDate temporalValue(ResolvedFilter f, String raw) {
        if (raw != null && !raw.isBlank()) {
            try {
                return LocalDate.parse(raw.trim());
            } catch (Exception ignored) {
                // 落到下面统一报错
            }
        }
        throw new SemanticException(SemanticException.Reason.INVALID_FILTER_VALUE,
                "筛选条件「%s」的取值「%s」不是合法日期（应为 yyyy-MM-dd）。"
                        .formatted(f.name(), raw)
                        + "如果这是时间范围（如「上个月」「近30天」），请写进 timeRange 字段，"
                        + "不要放进 filters");
    }

    /**
     * 把 {@code in} 的取值拆成多项。
     *
     * <p>DSL 的 {@code filter.value} 是**一个字符串**，而 {@code in} 天然是多值。
     * 模型实测会产出这几种写法：{@code "华东,华北"}、{@code "华东、华北"}、
     * {@code ["华东","华北"]}。全部认下来——为此失败不值得，而且重试一次要花一次模型调用。
     *
     * <p>拆不出任何一项时抛 {@link SemanticException}（而不是渲染成 {@code IN ()}）：
     * 那是语法错误，且会让本次结果变成"查询失败"，错因离现场很远。
     *
     * @return 至少一项
     */
    private static List<String> splitInValues(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        List<String> out = new ArrayList<>();
        for (String part : s.split("[,，、|]")) {
            String v = part.trim();
            if (v.length() >= 2
                    && ((v.startsWith("\"") && v.endsWith("\""))
                    || (v.startsWith("'") && v.endsWith("'")))) {
                v = v.substring(1, v.length() - 1).trim();
            }
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        if (out.isEmpty()) {
            throw new SemanticException(SemanticException.Reason.UNSUPPORTED_OPERATOR,
                    "筛选条件用了 in，但取值里拆不出任何一项：「" + raw + "」。"
                            + "in 的取值请写成用逗号分隔的多个值，例如「华东,华北」");
        }
        return out;
    }

    private static String normalizeFilterOp(String op) {
        if (op == null || op.isBlank()) {
            return "=";
        }
        return switch (op.trim().toLowerCase(Locale.ROOT)) {
            case "=", "==", "等于", "是", "为" -> "=";
            case "!=", "<>", "不等于", "不是" -> "<>";
            case ">", "大于" -> ">";
            case ">=", "大于等于", "不小于" -> ">=";
            case "<", "小于" -> "<";
            case "<=", "小于等于", "不大于" -> "<=";
            case "in", "在", "属于", "包含于", "是其中之一" -> "IN";
            case "like", "包含", "含有", "像", "匹配" -> "LIKE";
            // 空值判断。**它不是比较运算**：`col = NULL` 永远为假，
            // 必须渲染成 IS NULL / IS NOT NULL 才行。
            // 实测：问「只算有解决时间的」，模型想表达"解决时间不为空"，
            // 但没有这个运算符，只能编一个 null 当日期值，于是校验失败
            case "is null", "isnull", "为空", "是空", "空值" -> "IS NULL";
            case "is not null", "isnotnull", "不为空", "非空", "不是空" -> "IS NOT NULL";
            default -> throw new SemanticException(SemanticException.Reason.UNSUPPORTED_OPERATOR,
                    "不支持的筛选运算符「" + op + "」。可用的只有："
                            + "= != > < >= <= in like is null is not null"
                            + "（请用这些符号或英文单词，不要用中文）");
        };
    }
}

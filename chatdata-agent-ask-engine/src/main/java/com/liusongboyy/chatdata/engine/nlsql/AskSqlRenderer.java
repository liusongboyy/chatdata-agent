package com.liusongboyy.chatdata.engine.nlsql;

import com.liusongboyy.chatdata.engine.datasource.dialect.SqlDialect;
import com.liusongboyy.chatdata.engine.permission.PermissionDecision;
import com.liusongboyy.chatdata.engine.permission.RowScope;
import com.liusongboyy.chatdata.engine.permission.SqlPredicate;
import com.liusongboyy.chatdata.engine.semantic.FieldRef;
import com.liusongboyy.chatdata.engine.semantic.JoinPlanner;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.SemanticException;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import com.liusongboyy.chatdata.engine.semantic.TableJoin;

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
        List<FieldRef> metricRefs = dsl.metrics().stream()
                .map(model::resolveMetric)
                .toList();
        List<FieldRef> dimRefs = dsl.dimensions().stream()
                .map(model::resolveDimension)
                .toList();

        Set<String> involvedAliases = new LinkedHashSet<>();
        metricRefs.forEach(r -> involvedAliases.add(r.tableAlias()));
        dimRefs.forEach(r -> involvedAliases.add(r.tableAlias()));

        // ② 过滤条件里的字段也要参与"涉及哪些表"的判断
        List<ResolvedFilter> filters = new ArrayList<>();
        for (AskDsl.Filter f : dsl.filters()) {
            FieldRef ref = model.resolveDimension(f.field());
            involvedAliases.add(ref.tableAlias());
            filters.add(new ResolvedFilter(ref, f.field(), f.op(), f.value()));
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
                : model.tableIdOfAlias(metricRefs.get(0).tableAlias());

        JoinPlanner.JoinPlan plan = JoinPlanner.plan(model.domain(), involvedTableIds, preferredRoot);
        String alias = model.aliasOfTable(plan.rootTableId());

        // ④ SELECT 子句。脱敏列渲染成常量，数据不出库
        Set<String> masked = allowed.maskedColumns();
        List<RenderedSql.OutputColumn> outColumns = new ArrayList<>();
        List<String> selectParts = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        // 维度表达式**只在这里算一次**，SELECT 与 GROUP BY 共用同一份。
        // 分开算的话，按粒度截断时会出现 SELECT 里是 DATE_FORMAT(...)、
        // GROUP BY 里却是原始列——MySQL 容忍这种写法，于是它不会报错，
        // 只是分组口径与显示口径不再一致
        List<String> dimExprs = new ArrayList<>();
        for (int i = 0; i < dimRefs.size(); i++) {
            FieldRef r = dimRefs.get(i);
            String name = dsl.dimensions().get(i);
            // 必须用 r.tableAlias()——该列自己所属的表，**不是**根表的别名。
            // 单表时两者恒等，多表时用根别名会指向一张没有这个列的表
            String expr = dialect.quoteQualified(r.tableAlias() + "." + r.column());
            expr = bucketIfTemporal(expr, r, model, dialect, dsl.granularity());
            dimExprs.add(expr);
            selectParts.add(expr + " AS " + dialect.quoteIdentifier(name));
            outColumns.add(new RenderedSql.OutputColumn(name, expr, false));
        }

        for (int i = 0; i < metricRefs.size(); i++) {
            FieldRef r = metricRefs.get(i);
            String name = dsl.metrics().get(i);
            // 同上：用列自己所属的表别名
            String quoted = dialect.quoteQualified(r.tableAlias() + "." + r.column());

            if (isMasked(masked, r.column())) {
                // 查询前脱敏：该列不进 SELECT，直接给常量。数据根本不出现
                selectParts.add("'***' AS " + dialect.quoteIdentifier(name));
                outColumns.add(new RenderedSql.OutputColumn(name, "'***'", true));
            } else {
                String expr = r.agg().wrap(quoted);
                selectParts.add(expr + " AS " + dialect.quoteIdentifier(name));
                outColumns.add(new RenderedSql.OutputColumn(name, expr, false));
            }
        }

        if (selectParts.isEmpty()) {
            throw new IllegalArgumentException("DSL 没有可渲染的输出列");
        }

        // ⑤ FROM + JOIN
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(dialect.limitPrefix(DEFAULT_LIMIT));
        sql.append(String.join(", ", selectParts));
        appendFromAndJoins(sql, plan, model, dialect);

        // ⑥ WHERE
        List<String> whereParts = new ArrayList<>();

        // 6a. 时间条件 —— 左闭右开，见 TimeRangeResolver 的说明
        if (dsl.timeRange() != null) {
            ResolvedTime rt = resolveTime(dsl.timeRange(), today);
            if (rt != null) {
                String col = resolveTimeColumn(model, dialect, involvedTableIds, dsl.timeRange());
                whereParts.add(col + " >= ?");
                params.add(rt.startInclusive());
                whereParts.add(col + " < ?");
                params.add(rt.endExclusive());
            }
        }

        // 6b. 筛选条件
        for (ResolvedFilter f : filters) {
            String col = dialect.quoteQualified(f.ref().tableAlias() + "." + f.ref().column());
            String op = normalizeFilterOp(f.op());

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

        if (!whereParts.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", whereParts));
        }

        // ⑦ GROUP BY（有指标时才需要）。用与 SELECT 完全相同的表达式
        if (!dimExprs.isEmpty() && !metricRefs.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", dimExprs));
        }

        // ⑧ 行数限制后缀（SQL Server 为空，它用前缀 TOP）
        String suffix = dialect.limitSuffix(DEFAULT_LIMIT);
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
    private record ResolvedFilter(FieldRef ref, String name, String op, String value) {
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
        for (String candidate : TIME_COLUMN_CANDIDATES) {
            try {
                FieldRef r = model.resolveDimension(candidate);
                // **时间列必须落在本次参与 FROM 的表上。** 否则渲染出
                // `FROM ins_customer WHERE t2.effect_date >= ?`——t2 不在 FROM 里，
                // MySQL 报 Unknown column，用户看到的是"查询失败"而不是"这个域查不了时间"。
                //
                // 这一条此前只写在注释里、没有实现：域里只要有多张带日期列的表，
                // 而查询只用到其中一张，就可能选中另一张。
                // 时间列可以在任意一张**参与查询**的表上——多表场景下它未必在主表。
                if (!involvedTableIds.contains(model.tableIdOfAlias(r.tableAlias()))) {
                    continue;
                }
                return dialect.quoteQualified(r.tableAlias() + "." + r.column());
            } catch (SemanticException ignored) {
                // 这个名字在本域内不可解析，试下一个
            }
        }
        throw new SemanticException(SemanticException.Reason.FIELD_NOT_FOUND,
                "问数里带了时间条件（" + tr.kind() + "），但本域内找不到可用的日期列。"
                        + "请在语义建模中把日期列的别名设为「生效日期」「统计日期」等，"
                        + "或改用业务术语显式声明时间口径");
    }

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
            default -> throw new SemanticException(SemanticException.Reason.UNSUPPORTED_OPERATOR,
                    "不支持的筛选运算符「" + op + "」。可用的只有："
                            + "= != > < >= <= in like（请用这些符号或英文单词，不要用中文）");
        };
    }
}

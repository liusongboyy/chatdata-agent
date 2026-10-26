package com.liusongboyy.chatdata.engine.chat;

import com.liusongboyy.chatdata.engine.nlsql.AskDsl;
import com.liusongboyy.chatdata.engine.nlsql.TimeRangeResolver;
import com.liusongboyy.chatdata.engine.permission.PermissionDecision;
import com.liusongboyy.chatdata.engine.permission.RowScope;
import com.liusongboyy.chatdata.engine.permission.SqlPredicate;
import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「我这次理解成了什么」——把生效的 DSL 回显成业务用户能看懂的事实。
 *
 * <h2>它存在的唯一理由：挡住静默语义错误</h2>
 *
 * <p>P0 spike 实测过这个失败形态：问题「上个月<strong>华东</strong>的<strong>有效</strong>保单
 * 保费合计」被翻译成 {@code {metrics:["保费"], timeRange:{…}, filters:null}}——
 * **两个核心约束凭空消失**，而对象解析成功、类型合法、无任何异常。
 * 若直接渲染 SQL，会退化成「全量保费合计」：**答案错误且全程静默**。
 *
 * <p>这类失败**监控看不到**（无异常、无 5xx）、**审计也看不到**（DSL 结构合法）。
 * 唯一能拦住它的位置就是界面——把系统实际理解到的东西摊开给用户看。
 * 用户看到「筛选：无」而自己明明说了「华东」，立刻就知道这次答案不能信。
 *
 * <h2>为什么回显事实而不是拼一句中文</h2>
 *
 * <p>四个槽位（指标/维度/时间/筛选）**永远全部返回，空的就是空列表**。
 * 拼成一句话时，缺的那一项会自然地"消失在句子里"——而"缺了什么"
 * 恰恰是本功能唯一要传达的信息。结构化返回则强迫界面把空格也画出来。
 *
 * <p>另一层考虑：这份事实同时是「无结果时给出下一步」（放宽条件重问）与
 * 「文字结论」的输入，结构化才可复用。
 *
 * <h2>它绝不能把一次成功的问数搞失败</h2>
 *
 * <p>这是诊断信息，不是业务结果。任何一处解析不了都降级为"按原文显示"，
 * 绝不抛异常——**为了多显示一行说明而让用户拿不到数据，是本末倒置**。
 * 故 {@link #of} 整体是尽力而为的。
 */
public record AskUnderstanding(
        /** 指标（业务名）。空列表表示这次查询没有任何聚合指标。 */
        List<String> metrics,
        /** 维度（业务名）。空列表表示没有分组。 */
        List<String> dimensions,
        /** 时间条件。{@code null} 表示**这次查询没有时间限制**——这本身是重要信息。 */
        TimeWindow time,
        /** 筛选条件。空列表表示**没有任何筛选**。 */
        List<FilterFact> filters,
        /** 数据范围（行级权限）。用户靠它理解"为什么我看到的数字和别人不一样"。 */
        DataScope dataScope,
        /**
         * 同环比：「同比」/「环比」；{@code null} 表示这次没有做对比。
         *
         * <p>必须回显：同环比会**多读一个时间窗**，用户看到"保费 120 万"时
         * 如果不知道系统同时查了上一年，就无法判断增长率是怎么来的。
         */
        String compare
) {

    /** 兼容构造器：不做同环比的调用点与测试不必改。 */
    public AskUnderstanding(List<String> metrics, List<String> dimensions, TimeWindow time,
                            List<FilterFact> filters, DataScope dataScope) {
        this(metrics, dimensions, time, filters, dataScope, null);
    }

    private static final Logger log = LoggerFactory.getLogger(AskUnderstanding.class);

    /**
     * 时间条件。
     *
     * @param kind 口径名（如「上个月」），模型原话，可能为 null
     * @param from 起（**含**）
     * @param to   止（**含**）
     */
    public record TimeWindow(String kind, String from, String to) {
    }

    /**
     * 一条筛选条件。
     *
     * @param field 字段的业务名（能解析出业务名时用业务名，否则用原文）
     * @param op    运算符原文（{@code =} / {@code !=} / {@code in} / {@code like} …）。
     *              不在这里翻成中文——那是展示层的事，翻译口径可能变，事实不会
     * @param value 取值原文
     */
    public record FilterFact(String field, String op, String value) {
    }

    /**
     * 数据范围。
     *
     * @param restricted 是否被行级权限限定
     * @param text       限定内容，如「区域 = 华东」。{@code restricted=false} 时为 null
     */
    public record DataScope(boolean restricted, String text) {
    }

    public AskUnderstanding {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        filters = filters == null ? List.of() : List.copyOf(filters);
    }

    /** 列占位符，与 {@link SqlPredicate} 里的写法一致。 */
    private static final Pattern COLUMN_REF =
            Pattern.compile("\\{col:([A-Za-z_][A-Za-z0-9_.]*)}");

    /**
     * 由**生效的** DSL 构造回显。
     *
     * @param dsl      生效的 DSL。注意必须是重试之后实际渲染的那一份——
     *                 记首次尝试的那份，等于给用户看一个从未执行过的意图
     * @param model    语义模型，用于把物理列名翻成业务名
     * @param decision 权限判定结果，用于回显数据范围
     * @param today    参考日期，与渲染时传入的必须是同一个值
     */
    public static AskUnderstanding of(AskDsl dsl,
                                      SemanticModel model,
                                      PermissionDecision decision,
                                      LocalDate today) {
        if (dsl == null) {
            return new AskUnderstanding(List.of(), List.of(), null, List.of(),
                    new DataScope(false, null), null);
        }

        List<String> metrics = displayNames(dsl.metrics(), model);
        List<String> dimensions = displayNames(dsl.dimensions(), model);

        List<FilterFact> filters = new ArrayList<>();
        for (AskDsl.Filter f : dsl.filters()) {
            filters.add(new FilterFact(
                    displayName(f.field(), model), f.op(), f.value()));
        }

        return new AskUnderstanding(metrics, dimensions, timeWindow(dsl, today), filters,
                dataScope(decision, model),
                dsl.compare() == null ? null : dsl.compare().label());
    }

    /**
     * 时间条件——**以解析结果为准，不以 DSL 原文为准**。
     *
     * <p>两者会不一致：{@code timeRange} 存在但既没有可解析的日期、口径名也解析不了时，
     * 解析返回 null，渲染器就**不往 SQL 里加时间条件**。此时若照着 DSL 回显「上个月」，
     * 等于替一次全时段查询背书——那正是本功能要防的事。
     *
     * <p>止期转成**闭区间**再回显：解析出来的是左闭右开 {@code [start, end)}，
     * 直接显示 {@code end} 会让「上个月」显示成 {@code 09-01 ~ 10-01}，
     * 看上去像把十月也算进去了。
     */
    private static TimeWindow timeWindow(AskDsl dsl, LocalDate today) {
        if (dsl.timeRange() == null) {
            return null;
        }
        try {
            TimeRangeResolver.ResolvedRange r = TimeRangeResolver.resolve(
                    dsl.timeRange().kind(), dsl.timeRange().start(), dsl.timeRange().end(), today);
            if (r == null) {
                // 有 timeRange 字段但解析不出任何条件 → 实际没有时间限制
                return null;
            }
            return new TimeWindow(
                    dsl.timeRange().kind(),
                    r.startInclusive().toString(),
                    r.endExclusive().minusDays(1).toString());
        } catch (Exception e) {
            // 解析失败本该由渲染器先抛出来，走到这里说明两条路径不一致。
            // 记日志但不抛：诊断信息不该影响已经拿到的数据
            log.warn("回显时间条件失败，按原文显示：kind={} 原因={}",
                    dsl.timeRange().kind(), e.getMessage());
            return new TimeWindow(dsl.timeRange().kind(),
                    dsl.timeRange().start(), dsl.timeRange().end());
        }
    }

    /** 数据范围。显式全量与隐式全量都不算"被限定"——用户看到的都是全部数据。 */
    private static DataScope dataScope(PermissionDecision decision, SemanticModel model) {
        if (!(decision instanceof PermissionDecision.Allowed allowed)) {
            return new DataScope(false, null);
        }
        if (!(allowed.rowScope() instanceof RowScope.Restricted restricted)) {
            return new DataScope(false, null);
        }
        try {
            return new DataScope(true, describe(restricted.predicate(), model));
        } catch (Exception e) {
            // 谓词形如 "({col:region} = ?) OR ({col:region} = ?)"，参数按序替换。
            // 这里的异常同样是"少显示一行"级别，不该影响问数结果
            log.warn("回显数据范围失败：{}", e.getMessage());
            return new DataScope(true, null);
        }
    }

    /**
     * 把权限谓词写成人能读的形式。
     *
     * <p>不是把 SQL 美化，而是**把参数填回占位符**：谓词里列是 {@code {col:region}}、
     * 值是 {@code ?}，两者都是为渲染准备的中间形态，直接给用户看等于什么都没说。
     */
    static String describe(SqlPredicate predicate, SemanticModel model) {
        String sql = predicate.sql();
        List<Object> params = predicate.params();

        StringBuilder out = new StringBuilder();
        Matcher m = COLUMN_REF.matcher(sql);
        int cursor = 0;      // 已消费到原文的哪个位置
        int nextParam = 0;   // 下一个待用参数的下标

        // 逐段扫描：列占位符之间是原样的运算符与 ?，后者按序填参数值
        while (m.find()) {
            nextParam = appendPlain(out, sql, cursor, m.start(), params, nextParam);
            out.append(displayName(m.group(1), model));
            cursor = m.end();
        }
        appendPlain(out, sql, cursor, sql.length(), params, nextParam);

        return out.toString().trim();
    }

    /**
     * 追加一段不含列占位符的原文，其中的 {@code ?} 依次填上参数值。
     *
     * @return 消费之后的参数下标
     */
    private static int appendPlain(StringBuilder out, String sql, int from, int to,
                                   List<Object> params, int paramIndex) {
        int idx = paramIndex;
        for (int i = from; i < to; i++) {
            char c = sql.charAt(i);
            if (c == '?' && idx < params.size()) {
                out.append(literal(params.get(idx++)));
            } else {
                out.append(c);
            }
        }
        return idx;
    }

    /** 参数值。字符串加引号，好和字段名区分开。 */
    private static String literal(Object value) {
        if (value == null) {
            return "空";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return "'" + value + "'";
    }

    /**
     * 物理列名 → 业务名。
     *
     * <p>权限规则里配的是**物理列名**（{@code field: region}），而用户认识的是业务名
     * （「区域」）。不翻译的话，回显出来的数据范围用户看不懂，那一行就白显示了。
     *
     * <p>翻不出来就用原文——**绝不猜**，也绝不抛异常。
     */
    private static String displayName(String raw, SemanticModel model) {
        if (raw == null || raw.isBlank() || model == null) {
            return raw;
        }
        // 规则里可能写成 "t.col" 形式，取最后一段去匹配
        String name = raw;
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(dot + 1);
        }
        for (RegisteredTable t : model.visibleTables()) {
            for (ColumnDef c : t.columns()) {
                if (c.matches(name)) {
                    return c.displayName();
                }
            }
        }
        return raw;
    }

    private static List<String> displayNames(List<String> raw, SemanticModel model) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream().map(n -> displayName(n, model)).toList();
    }
}

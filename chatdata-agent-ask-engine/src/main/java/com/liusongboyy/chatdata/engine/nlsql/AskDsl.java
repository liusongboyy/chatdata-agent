package com.liusongboyy.chatdata.engine.nlsql;

import java.util.List;

/**
 * 问数 DSL —— 语义层与 SQL 之间的中间表示，由 LLM 产出、先于 SQL 存在。
 *
 * <p>四项构成来自 {@code GLOSSARY.md}：指标、维度、时间范围、筛选条件。
 *
 * <p><strong>它是权限与审计的检查点。</strong>权限校验、字段存在性、审计都挂在这个对象上，
 * 而不是挂在 SQL 上——这样「LLM 生成的 SQL 是否越权」就变成
 * 「DSL 里的字段是否都在授权范围内」，可确定性验证。
 *
 * <p><strong>字段禁止 null。</strong>紧凑构造器把 null 兜底成空列表（见下），
 * 下游不写判空分支——判空分支本身是 bug 温床。
 * spike 实测中模型经常返回 {@code "dimensions": null} 而非空数组。
 *
 * <p><strong>注意：schema 不由此 record 反射生成。</strong>
 * 见 {@link AskDslSchema}——那是一个实测出来的坑。
 */
public record AskDsl(
        List<String> metrics,
        List<String> dimensions,
        TimeRange timeRange,
        List<Filter> filters,
        Granularity granularity,
        List<Sort> sort,
        Integer limit,
        List<String> shareOf,
        Compare compare
) {

    /**
     * 时间维度的粒度。
     *
     * <h2>为什么需要它</h2>
     *
     * <p>实测：问「**按月**看保费趋势」，模型把维度填成「生效日期」，
     * 于是查询按**天**分组，返回 268 个点——数字都对，但**不是用户要的粒度**。
     * DSL 里原本没有"按什么粒度分组"这个概念。
     *
     * <h2>为什么只支持到年和月</h2>
     *
     * <p>周与季度在五种库上的写法差异更大（周起始日、ISO 周、季度函数名），
     * 而它们的使用频率远低于月与年。**不支持的取值一律不截断**，
     * 而不是猜成月——猜错会让「按周看」静默变成「按月看」，比不截断更糟。
     */
    public enum Granularity {
        /** 不截断（默认）。日期列原样分组。 */
        DAY,
        MONTH,
        YEAR;

        /** 解析。null / 空 / 不认识的取值都返回 {@link #DAY}——不截断是安全的默认。 */
        public static Granularity parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return DAY;
            }
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "month", "月" -> MONTH;
                case "year", "年" -> YEAR;
                default -> DAY;
            };
        }
    }

    /** 兼容构造器：不指定粒度。既有的调用点与测试不必改。 */
    public AskDsl(List<String> metrics, List<String> dimensions,
                  TimeRange timeRange, List<Filter> filters) {
        this(metrics, dimensions, timeRange, filters, Granularity.DAY,
                List.of(), null, List.of(), null);
    }

    /** 兼容构造器：只指定粒度。 */
    public AskDsl(List<String> metrics, List<String> dimensions,
                  TimeRange timeRange, List<Filter> filters, Granularity granularity) {
        this(metrics, dimensions, timeRange, filters, granularity,
                List.of(), null, List.of(), null);
    }

    /**
     * 排序项。
     *
     * @param field     输出列名：指标名、维度名，或系统生成的「保费占比」「保费同比」
     * @param direction asc / desc。**缺省按 desc**——排序问题绝大多数是"最高/最多/前 N"，
     *                  而 SQL 的缺省是 asc，直接用会给出"最低的 5 个"，错得静默。
     *                  prompt 要求模型永远显式写出方向，这里是它漏写时的兜底
     */
    public record Sort(String field, String direction) {

        public Sort {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("排序字段不能为空");
            }
            field = field.trim();
            direction = (direction == null || direction.isBlank())
                    ? "desc"
                    : direction.trim().toLowerCase(java.util.Locale.ROOT);
            if (!"asc".equals(direction) && !"desc".equals(direction)) {
                throw new IllegalArgumentException(
                        "排序方向只能是 asc 或 desc，实际：" + direction);
            }
        }
    }

    /**
     * 同环比 / 自定义对比期。
     *
     * <h2>三种对比方式</h2>
     *
     * <ul>
     *   <li><b>同比（yoy）</b>：与上一年同期比。粒度必须是月或年——按天分组会踩到
     *       2 月 29 日，平移到上一年会落到 2 月 28 日，与当年的 2 月 28 日撞进同一组</li>
     *   <li><b>环比（mom）</b>：与上一个月比。粒度是月或天</li>
     *   <li><b>自定义（custom）</b>：与用户点名的某段时间比（「比 2023 年」）。
     *       两个窗口长度、相位一致时才能把上一期平移到本期；否则在没有时间维度时仍可用
     *       （就是"本期总额 vs 那一期总额"），有分组时**拒绝**而不是近似</li>
     * </ul>
     *
     * <p>对比期由系统计算，不接受用户直接给偏移量——那等于让模型做算术。
     */
    public record Compare(Mode mode, String start, String end) {

        public enum Mode {
            YOY, MOM, CUSTOM
        }

        public Compare {
            if (mode == null) {
                throw new IllegalArgumentException("compare 缺少 mode");
            }
            if (mode != Mode.CUSTOM) {
                start = null;
                end = null;
            } else {
                if (start == null || start.isBlank() || end == null || end.isBlank()) {
                    throw new IllegalArgumentException(
                            "自定义对比期必须同时给出 start 与 end（yyyy-MM-dd）");
                }
                start = requireIsoDate(start, "start");
                end = requireIsoDate(end, "end");
                java.time.LocalDate s = java.time.LocalDate.parse(start);
                java.time.LocalDate e = java.time.LocalDate.parse(end);
                if (!s.isBefore(e)) {
                    throw new IllegalArgumentException(
                            "自定义对比期的起点必须早于终点：" + start + " ~ " + end);
                }
            }
        }

        private static String requireIsoDate(String raw, String field) {
            try {
                return java.time.LocalDate.parse(raw.trim()).toString();
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "自定义对比期的 " + field + " 不是合法日期（应为 yyyy-MM-dd）：" + raw);
            }
        }

        public static Compare yoy() {
            return new Compare(Mode.YOY, null, null);
        }

        public static Compare mom() {
            return new Compare(Mode.MOM, null, null);
        }

        public static Compare custom(String start, String end) {
            return new Compare(Mode.CUSTOM, start, end);
        }

        /** 中文标签，用于生成输出列名（如「保费同比」「保费对比」）。 */
        public String label() {
            return switch (mode) {
                case YOY -> "同比";
                case MOM -> "环比";
                case CUSTOM -> "对比";
            };
        }

        /** 解析 yoy / mom / 同比 / 环比。null / 空返回 null；不认识的取值抛异常。 */
        public static Compare parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "yoy", "同比" -> yoy();
                case "mom", "环比" -> mom();
                default -> throw new IllegalArgumentException(
                        "compare 只能填 yoy / mom，或 {\"mode\":\"custom\",\"start\":\"...\",\"end\":\"...\"}"
                                + "（也接受「同比」「环比」），实际：" + raw);
            };
        }
    }

    /**
     * 兜底 null 为不可变空列表。
     *
     * <p>同时做了防御性拷贝：{@code List.copyOf} 让外部拿不到可变引用，
     * 这个对象会在权限校验与审计之间传递，中途被改会很隐蔽。
     */
    public AskDsl {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        filters = filters == null ? List.of() : List.copyOf(filters);
        granularity = granularity == null ? Granularity.DAY : granularity;
        sort = sort == null ? List.of() : List.copyOf(sort);
        shareOf = shareOf == null ? List.of() : List.copyOf(shareOf);
        if (limit != null && limit <= 0) {
            throw new IllegalArgumentException("limit 必须为正数，实际：" + limit);
        }
    }

    /**
     * 去掉对比期，保留其余一切。
     *
     * <p>用在**分析编排**里：模型有时会给一个不成形的 compare（自定义对比却没给日期、
     * 同比却按天分组）。这类 compare 是它自己加的增强，不是用户说过的约束——
     * 丢掉它，这一步仍然查得出正确数据；而让整条计划因此作废，用户拿到的只会更少。
     *
     * <p>单查询链路**不做**这个降级：那里 compare 是用户明确要求的，
     * 悄悄丢掉等于答应了一次没做的对比。
     */
    /** 换一个时间范围，保留其余一切。 */
    public AskDsl withTimeRange(TimeRange range) {
        return new AskDsl(metrics, dimensions, range, filters, granularity,
                sort, limit, shareOf, compare);
    }

    /** 换一个粒度，保留其余一切。 */
    public AskDsl withGranularity(Granularity g) {
        return new AskDsl(metrics, dimensions, timeRange, filters, g,
                sort, limit, shareOf, compare);
    }

    public AskDsl withoutCompare() {
        return new AskDsl(metrics, dimensions, timeRange, filters, granularity,
                sort, limit, shareOf, null);
    }

    /** 是否什么都没解析出来——用于触发重问。 */
    public boolean isEmpty() {
        return metrics.isEmpty() && dimensions.isEmpty() && filters.isEmpty() && timeRange == null;
    }

    /**
     * 时间范围。
     *
     * <p>整体可空（用户未提及时间）；但一旦存在，{@code kind} 必须有值——
     * spike 实测模型会把它编造成 {@code "relative"} 这类内部术语，
     * 也会把整句话塞进 {@code start}（该字段约定是 {@code yyyy-MM-dd}）。
     * 这两类非法值必须在构造期拒绝，不能放行到下游。
     *
     * @param kind  口径名称，如「本月」「近10天」「今年」
     * @param start 起始日期 yyyy-MM-dd，未明确时为 null
     * @param end   结束日期 yyyy-MM-dd，未明确时为 null
     */
    public record TimeRange(String kind, String start, String end) {
    }

    /**
     * 筛选条件。
     *
     * <p>比较对象可以是常量，也可以是**另一个字段**——「关闭时间晚于 SLA 截止时间」
     * 这类条件两侧都是列。字段与字段比较时用 {@code rightField} 而不是 {@code value}，
     * 两者互斥。
     *
     * @param op         比较运算符：= / != / &gt; / &lt; / &gt;= / &lt;= / in / like
     * @param value      与常量比较时的取值；与字段比较时为 null
     * @param rightField 与字段比较时的右侧字段业务名；与常量比较时为 null
     */
    public record Filter(String field, String op, String value, String rightField) {

        /** 与常量比较。 */
        public Filter(String field, String op, String value) {
            this(field, op, value, null);
        }

        public Filter {
            boolean hasRight = rightField != null && !rightField.isBlank();
            if (hasRight) {
                // 两者同时出现时以 rightField 为准。schema 里 value 仍是必填
                // （spike 实测：不在 required 里的字段会被模型系统性省略，
                // 去掉它会让所有筛选条件一起丢值），所以模型很可能会两个都填
                rightField = rightField.trim();
                value = null;
            }
        }

        public boolean comparesWithField() {
            return rightField != null;
        }
    }
}

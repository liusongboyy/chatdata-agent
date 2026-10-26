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
     * 同环比。
     *
     * <h2>只做两种，且与粒度绑定</h2>
     *
     * <p>同比按**年**平移、环比按**月**平移。粒度决定平移后能否对齐：
     * <ul>
     *   <li>同比：按月或按年分组。按天分组会踩到 2 月 29 日——平移到上一年会落到
     *       2 月 28 日，与当年的 2 月 28 日撞进同一组，比例静默算错</li>
     *   <li>环比：按天或按月分组</li>
     * </ul>
     * 其余组合在渲染前直接拒绝，不做近似。
     */
    public enum Compare {
        /** 同比：与上个年度的同一时段比。 */
        YOY("同比"),
        /** 环比：与上个周期（月）比。 */
        MOM("环比");

        private final String label;

        Compare(String label) {
            this.label = label;
        }

        /** 中文标签，用于生成输出列名（如「保费同比」）。 */
        public String label() {
            return label;
        }

        /** 解析。null / 空返回 null（不对比）；不认识的取值抛异常，不静默忽略。 */
        public static Compare parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "yoy", "同比" -> YOY;
                case "mom", "环比" -> MOM;
                default -> throw new IllegalArgumentException(
                        "compare 只能是 yoy / mom（或「同比」「环比」），实际：" + raw);
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
     * @param op 比较运算符：= / != / &gt; / &lt; / in / like
     */
    public record Filter(String field, String op, String value) {
    }
}

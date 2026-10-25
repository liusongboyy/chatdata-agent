package com.liusongboyy.chatdata.engine.datasource.dialect;

/**
 * 一种数据库的 SQL 方言。
 *
 * <h2>这个接口刻意保持薄</h2>
 *
 * <p>首版支持 MySQL / Doris / PostgreSQL / Oracle(12c+) / SQL Server 五种，
 * 后续还会加。接口越厚，每加一种库要实现的就越多，也越容易实现错。
 * 故**只放真正存在差异的项**，其余一律不抽象。
 *
 * <p>已经确认**不需要**进接口的项（记在这里免得以后有人想加）：
 *
 * <ul>
 *   <li><b>当前日期</b>——「上个月」这类相对时间在 Java 侧解析成具体日期，
 *       SQL 里只出现 {@code >= ? AND < ?}，方言中立。少一处差异就少一类 bug。</li>
 *   <li><b>聚合函数</b>——{@code SUM} / {@code COUNT} / {@code AVG} 五种库一致。</li>
 *   <li><b>比较运算符</b>——{@code =} / {@code <>} / {@code IN} 一致。</li>
 *   <li><b>JOIN 语法</b>——{@code INNER JOIN ... ON} 一致。</li>
 * </ul>
 *
 * <h2>标识符必须走本接口，不得手拼</h2>
 *
 * <p>见 {@link #quoteQualified} 的说明——大小写折叠是这五种库里最容易出错的一处。
 */
public interface SqlDialect {

    /** 数据源类型标识，对应 {@code ask_datasource.db_type}，如 {@code MYSQL5.7} / {@code Doris}。 */
    String id();

    /** 给管理员看的名字。 */
    String displayName();

    /**
     * 引用单个标识符（表名或列名）。
     *
     * <p><strong>必须按元数据报告的原文引用，不要做大小写转换。</strong>
     * {@code DatabaseMetaData} 报告的是库里实际存储的名字，原样引用一定对。
     */
    String quoteIdentifier(String raw);

    /**
     * 引用限定名，如 {@code p.region} → {@code "p"."region"}。
     *
     * <p><strong>这是本接口存在的首要理由。</strong>不加引号时，同一份元数据
     * （列名 {@code Region}）在三种库上会解析成三个不同名字：
     *
     * <table>
     *   <tr><th>库</th><th>未加引号的 {@code Region} 解析为</th></tr>
     *   <tr><td>MySQL / Doris / SQL Server</td><td>{@code Region}（不折叠）</td></tr>
     *   <tr><td>PostgreSQL</td><td>{@code region}（折小写）</td></tr>
     *   <tr><td>Oracle</td><td>{@code REGION}（折大写）</td></tr>
     * </table>
     *
     * <p>渲染器**一律**通过本方法引用标识符，不手拼。
     */
    default String quoteQualified(String qualified) {
        if (qualified == null || qualified.isBlank()) {
            throw new IllegalArgumentException("标识符不能为空");
        }
        String[] parts = qualified.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(quoteIdentifier(parts[i]));
        }
        return sb.toString();
    }

    /**
     * 行数限制的**前缀**（放在 {@code SELECT} 之后）。
     *
     * <p>只有 SQL Server 用得到：{@code SELECT TOP 10 ...}。其余返回空串。
     */
    default String limitPrefix(int limit) {
        return "";
    }

    /**
     * 行数限制的**后缀**（放在语句末尾）。
     *
     * <p>默认 {@code LIMIT n}；Oracle 覆盖为 {@code FETCH FIRST n ROWS ONLY}（12c+）；
     * SQL Server 覆盖为空串（它用 {@link #limitPrefix}）。
     */
    default String limitSuffix(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("行数限制必须为正数，实际 " + limit);
        }
        return "LIMIT " + limit;
    }

    /**
     * 把日期列渲染成 {@code yyyy-MM} 的月份字符串，用于按月分组。
     *
     * <p>这是五种库里差异最刺眼的一处——**格式串是两种不同的模式语言**：
     * MySQL 系用 {@code %Y-%m}，PostgreSQL / Oracle 用 {@code YYYY-MM}，
     * SQL Server 又是 {@code yyyy-MM}。写混了不会报错，只会得到错的分组。
     *
     * @param columnExpression 已引用过的列表达式，如 {@code "p"."effect_date"}
     */
    String monthBucket(String columnExpression);

    /**
     * 把日期列渲染成 {@code yyyy} 的年份字符串，用于按年分组。
     *
     * <p>与 {@link #monthBucket} 同样的道理：格式串各写各的。
     * 年粒度看起来简单（不就是取前四位吗），但用 {@code SUBSTRING} 去截
     * 在 SQL Server 与 Oracle 上要转成字符串再截，反而不如各自的原生函数可靠。
     */
    String yearBucket(String columnExpression);

    /**
     * 两个时间列之差的**数值**，用于派生指标里的「时长」。
     *
     * <p>这是少数几种真正需要方言的地方之一：{@code TIMESTAMPDIFF} 只有
     * MySQL/Doris 有；PostgreSQL 用 {@code EXTRACT(EPOCH FROM (b - a))}、
     * Oracle 的日期相减直接得到**天**、SQL Server 用 {@code DATEDIFF}。
     *
     * <p>返回值语义：{@code unit} 为单位的天/时/分/秒数，**不是**日期类型。
     *
     * @param unit       SECOND / MINUTE / HOUR / DAY（已由 DerivedMetric 校验过大写）
     * @param startExpr  起始时间列表达式（已按方言引用）
     * @param endExpr    结束时间列表达式
     */
    String timeDiffToNumber(String unit, String startExpr, String endExpr);

    /**
     * 按粒度渲染日期列。
     *
     * <p>这是渲染器该调用的入口——它把"粒度"这个语义映射到具体函数，
     * 调用方不必自己 switch。
     *
     * @param granularity day / month / year，大小写不敏感；null 或 day 表示不截断
     * @return 截断后的表达式；{@code day} 粒度原样返回列表达式
     */
    default String bucket(String columnExpression, String granularity) {
        if (granularity == null || granularity.isBlank()) {
            return columnExpression;
        }
        return switch (granularity.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "month" -> monthBucket(columnExpression);
            case "year" -> yearBucket(columnExpression);
            // day 及未识别的取值都不截断。**不认识的粒度不猜**——
            // 猜成 month 会让"按周看"静默变成"按月看"，那比不截断更糟
            default -> columnExpression;
        };
    }

    /**
     * 按给定引号字符引用标识符，并转义标识符内部出现的同种引号。
     *
     * <p>转义不能省：标识符里带引号虽然罕见，但一旦出现而没转义，
     * 就从一个名字变成了两个 token —— 那是注入。
     *
     * @param quote 引号字符，反引号或双引号
     */
    static String quoteWith(char quote, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("标识符不能为空");
        }
        String t = raw.trim();
        String q = String.valueOf(quote);
        return q + t.replace(q, q + q) + q;
    }
}

package com.liusongboyy.chatdata.engine.datasource.dialect;

/**
 * Apache Hive。
 *
 * <h2>与 MySQL 的实质差异</h2>
 *
 * <p>两者看起来都像 SQL，但问数渲染器用到的四个时间函数<b>没有一个可以直接照抄</b>：
 *
 * <table>
 *   <tr><th></th><th>MySQL</th><th>Hive</th></tr>
 *   <tr><td>月份桶</td><td>{@code DATE_FORMAT(x,'%Y-%m')}</td>
 *       <td>{@code date_format(x,'yyyy-MM')}——模式语言不同</td></tr>
 *   <tr><td>年份桶</td><td>{@code YEAR(x)}</td><td>{@code year(x)}——大小写不敏感，可复用</td></tr>
 *   <tr><td>时间平移</td><td>{@code DATE_ADD(x, INTERVAL n MONTH)}</td>
 *       <td>{@code add_months(x, n)}——<b>没有 INTERVAL 语法</b></td></tr>
 *   <tr><td>时间差</td><td>{@code TIMESTAMPDIFF(unit, a, b)}</td>
 *       <td>{@code datediff(b, a)}——<b>只支持天</b>，参数顺序相反</td></tr>
 * </table>
 *
 * <h2>时间差只到天，是刻意的</h2>
 *
 * <p>Hive 的 {@code datediff} 只返回天数差。要实现小时/分钟差得自己乘除，
 * 而 {@code unix_timestamp} 在 Hive 2 之前对时区处理有坑。
 *
 * <p>所以这里<b>只支持 DAY</b>，其余单位明确抛异常。问数里"用时多少小时"
 * 这类需求在 Hive 上会失败——但那比静默算错好。要支持的话应该让管理员
 * 用派生指标显式表达（{@code datediff(...) * 24}），而不是我们猜。
 */
public class HiveDialect implements SqlDialect {

    @Override
    public String id() {
        return "HIVE";
    }

    @Override
    public String displayName() {
        return "Apache Hive";
    }

    @Override
    public String quoteIdentifier(String raw) {
        // Hive 用反引号，与 MySQL 一致。**必须加**——
        // Hive 的列名大小写不敏感但保留原样，不加引号遇到保留字会直接语法错误
        return SqlDialect.quoteWith('`', raw);
    }

    @Override
    public String monthBucket(String columnExpression) {
        // Hive 的 date_format 用 Java SimpleDateFormat 模式（yyyy-MM），
        // 不是 MySQL 的 %Y-%m。这两个写混了不会报错，只会得到字面量
        return "date_format(" + columnExpression + ", 'yyyy-MM')";
    }

    @Override
    public String yearBucket(String columnExpression) {
        return "year(" + columnExpression + ")";
    }

    @Override
    public String addInterval(String unit, String expression, int amount) {
        // Hive 没有 `expr + INTERVAL n UNIT` 语法，只能用函数
        return switch (unit) {
            case "MONTH" -> "add_months(" + expression + ", " + amount + ")";
            case "YEAR" -> "add_months(" + expression + ", " + (amount * 12) + ")";
            case "DAY" -> "date_add(" + expression + ", " + amount + ")";
            default -> throw new IllegalArgumentException(
                    "Hive 的时间平移只支持 YEAR / MONTH / DAY，实际：" + unit);
        };
    }

    @Override
    public String timeDiffToNumber(String unit, String startExpr, String endExpr) {
        if (!"DAY".equals(unit)) {
            throw new IllegalArgumentException(
                    "Hive 的 datediff 只返回天数差，不支持 " + unit
                            + "。需要更细的粒度时请用派生指标显式换算");
        }
        // **参数顺序与 MySQL 相反**：datediff(结束, 开始)
        return "datediff(" + endExpr + ", " + startExpr + ")";
    }
}

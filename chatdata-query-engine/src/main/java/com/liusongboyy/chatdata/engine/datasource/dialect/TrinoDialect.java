package com.liusongboyy.chatdata.engine.datasource.dialect;

/**
 * Trino（原 PrestoSQL）。
 *
 * <h2>与 MySQL 的实质差异</h2>
 *
 * <table>
 *   <tr><th></th><th>MySQL</th><th>Trino</th></tr>
 *   <tr><td>标识符</td><td>反引号 {@code `x`}</td>
 *       <td><b>双引号</b> {@code "x"}——ANSI 风格</td></tr>
 *   <tr><td>月份桶</td><td>{@code DATE_FORMAT(x,'%Y-%m')}</td>
 *       <td>{@code date_format(x,'%Y-%m')}——模式语言相同</td></tr>
 *   <tr><td>年份桶</td><td>{@code YEAR(x)}</td><td>{@code year(x)}</td></tr>
 *   <tr><td>时间平移</td><td>{@code DATE_ADD(x, INTERVAL n MONTH)}</td>
 *       <td>{@code date_add('month', n, x)}——单位在前</td></tr>
 *   <tr><td>时间差</td><td>{@code TIMESTAMPDIFF(unit,a,b)}</td>
 *       <td>{@code date_diff('day', a, b)}——单位是字符串且在前</td></tr>
 * </table>
 *
 * <h2>标识符必须用双引号</h2>
 *
 * <p>用反引号在 Trino 上是<b>语法错误</b>，不是"也能用"。
 * 而 Trino 会把未加引号的标识符折成小写——两者叠加意味着
 * "不加引号"和"加错引号"都会坏，只是坏的方式不同。
 *
 * <h2>date_diff 的参数顺序</h2>
 *
 * <p>{@code date_diff(unit, from, to)} 返回 {@code to - from}，
 * 与 MySQL 的 {@code TIMESTAMPDIFF(unit, from, to)} 顺序一致，
 * 但单位是字符串且必须带引号。
 */
public class TrinoDialect implements SqlDialect {

    @Override
    public String id() {
        return "TRINO";
    }

    @Override
    public String displayName() {
        return "Trino";
    }

    @Override
    public String quoteIdentifier(String raw) {
        return SqlDialect.quoteWith('"', raw);
    }

    @Override
    public String monthBucket(String columnExpression) {
        // Trino 的 date_format 沿用 MySQL 风格的 %Y-%m 模式
        return "date_format(" + columnExpression + ", '%Y-%m')";
    }

    @Override
    public String yearBucket(String columnExpression) {
        return "year(" + columnExpression + ")";
    }

    @Override
    public String addInterval(String unit, String expression, int amount) {
        // 单位在前、且是小写字符串。与 MySQL 的 INTERVAL 语法完全不同
        String u = switch (unit) {
            case "YEAR", "MONTH", "DAY" -> unit.toLowerCase(java.util.Locale.ROOT);
            default -> throw new IllegalArgumentException(
                    "Trino 的时间平移只支持 YEAR / MONTH / DAY，实际：" + unit);
        };
        return "date_add('" + u + "', " + amount + ", " + expression + ")";
    }

    @Override
    public String timeDiffToNumber(String unit, String startExpr, String endExpr) {
        String u = switch (unit) {
            case "SECOND", "MINUTE", "HOUR", "DAY", "MONTH", "YEAR" ->
                    unit.toLowerCase(java.util.Locale.ROOT);
            default -> throw new IllegalArgumentException("未知时长单位：" + unit);
        };
        // date_diff(unit, from, to) → to - from，与 TIMESTAMPDIFF 顺序一致
        return "date_diff('" + u + "', " + startExpr + ", " + endExpr + ")";
    }
}

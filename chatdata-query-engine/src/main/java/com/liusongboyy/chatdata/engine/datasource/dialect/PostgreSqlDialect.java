package com.liusongboyy.chatdata.engine.datasource.dialect;

/** PostgreSQL。 */
public class PostgreSqlDialect implements SqlDialect {

    @Override
    public String id() {
        return "POSTGRESQL";
    }

    @Override
    public String displayName() {
        return "PostgreSQL";
    }

    @Override
    public String quoteIdentifier(String raw) {
        // 双引号。**必须加**——PostgreSQL 把未加引号的标识符折成小写，
        // 元数据里若报告的是 Region，不加引号会变成 region。
        return SqlDialect.quoteWith('"', raw);
    }

    @Override
    public String monthBucket(String columnExpression) {
        // 模式语言是 YYYY-MM，与 MySQL 的 %Y-%m 不同
        return "to_char(" + columnExpression + ", 'YYYY-MM')";
    }

    @Override
    public String yearBucket(String columnExpression) {
        return "to_char(" + columnExpression + ", 'YYYY')";
    }

    @Override
    public String addInterval(String unit, String expression, int amount) {
        if (!"YEAR".equals(unit) && !"MONTH".equals(unit) && !"DAY".equals(unit)) {
            throw new IllegalArgumentException("时间平移只支持 YEAR / MONTH / DAY，实际：" + unit);
        }
        return "(" + expression + " + INTERVAL '" + amount + " "
                + unit.toLowerCase(java.util.Locale.ROOT) + "')";
    }

    @Override
    public String timeDiffToNumber(String unit, String startExpr, String endExpr) {
        // EXTRACT(EPOCH FROM ...) 得到秒；按单位折算
        String perUnit = switch (unit) {
            case "SECOND" -> "1";
            case "MINUTE" -> "60";
            case "HOUR" -> "3600";
            case "DAY" -> "86400";
            default -> throw new IllegalArgumentException("未知时长单位：" + unit);
        };
        return "(EXTRACT(EPOCH FROM (" + endExpr + " - " + startExpr + ")) / " + perUnit + ")";
    }
}

package com.liusongboyy.chatdata.engine.datasource.dialect;

/** MySQL 5.7+。 */
public class MySqlDialect implements SqlDialect {

    /** 同环比只用到年/月/日三种单位，其余取值一律拒绝，不猜。 */
    static void requireIntervalUnit(String unit) {
        if (!"YEAR".equals(unit) && !"MONTH".equals(unit) && !"DAY".equals(unit)) {
            throw new IllegalArgumentException("时间平移只支持 YEAR / MONTH / DAY，实际：" + unit);
        }
    }

    @Override
    public String id() {
        return "MYSQL5.7";
    }

    @Override
    public String displayName() {
        return "MySQL";
    }

    @Override
    public String quoteIdentifier(String raw) {
        return SqlDialect.quoteWith('`', raw);
    }

    @Override
    public String monthBucket(String columnExpression) {
        // MySQL 的模式语言是 %Y-%m，与 PostgreSQL / Oracle 的 YYYY-MM 完全不同
        return "DATE_FORMAT(" + columnExpression + ", '%Y-%m')";
    }

    @Override
    public String yearBucket(String columnExpression) {
        return "YEAR(" + columnExpression + ")";
    }

    @Override
    public String addInterval(String unit, String expression, int amount) {
        requireIntervalUnit(unit);
        return "DATE_ADD(" + expression + ", INTERVAL " + amount + " " + unit + ")";
    }

    @Override
    public String timeDiffToNumber(String unit, String startExpr, String endExpr) {
        return "TIMESTAMPDIFF(" + unit + ", " + startExpr + ", " + endExpr + ")";
    }
}

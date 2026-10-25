package com.liusongboyy.chatdata.engine.datasource.dialect;

/** MySQL 5.7+。 */
public class MySqlDialect implements SqlDialect {

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
    public String timeDiffToNumber(String unit, String startExpr, String endExpr) {
        return "TIMESTAMPDIFF(" + unit + ", " + startExpr + ", " + endExpr + ")";
    }
}

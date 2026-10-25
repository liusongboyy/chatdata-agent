package com.liusongboyy.chatdata.engine.datasource.dialect;

/**
 * SQL Server。
 *
 * <p>它的行数限制是**前缀**（{@code SELECT TOP n ...}）而不是后缀，
 * 是五种库里唯一如此的——渲染器必须把 {@link #limitPrefix} 放在正确位置，
 * 不能统一当成后缀处理。
 */
public class SqlServerDialect implements SqlDialect {

    @Override
    public String id() {
        return "SQLSERVER";
    }

    @Override
    public String displayName() {
        return "SQL Server";
    }

    @Override
    public String quoteIdentifier(String raw) {
        return SqlDialect.quoteWith('"', raw);
    }

    @Override
    public String limitPrefix(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("行数限制必须为正数，实际 " + limit);
        }
        return "TOP " + limit + " ";
    }

    @Override
    public String limitSuffix(int limit) {
        // SQL Server 用 TOP，没有后缀形式。
        // 仍然校验参数：覆盖了默认实现就绕过了它的校验，不补上的话
        // limitSuffix(0) 会静默返回空串，而 limitPrefix(0) 抛异常——同一个类里两种行为。
        if (limit <= 0) {
            throw new IllegalArgumentException("行数限制必须为正数，实际 " + limit);
        }
        return "";
    }

    @Override
    public String monthBucket(String columnExpression) {
        // 不用 FORMAT()：它是 CLR 实现的，在大结果集上比 CONVERT 慢一个量级。
        // 样式 120 = yyyy-mm-dd hh:mi:ss，截到 7 位正好是 yyyy-MM。
        return "CONVERT(varchar(7), " + columnExpression + ", 120)";
    }

    @Override
    public String yearBucket(String columnExpression) {
        return "YEAR(" + columnExpression + ")";
    }

    @Override
    public String timeDiffToNumber(String unit, String startExpr, String endExpr) {
        return "DATEDIFF(" + unit + ", " + startExpr + ", " + endExpr + ")";
    }
}

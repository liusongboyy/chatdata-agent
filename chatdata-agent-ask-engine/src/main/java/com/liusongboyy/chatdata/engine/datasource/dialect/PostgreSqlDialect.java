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
}

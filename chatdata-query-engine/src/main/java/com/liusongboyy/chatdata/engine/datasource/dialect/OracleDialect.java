package com.liusongboyy.chatdata.engine.datasource.dialect;

/**
 * Oracle 12c 及以上。
 *
 * <p><strong>版本假设：12c+。</strong>行数限制用 {@code FETCH FIRST n ROWS ONLY}
 * （12c 引入的 ANSI 语法）。11g 及更早没有这个语法，得退回
 * {@code WHERE ROWNUM <= n}——那种写法与 {@code ORDER BY} 同时存在时语义还不同
 * （ROWNUM 在排序前分配），是个坑。若将来要支持 11g，需要单独一个方言类。
 */
public class OracleDialect implements SqlDialect {

    @Override
    public String id() {
        return "ORACLE";
    }

    @Override
    public String displayName() {
        return "Oracle";
    }

    @Override
    public String quoteIdentifier(String raw) {
        // 双引号。**必须加**——Oracle 把未加引号的标识符折成**大写**，
        // 与 PostgreSQL 折小写正好相反，这是两者最容易混的一处。
        return SqlDialect.quoteWith('"', raw);
    }

    @Override
    public String limitSuffix(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("行数限制必须为正数，实际 " + limit);
        }
        return "FETCH FIRST " + limit + " ROWS ONLY";
    }

    @Override
    public String monthBucket(String columnExpression) {
        return "to_char(" + columnExpression + ", 'YYYY-MM')";
    }

    @Override
    public String yearBucket(String columnExpression) {
        return "to_char(" + columnExpression + ", 'YYYY')";
    }

    @Override
    public String addInterval(String unit, String expression, int amount) {
        return switch (unit) {
            // ADD_MONTHS 会处理月末（1 月 31 日 + 1 个月 = 2 月 28/29 日），
            // 用日期直接加天数做不到这一点
            case "MONTH" -> "ADD_MONTHS(" + expression + ", " + amount + ")";
            case "YEAR" -> "ADD_MONTHS(" + expression + ", " + (amount * 12) + ")";
            // Oracle 的 DATE 加数字就是加天
            case "DAY" -> "(" + expression + " + " + amount + ")";
            default -> throw new IllegalArgumentException(
                    "时间平移只支持 YEAR / MONTH / DAY，实际：" + unit);
        };
    }

    @Override
    public String timeDiffToNumber(String unit, String startExpr, String endExpr) {
        // Oracle 的 DATE 相减直接得到**天**（带小数）
        String factor = switch (unit) {
            case "SECOND" -> "86400";
            case "MINUTE" -> "1440";
            case "HOUR" -> "24";
            case "DAY" -> "1";
            default -> throw new IllegalArgumentException("未知时长单位：" + unit);
        };
        return "((" + endExpr + " - " + startExpr + ") * " + factor + ")";
    }
}

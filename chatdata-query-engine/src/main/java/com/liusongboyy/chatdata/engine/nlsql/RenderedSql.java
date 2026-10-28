package com.liusongboyy.chatdata.engine.nlsql;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 渲染产物：一条可执行的 SQL 及其配套信息。
 *
 * <p><strong>为什么不止返回一个字符串：</strong>下游有三个消费方，各自需要不同的东西——
 *
 * <ul>
 *   <li><b>执行</b>需要 {@link #sql()} 与 {@link #params()}（参数必须分开传，不能内联）</li>
 *   <li><b>结果映射</b>需要 {@link #columns()} 知道结果集每一列是什么、顺序如何</li>
 *   <li><b>审计</b>需要 {@link #maskedColumnNames()} 记录本次哪些列被脱敏了</li>
 * </ul>
 *
 * <p>如果只返回 SQL 字符串，这三件事都要靠反解 SQL 得到——那是把已经知道的信息丢掉再猜回来。
 *
 * @param sql        含 {@code ?} 占位符的 SQL
 * @param params     与占位符一一对应的绑定值
 * @param columns    输出列，顺序与结果集一致
 * @param tableAlias 本次查询用到的表别名（最小版本只支持单表）
 */
public record RenderedSql(
        String sql,
        List<Object> params,
        List<OutputColumn> columns,
        String tableAlias
) {

    public RenderedSql {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        params = params == null ? List.of() : List.copyOf(params);
        columns = columns == null ? List.of() : List.copyOf(columns);

        long placeholders = sql.chars().filter(c -> c == '?').count();
        if (placeholders != params.size()) {
            throw new IllegalStateException(
                    "渲染出的 SQL 占位符数量(%d)与参数数量(%d)不一致——这是渲染器的缺陷，拒绝产出"
                            .formatted(placeholders, params.size()));
        }
    }

    /**
     * 一个输出列。
     *
     * @param name       结果集里的列名（{@code AS} 别名）
     * @param expression SELECT 里的表达式，审计用
     * @param masked     是否已脱敏
     */
    public record OutputColumn(String name, String expression, boolean masked) {
    }

    /**
     * 展示用的 SQL：把参数内联成字面量。
     *
     * <h2>为什么执行与展示必须分开</h2>
     *
     * <p>执行走 {@link #sql()} + {@link #params()}（参数化，防注入）。
     * 但**展示与审计不能是参数化的**：用户看到
     * {@code WHERE org_path LIKE ? ESCAPE '\'} 根本不知道查了什么，
     * 审计里也无法回答"这个用户当时按什么条件取的数"。
     *
     * <p><strong>本方法的结果不是执行语句。</strong>拿它去执行等于放弃注入防护。
     * 命名上用 {@code display} 而不是 {@code inline}，就是为了让误用在 review 时能被看见。
     *
     * <p>转义按 MySQL 规则：反斜杠与单引号都转义（单引号翻倍）。
     * 其它方言的标识符引号不同，但**字面量写法在这几种库上一致**。
     */
    public String displaySql() {
        if (params.isEmpty()) {
            return sql;
        }
        StringBuilder sb = new StringBuilder(sql.length() + params.size() * 16);
        int next = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '?' && next < params.size()) {
                sb.append(literal(params.get(next++)));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 把一个绑定值写成 SQL 字面量。
     *
     * <p>数值与布尔不加引号；其余（字符串、日期、时间）加单引号并转义。
     *
     * <p>{@code null} 分支是防御性的：本 record 的紧凑构造器用 {@code List.copyOf}
     * 拒绝 null 参数（JDBC 绑定 null 还要指定 SQL 类型，本工程不用这种写法）。
     * 保留它是因为将来放开限制时，漏了这一支会写出 {@code 'null'} 这种字符串字面量。
     */
    private static String literal(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        String s = String.valueOf(v);
        return "'" + s.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    /** 被脱敏的输出列名。审计要单独记这一类。 */
    public Set<String> maskedColumnNames() {
        return columns.stream()
                .filter(OutputColumn::masked)
                .map(OutputColumn::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 结果集列名，按顺序。 */
    public List<String> columnNames() {
        return columns.stream().map(OutputColumn::name).toList();
    }
}

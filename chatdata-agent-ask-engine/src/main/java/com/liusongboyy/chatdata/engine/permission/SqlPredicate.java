package com.liusongboyy.chatdata.engine.permission;

import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一段 SQL 谓词 + 它的绑定参数。
 *
 * <h2>列引用用占位符，不用裸列名</h2>
 *
 * <p>谓词里的列写成 {@code {col:region}}，渲染时由渲染器替换成
 * <strong>带表限定、按方言引用</strong>的形式（如 {@code `t1`.`region`}）。
 *
 * <p>为什么必须这样：<strong>编译期不知道表别名</strong>。
 * 行规则在 {@code PermissionGuard} 里编译，而别名要到渲染阶段才由
 * {@code SemanticModel} 分配。若编译期就把列名固化成字符串，会出现两个问题：
 *
 * <ol>
 *   <li><strong>无法加表限定</strong>——多表查询里 {@code region = ?} 会歧义，
 *       或者注到错误的表上</li>
 *   <li><strong>无法按方言引用</strong>——裸列名会被大小写折叠规则改写：
 *       PostgreSQL 把 {@code Region} 折成 {@code region}、Oracle 折成 {@code REGION}。
 *       项目里「标识符一律带引号」的规则在权限这条路上会被绕过</li>
 * </ol>
 *
 * <p>占位符里是编译期已按白名单校验过的标识符（见 {@code RowRuleCompiler}），
 * 故替换是安全的。
 *
 * <h2>取值必须参数化</h2>
 *
 * <p>{@link #sql()} 里只允许出现占位符 {@code ?}，所有取值走 {@link #params()}。
 * 任何把值拼进 {@link #sql()} 的写法都是缺陷，不论值看起来多"干净"。
 *
 * @param sql    含 {@code ?} 与 {@code {col:xxx}} 的谓词片段
 * @param params 与 {@code ?} 一一对应的绑定值
 */
public record SqlPredicate(String sql, List<Object> params) {

    /** 列占位符的正则。列名部分只允许标识符字符与点号——编译期已校验过。 */
    private static final Pattern COLUMN_REF = Pattern.compile("\\{col:([A-Za-z_][A-Za-z0-9_.]*)}");

    public SqlPredicate {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("谓词 SQL 不能为空");
        }
        params = params == null ? List.of() : List.copyOf(params);

        long placeholders = sql.chars().filter(c -> c == '?').count();
        if (placeholders != params.size()) {
            throw new IllegalArgumentException(
                    "谓词占位符数量(%d)与参数数量(%d)不一致——这是一处必然导致参数错位的缺陷，拒绝构造"
                            .formatted(placeholders, params.size()));
        }
    }

    public static SqlPredicate of(String sql, Object... params) {
        return new SqlPredicate(sql, List.of(params));
    }

    /** 构造一个「列 运算符 值」的谓词。列以占位符形式进入 SQL。 */
    public static SqlPredicate column(String field, String sqlOperator, Object value) {
        return new SqlPredicate("{col:" + field + "} " + sqlOperator + " ?", List.of(value));
    }

    /** 构造一个「列 IN (?, ?, ...)」的谓词。 */
    public static SqlPredicate columnIn(String field, String sqlOperator, List<?> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("IN 的取值不能为空");
        }
        String marks = String.join(", ", values.stream().map(v -> "?").toList());
        return new SqlPredicate("{col:" + field + "} " + sqlOperator + " (" + marks + ")",
                List.copyOf(values));
    }

    /**
     * 把 SQL 里所有列占位符替换为渲染结果。
     *
     * @param renderer 输入列名（编译期校验过的标识符），返回带限定与引号的表达式
     * @return 替换后的新谓词；没有占位符时返回 {@code this}
     */
    public SqlPredicate mapColumns(UnaryOperator<String> renderer) {
        Matcher m = COLUMN_REF.matcher(sql);
        if (!m.find()) {
            return this;
        }
        StringBuilder sb = new StringBuilder();
        m.reset();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(renderer.apply(m.group(1))));
        }
        m.appendTail(sb);
        return new SqlPredicate(sb.toString(), params);
    }

    /** 是否仍含未替换的列占位符。渲染完成后应恒为 false。 */
    public boolean hasUnresolvedColumns() {
        return COLUMN_REF.matcher(sql).find();
    }

    /** 用 {@code AND} 连接，括号包裹以保证与调用方的运算符优先级正确。 */
    public SqlPredicate and(SqlPredicate other) {
        return new SqlPredicate(
                "(" + this.sql + ") AND (" + other.sql + ")",
                concat(this.params, other.params));
    }

    /** 用 {@code OR} 连接，括号包裹。 */
    public SqlPredicate or(SqlPredicate other) {
        return new SqlPredicate(
                "(" + this.sql + ") OR (" + other.sql + ")",
                concat(this.params, other.params));
    }

    private static List<Object> concat(List<Object> a, List<Object> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }
}

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

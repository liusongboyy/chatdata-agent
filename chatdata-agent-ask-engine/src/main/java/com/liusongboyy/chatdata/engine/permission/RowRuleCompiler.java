package com.liusongboyy.chatdata.engine.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 把行级规则编译成参数化的 SQL 谓词。
 *
 * <h2>三道校验，缺一不可</h2>
 *
 * <ol>
 *   <li><b>字段名白名单</b>——{@code field} 来自配置，直接拼进 SQL 就是注入点。
 *       只接受 {@code 标识符} 或 {@code 表名.列名}。</li>
 *   <li><b>运算符白名单</b>——配置里写什么运算符就拼什么，等于把 SQL 语法交给配置者。
 *       只接受下面 {@link #OPERATORS} 里列出的一小组。</li>
 *   <li><b>取值参数化</b>——见 {@link SqlPredicate}。</li>
 * </ol>
 *
 * <h2>动态变量取不到时：拒绝，不跳过</h2>
 *
 * <p>厂商的行为是：{@code sysVar} 为空或取不到值时**跳过该条规则**并打一条日志。
 * 后果是**该角色的限制静默消失**——用户看到的是全部行。ADR-0002 记录了这条日志原文：
 * <pre>
 *   [chat2bi] 行级规则动态变量[sysOrgCode]暂不支持或值为空，已跳过
 * </pre>
 *
 * <p>我们改成**拒绝整个查询并说明原因**。理由是三种处理方式里只有它同时满足
 * "不泄露"与"可发现"：
 * <table>
 *   <tr><th>处理</th><th>数据安全</th><th>能否被发现</th></tr>
 *   <tr><td>跳过规则（厂商）</td><td>✗ 泄露全部行</td><td>✗ 只打日志，用户无感</td></tr>
 *   <tr><td>当作恒假</td><td>✓</td><td>△ 用户看到空结果，会以为"没数据"</td></tr>
 *   <tr><td><b>拒绝并说明</b></td><td>✓</td><td>✓ 用户直接看到配置问题</td></tr>
 * </table>
 */
public final class RowRuleCompiler {

    /** 合法标识符：{@code col} 或 {@code table.col}。 */
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$");

    /**
     * 运算符白名单。键是配置里的写法，值是 SQL 运算符。
     *
     * <p>同时接受中文写法（厂商前端 {@code ROW_OPS} 用的就是中文）与 SQL 写法，
     * 因为配置是人手写的，两种都可能出现。
     */
    private static final Map<String, String> OPERATORS = Map.ofEntries(
            Map.entry("=", "="),
            Map.entry("==", "="),
            Map.entry("!=", "<>"),
            Map.entry("<>", "<>"),
            Map.entry("≠", "<>"),
            Map.entry(">", ">"),
            Map.entry("≥", ">="),
            Map.entry(">=", ">="),
            Map.entry("<", "<"),
            Map.entry("≤", "<="),
            Map.entry("<=", "<="),
            Map.entry("包含", "LIKE"),
            Map.entry("like", "LIKE"),
            Map.entry("LIKE", "LIKE"),
            Map.entry("in", "IN"),
            Map.entry("IN", "IN"),
            Map.entry("not in", "NOT IN"),
            Map.entry("NOT IN", "NOT IN"));

    private RowRuleCompiler() {
    }

    /**
     * 编译主体的行范围。
     *
     * <p>判定顺序：显式全量 → 匹配规则 → 无规则则隐式全量。
     *
     * <p><strong>最后一档刻意不是"拒绝"。</strong>厂商语义下"被授予域访问权即等于域内全量可见"
     * 是模型的固有含义（ADR-0002 的 R2），改成拒绝会让所有未配行规则的合法配置直接不可用。
     * 我们保留语义，但用 {@link RowScope.ImplicitUnrestricted} 与显式授予区分开，
     * 让审计能查出"谁在靠隐式全量看数据"。
     */
    public static RowScope compile(DomainPermission perm, PermissionContext ctx) {
        // ① 显式授予全量——管理员写下了意图
        if (perm.hasUnrestrictedRole(ctx.roles())) {
            return new RowScope.Unrestricted();
        }

        // ② 收集适用于本主体的规则（按角色逐字符匹配）
        List<RowRule> applicable = new ArrayList<>();
        for (RowRule rule : perm.rowRules()) {
            if (ctx.hasRole(rule.role())) {
                applicable.add(rule);
            }
        }

        // ③ 无匹配规则 → 隐式全量（厂商语义），但标记出来供审计区分
        if (applicable.isEmpty()) {
            return new RowScope.ImplicitUnrestricted();
        }

        // ④ 逐条编译。任一条无法求值即整体拒绝——不跳过、不放宽
        List<SqlPredicate> predicates = new ArrayList<>(applicable.size());
        for (RowRule rule : applicable) {
            predicates.add(compileOne(rule, ctx));
        }

        // ⑤ 多角色/多规则按 OR 合并：可见范围是各角色可见范围的并集
        SqlPredicate merged = predicates.stream()
                .reduce(SqlPredicate::or)
                .orElseThrow(() -> new IllegalStateException("规则列表非空却合并失败"));

        return new RowScope.Restricted(merged);
    }

    /** 编译单条规则。任何非法输入都抛 {@link RuleNotEvaluableException}，由上层转成拒绝。 */
    private static SqlPredicate compileOne(RowRule rule, PermissionContext ctx) {
        String field = rule.field();
        if (field == null || !IDENTIFIER.matcher(field.trim()).matches()) {
            throw new RuleNotEvaluableException(
                    "规则 %d 的字段名非法：%s".formatted(rule.id(), field));
        }
        field = field.trim();

        String rawOp = rule.op() == null ? "=" : rule.op().trim();
        String op = OPERATORS.get(rawOp);
        if (op == null) {
            throw new RuleNotEvaluableException(
                    "规则 %d 的运算符不受支持：%s".formatted(rule.id(), rawOp));
        }

        Object value = resolveValue(rule, ctx);

        // IN / NOT IN：取值按逗号拆成多个占位符
        if ("IN".equals(op) || "NOT IN".equals(op)) {
            List<String> parts = splitValues(value);
            if (parts.isEmpty()) {
                throw new RuleNotEvaluableException(
                        "规则 %d 是 %s 但取值为空".formatted(rule.id(), rawOp));
            }
            return SqlPredicate.columnIn(field, op, parts);
        }

        // LIKE：包含匹配，两侧加通配符。通配符本身转义，避免用户值里的 % 变成"匹配全部"。
        // 这里不能用 SqlPredicate.column(...)——那个工厂方法会再追加一个 ? 占位符，
        // 而 LIKE 的 ? 已经写死在运算符片段里了，直接用构造器。
        if ("LIKE".equals(op)) {
            String pattern = "%" + escapeLike(String.valueOf(value)) + "%";
            return new SqlPredicate("{col:" + field + "} LIKE ? ESCAPE '\\\\'", List.of(pattern));
        }

        return SqlPredicate.column(field, op, value);
    }

    /**
     * 取值解析。
     *
     * <p>动态规则从 {@link PermissionContext} 取；**取不到就抛异常**，
     * 不返回 null、不跳过——见类注释里的三种处理方式对比。
     */
    private static Object resolveValue(RowRule rule, PermissionContext ctx) {
        if (!rule.dynamic()) {
            if (rule.value() == null) {
                throw new RuleNotEvaluableException("规则 %d 是静态规则但没有取值".formatted(rule.id()));
            }
            return rule.value();
        }

        return ctx.dynamicVar(rule.sysVar())
                .orElseThrow(() -> new RuleNotEvaluableException(
                        "规则 %d 依赖动态变量 [%s]，但当前主体取不到该值。"
                                .formatted(rule.id(), rule.sysVar())
                                + "（厂商在此情形下会跳过规则、放行全部行；本项目改为拒绝）"));
    }

    private static List<String> splitValues(Object value) {
        if (value == null) {
            return List.of();
        }
        return java.util.Arrays.stream(String.valueOf(value).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 转义 LIKE 的通配符，防止取值里的 {@code %} 把"包含 X"变成"匹配全部"。 */
    private static String escapeLike(String v) {
        return v.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 规则无法求值。上层应转为拒绝，**不得**降级为"不过滤"。 */
    public static class RuleNotEvaluableException extends RuntimeException {
        public RuleNotEvaluableException(String message) {
            super(message);
        }
    }
}

package com.liusongboyy.chatdata.engine.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;
import com.liusongboyy.chatdata.engine.semantic.DerivedMetric;
import com.fasterxml.jackson.databind.JsonNode;
import com.liusongboyy.chatdata.engine.semantic.ConditionTerm;
import com.liusongboyy.chatdata.engine.semantic.MetricCondition;
import com.liusongboyy.chatdata.engine.semantic.SemanticException;

import java.util.List;

/**
 * 域内注册表的创建/更新入参。
 *
 * <p>列定义在这里接收**结构化**的列表，由本类序列化成 {@code columns_json}。
 * 让管理面直接传 JSON 字符串也能用，但那意味着前端要自己拼 JSON，
 * 而拼错的表现是"保存成功但问数时找不到字段"——错在了看不见的地方。
 *
 * @param name        表名（TABLE 模式）或虚拟表标识（SQL 模式）
 * @param label       显示名
 * @param mode        TABLE / SQL
 * @param sqlText     SQL 虚拟表定义（SQL 模式必填）
 * @param columns     列定义
 * @param enabled     是否对 LLM 可见
 * @param sortNo      排序号
 */
public record DomainTableInput(
        String name,
        String label,
        String mode,
        String sqlText,
        List<ColumnInput> columns,
        boolean enabled,
        int sortNo
) {

    private static final ObjectMapper M = new ObjectMapper();

    /**
     * 一列。
     *
     * @param col     物理列名。**必须与数据库里的列名逐字符一致**——
     *                渲染器原样加引号引用它，差一个字符就查不到
     * @param type    数据库类型名，仅用于展示
     * @param agg     聚合方式；维度列留空
     * @param alias   业务名。问数时用户与模型使用的名字
     * @param enabled 是否对 LLM 可见
     * @param derived   派生指标定义；普通列为 null。派生指标**没有物理列**，
     *                  故此时 {@code col} 可以留空
     * @param condition 统计条件：只统计满足条件的行；无则为 null。
     *                  **原样接收 {@code columns_json} 里的 condition 对象**，
     *                  由 {@link MetricCondition#from} 解析——管理面与运行时同一份判据，
     *                  也天然兼容"单条件"与"AND/OR 多条件"两种形态
     */
    public record ColumnInput(
            String col,
            String type,
            String agg,
            String alias,
            boolean enabled,
            DerivedInput derived,
            JsonNode condition
    ) {
        /** 兼容旧调用方与测试：普通列没有派生定义、没有统计条件。 */
        public ColumnInput(String col, String type, String agg, String alias, boolean enabled) {
            this(col, type, agg, alias, enabled, null, null);
        }

        /** 兼容：派生指标，不带统计条件。 */
        public ColumnInput(String col, String type, String agg, String alias, boolean enabled,
                           DerivedInput derived) {
            this(col, type, agg, alias, enabled, derived, null);
        }
    }


    /**
     * 派生指标入参。
     *
     * <p>字段名与 {@code columns_json} 里的 {@code derived} 块一致，
     * 因此前端**原样**把它放在列定义里传进来即可，不必自己拼 JSON 字符串。
     *
     * <p>校验交给 {@link DerivedMetric} 的构造器——管理面与运行时的判据必须是同一份，
     * 否则会出现"保存时通过、启动校验报错"。
     */
    public record DerivedInput(
            String kind,
            String left,
            String right,
            String start,
            String end,
            String unit
    ) {
        DerivedMetric validated(String columnName) {
            try {
                return new DerivedMetric(DerivedMetric.Kind.parse(kind),
                        left, right, start, end, unit);
            } catch (SemanticException e) {
                throw new AdminException(
                        "派生指标「" + columnName + "」配置有误：" + e.getMessage());
            }
        }
    }

    public DomainTableInput validated() {
        require(name, "表名");

        String m = (mode == null || mode.isBlank()) ? "TABLE" : mode.trim().toUpperCase();
        if (!"TABLE".equals(m) && !"SQL".equals(m)) {
            throw new AdminException("类型只能是 TABLE 或 SQL，实际：" + mode);
        }
        if ("SQL".equals(m) && (sqlText == null || sqlText.isBlank())) {
            throw new AdminException("SQL 虚拟表必须填写 SQL 定义");
        }

        List<ColumnInput> cols = columns == null ? List.of() : columns;
        for (ColumnInput c : cols) {
            if (c.derived() != null) {
                validateDerived(c);
                continue;
            }
            require(c.col(), "列名");
            // 聚合方式在保存时就校验。未知取值如果放行，要到渲染时才抛异常，
            // 而那时用户看到的是"这个问题涉及的数据还没配置好"，指不到真正的原因
            Aggregation agg;
            try {
                agg = Aggregation.parse(c.agg());
            } catch (Exception e) {
                throw new AdminException(
                        "列「%s」的聚合方式不受支持：%s。可用：SUM / COUNT / COUNT_DISTINCT / AVG / MAX / MIN"
                                .formatted(c.col(), c.agg()));
            }
            if (hasCondition(c)) {
                if (!agg.isAggregating()) {
                    throw new AdminException(
                            "列「" + (c.alias() == null ? c.col() : c.alias()) + "」配置了统计条件，"
                                    + "但没有聚合方式——条件只对指标有意义");
                }
                parseCondition(c);
            }
        }

        return new DomainTableInput(name.trim(), blankToNull(label), m, blankToNull(sqlText),
                List.copyOf(cols), enabled, sortNo);
    }

    /** 序列化成 {@code columns_json}。字段名与厂商保持一致，便于对照。 */
    public String columnsJson() {
        ArrayNode arr = M.createArrayNode();
        for (ColumnInput c : (columns == null ? List.<ColumnInput>of() : columns)) {
            ObjectNode node = arr.addObject();
            // 派生指标没有物理列，这里写空串而不是 null：columns_json 的读取方
            // （DomainLoader / keysOf）按"col 为空即无物理列"判断
            node.put("col", c.col() == null ? "" : c.col().trim());
            node.put("type", c.type() == null ? "" : c.type());
            // 派生指标本身已是聚合结果，不能再叠聚合（ColumnDef 会拒绝）
            node.put("agg", c.derived() != null || c.agg() == null ? "" : c.agg().trim());
            node.put("alias", c.alias() == null ? "" : c.alias().trim());
            node.put("enabled", c.enabled());
            if (hasCondition(c)) {
                writeCondition(node.putObject("condition"), parseCondition(c));
            }
            if (c.derived() != null) {
                // 校验已通过，这里再解析一次是为了拿到**规范化后**的值：
                // kind 统一成小写、unit 统一成大写，避免同一份配置写出两种形态
                DerivedMetric d = c.derived().validated(c.alias());
                ObjectNode dv = node.putObject("derived");
                dv.put("kind", d.kind().name().toLowerCase(java.util.Locale.ROOT));
                if (d.kind() == DerivedMetric.Kind.DURATION) {
                    dv.put("start", d.start());
                    dv.put("end", d.end());
                    dv.put("unit", d.unit());
                } else {
                    dv.put("left", d.left());
                    dv.put("right", d.right());
                }
            }
        }
        return arr.toString();
    }

    private static boolean hasCondition(ColumnInput c) {
        return c.condition() != null && !c.condition().isNull() && !c.condition().isMissingNode();
    }

    /** 解析统计条件。判据复用 {@link MetricCondition}，与运行时同一份。 */
    private static MetricCondition parseCondition(ColumnInput c) {
        try {
            return MetricCondition.from(c.condition());
        } catch (Exception e) {
            throw new AdminException(
                    "列「" + (c.alias() == null ? c.col() : c.alias()) + "」的统计条件有误："
                            + e.getMessage());
        }
    }

    /**
     * 写回 {@code columns_json}。
     *
     * <p>单个条件写成扁平形态（与老配置一致），多个条件才写成
     * {@code {logic, conditions:[...]}}——省得每份夹具都被包一层。
     */
    private static void writeCondition(ObjectNode target, MetricCondition mc) {
        if (mc.terms().size() == 1) {
            writeTerm(target, mc.terms().get(0));
            return;
        }
        target.put("logic", mc.logic().name().toLowerCase(java.util.Locale.ROOT));
        ArrayNode arr = target.putArray("conditions");
        for (ConditionTerm t : mc.terms()) {
            writeTerm(arr.addObject(), t);
        }
    }

    private static void writeTerm(ObjectNode node, ConditionTerm t) {
        node.put("field", t.field());
        node.put("op", t.op());
        if (t.comparesWithField()) {
            node.put("rightField", t.rightField());
        } else {
            node.put("value", t.value());
        }
    }

    /** 派生指标的入参校验。判据复用 {@link DerivedMetric}，与运行时同一份。 */
    private static void validateDerived(ColumnInput c) {
        require(c.alias(), "派生指标的业务名");
        if (c.col() != null && !c.col().isBlank()) {
            throw new AdminException(
                    "派生指标「" + c.alias() + "」没有物理列，列名请留空（当前写的是 " + c.col().trim() + "）");
        }
        if (c.agg() != null && !c.agg().isBlank()) {
            throw new AdminException(
                    "派生指标「" + c.alias() + "」本身就是聚合结果，不能再配置聚合方式");
        }
        c.derived().validated(c.alias());
    }

    private static void require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new AdminException(label + "不能为空");
        }
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}

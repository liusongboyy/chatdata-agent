package com.liusongboyy.chatdata.engine.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;

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
     */
    public record ColumnInput(
            String col,
            String type,
            String agg,
            String alias,
            boolean enabled
    ) {
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
            require(c.col(), "列名");
            // 聚合方式在保存时就校验。未知取值如果放行，要到渲染时才抛异常，
            // 而那时用户看到的是"这个问题涉及的数据还没配置好"，指不到真正的原因
            try {
                Aggregation.parse(c.agg());
            } catch (Exception e) {
                throw new AdminException(
                        "列「%s」的聚合方式不受支持：%s。可用：SUM / COUNT / COUNT_DISTINCT / AVG / MAX / MIN"
                                .formatted(c.col(), c.agg()));
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
            node.put("col", c.col().trim());
            node.put("type", c.type() == null ? "" : c.type());
            node.put("agg", c.agg() == null ? "" : c.agg().trim());
            node.put("alias", c.alias() == null ? "" : c.alias().trim());
            node.put("enabled", c.enabled());
        }
        return arr.toString();
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

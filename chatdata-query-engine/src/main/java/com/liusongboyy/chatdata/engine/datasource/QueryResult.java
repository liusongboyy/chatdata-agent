package com.liusongboyy.chatdata.engine.datasource;

import java.util.List;
import java.util.Map;

/**
 * 一次查询的结果。
 *
 * <p>刻意**不带列类型信息**：问数的消费方是表格与图表，它们要的是"列名 + 值"，
 * 不是 JDBC 类型。带上类型会诱使调用方按类型分支，而那些分支最终都要转成字符串展示。
 *
 * @param columns 列名，顺序与每行一致
 * @param rows    数据行；每行的长度等于 {@code columns.size()}
 */
public record QueryResult(List<String> columns, List<List<Object>> rows) {

    public QueryResult {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public static QueryResult empty() {
        return new QueryResult(List.of(), List.of());
    }

    public int rowCount() {
        return rows.size();
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /** 把一行转成「列名 → 值」，便于按名字取值。 */
    public Map<String, Object> rowAsMap(int index) {
        List<Object> row = rows.get(index);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < columns.size() && i < row.size(); i++) {
            out.put(columns.get(i), row.get(i));
        }
        return out;
    }
}

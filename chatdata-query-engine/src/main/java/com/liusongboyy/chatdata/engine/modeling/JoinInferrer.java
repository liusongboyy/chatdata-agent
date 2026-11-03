package com.liusongboyy.chatdata.engine.modeling;

import com.liusongboyy.chatdata.engine.datasource.MetaReader.ColumnMeta;
import com.liusongboyy.chatdata.engine.datasource.MetaReader.TableMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 推断表之间的关联。
 *
 * <h2>三档置信度，必须分开标</h2>
 *
 * <table>
 *   <tr><th>来源</th><th>判据</th><th>置信度</th></tr>
 *   <tr><td>外键</td><td>JDBC {@code getImportedKeys}</td><td>高</td></tr>
 *   <tr><td>命名</td><td>{@code XxxID} 且存在表 {@code xxx} 有主键</td><td>中</td></tr>
 *   <tr><td>无</td><td>以上都不成立</td><td>不生成</td></tr>
 * </table>
 *
 * <h2>为什么命名推断不能省</h2>
 *
 * <p>本仓库的 AdventureWorks 库<b>一条外键都没有</b>（导入时删掉了），
 * 真实企业库也常如此。只靠外键，这个功能在主场景上直接失效。
 *
 * <h2>歧义必须列全，不许挑一个</h2>
 *
 * <p>{@code XxxID} 可能匹配到多张表时，把<b>全部候选</b>列出来让人选。
 * 挑一个的话，挑错的表现是 JOIN 到错的表——SQL 合法、行数看着也正常，
 * 只是数字不对。这正是这个项目最怕的那类失败。
 */
public final class JoinInferrer {

    private JoinInferrer() {
    }

    /** 关联来源的置信度。 */
    public enum Confidence {
        /** JDBC 外键。可以直接用 */
        FOREIGN_KEY,
        /** 按命名推断。要人工确认 */
        NAMING
    }

    /**
     * 一条关联候选。
     *
     * @param left       左侧表
     * @param leftColumn 左侧列
     * @param right      右侧表
     * @param rightColumn 右侧列
     * @param confidence 置信度
     * @param reason     为什么这么推断
     */
    public record JoinCandidate(String left, String leftColumn, String right, String rightColumn,
                                Confidence confidence, String reason) {
    }

    /**
     * 外键形态的输入。
     *
     * <p>刻意不让这个类依赖 JDBC——{@code MetaReader} 负责读，这里只负责推断。
     * 这样推断规则能脱离数据库单测。
     */
    public record ForeignKey(String table, String column, String referencedTable,
                             String referencedColumn) {
    }

    /** {@code XxxID} 这类列名。捕获 {@code Xxx} 部分。 */
    private static final Pattern ID_COLUMN = Pattern.compile("^(.*?)(?i:_?id)$");

    /**
     * 推断关联。
     *
     * @param tables      参与建模的表
     * @param columnsOf   表名 → 该表的列
     * @param foreignKeys  JDBC 读到的主外键
     * @return 关联候选。**可能包含同一对表的多条候选**（命名歧义），调用方要全列给人选
     */
    public static List<JoinCandidate> infer(List<TableMeta> tables,
                                            Map<String, List<ColumnMeta>> columnsOf,
                                            List<ForeignKey> foreignKeys) {
        List<JoinCandidate> out = new ArrayList<>();
        out.addAll(fromForeignKeys(foreignKeys));
        out.addAll(fromNaming(tables, columnsOf, foreignKeys));
        return out;
    }

    /** 第一档：外键，直接用。 */
    private static List<JoinCandidate> fromForeignKeys(List<ForeignKey> foreignKeys) {
        List<JoinCandidate> out = new ArrayList<>();
        if (foreignKeys == null) {
            return out;
        }
        for (ForeignKey fk : foreignKeys) {
            out.add(new JoinCandidate(fk.table(), fk.column(),
                    fk.referencedTable(), fk.referencedColumn(),
                    Confidence.FOREIGN_KEY,
                    "JDBC 外键约束：" + fk.table() + "." + fk.column()
                            + " → " + fk.referencedTable() + "." + fk.referencedColumn()));
        }
        return out;
    }

    /**
     * 第二档：命名推断。
     *
     * <p>只在「外键没有覆盖到这一列」时才推——已经由外键确定的关联不重复给出，
     * 否则同一个关系会出现两条候选，管理员还得去重。
     */
    private static List<JoinCandidate> fromNaming(List<TableMeta> tables,
                                                  Map<String, List<ColumnMeta>> columnsOf,
                                                  List<ForeignKey> foreignKeys) {
        List<JoinCandidate> out = new ArrayList<>();

        // 表名小写 → 实际表名。库在大小写上的行为各异，比对时统一折小写
        Map<String, String> tableByLower = new java.util.HashMap<>();
        for (TableMeta t : tables) {
            tableByLower.put(t.name().toLowerCase(Locale.ROOT), t.name());
        }

        java.util.Set<String> alreadyLinked = new java.util.HashSet<>();
        if (foreignKeys != null) {
            for (ForeignKey fk : foreignKeys) {
                alreadyLinked.add(key(fk.table(), fk.column()));
            }
        }

        for (TableMeta table : tables) {
            List<ColumnMeta> columns = columnsOf.getOrDefault(table.name(), List.of());
            for (ColumnMeta column : columns) {
                if (column.primary()) {
                    continue;   // 自己的主键不推关联
                }
                if (alreadyLinked.contains(key(table.name(), column.name()))) {
                    continue;
                }
                Matcher m = ID_COLUMN.matcher(column.name());
                if (!m.matches()) {
                    continue;
                }
                String stem = m.group(1);
                if (stem.isEmpty()) {
                    continue;   // 列名就叫 "id"，推不出指向谁
                }

                List<JoinCandidate> candidates =
                        resolveStem(stem, table, column, tableByLower, columnsOf);
                // **全列出，不挑一个**——见类注释
                out.addAll(candidates);
            }
        }
        return out;
    }

    /**
     * 把一个 {@code XxxID} 的 {@code Xxx} 解析成候选表。
     *
     * <p>尝试的形态：{@code xxx}、{@code xxxs}（简单复数）、{@code dim_xxx}、
     * {@code xxx_dim}。真实库的命名五花八门，只试一种会漏。
     */
    private static List<JoinCandidate> resolveStem(String stem, TableMeta from, ColumnMeta column,
                                                   Map<String, String> tableByLower,
                                                   Map<String, List<ColumnMeta>> columnsOf) {
        String s = stem.toLowerCase(Locale.ROOT);
        List<String> candidates = List.of(s, s + "s", "dim_" + s, s + "_dim", "t_" + s);

        List<JoinCandidate> out = new ArrayList<>();
        for (String candidate : candidates) {
            String actual = tableByLower.get(candidate);
            if (actual == null || actual.equalsIgnoreCase(from.name())) {
                continue;
            }
            String pk = primaryKeyOf(actual, columnsOf);
            if (pk == null) {
                continue;   // 对不上主键的推不出关联，不推
            }
            out.add(new JoinCandidate(from.name(), column.name(), actual, pk,
                    Confidence.NAMING,
                    "列名 " + column.name() + " 匹配表 " + actual + " 的主键 " + pk
                            + "（按命名推断，需确认）"));
        }
        return out;
    }

    /**
     * 取表的主键列名。
     *
     * <p>复合主键时返回第一个——单列 JOIN 表达不了复合键，那属于人工处理范围。
     */
    private static String primaryKeyOf(String table, Map<String, List<ColumnMeta>> columnsOf) {
        List<ColumnMeta> columns = columnsOf.get(table);
        if (columns == null) {
            return null;
        }
        return columns.stream()
                .filter(ColumnMeta::primary)
                .map(ColumnMeta::name)
                .findFirst()
                .orElse(null);
    }

    private static String key(String table, String column) {
        return (table + "." + column).toLowerCase(Locale.ROOT);
    }
}

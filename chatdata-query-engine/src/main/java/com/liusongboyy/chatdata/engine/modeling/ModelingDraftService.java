package com.liusongboyy.chatdata.engine.modeling;

import com.liusongboyy.chatdata.engine.datasource.DatasourceResolver;
import com.liusongboyy.chatdata.engine.datasource.MetaReader;
import com.liusongboyy.chatdata.engine.datasource.MetaReader.ColumnMeta;
import com.liusongboyy.chatdata.engine.datasource.MetaReader.TableMeta;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从数据源生成域草稿。
 *
 * <h2>为什么是「草稿」</h2>
 *
 * <p>推断必然有错：把 {@code Description} 当枚举、把业务编码 {@code ProductID}
 * 当外键，都发生过。<b>推断错的东西直接生效，就是这个项目最怕的那种失败</b>——
 * 不报错、只是数字不对。
 *
 * <p>所以流程只能是：生成 → 人看 → 确认 → 落库。每条推断都带 {@code reason}，
 * 管理员能判断"这条为什么这么建议"。
 *
 * <h2>不新增写接口</h2>
 *
 * <p>确认后复用现有的 {@code createDomain / createTable / saveJoins}。
 * 出现"草稿路径"与"手工路径"两套写入逻辑，早晚会分叉。
 */
public class ModelingDraftService {

    private static final Logger log = LoggerFactory.getLogger(ModelingDraftService.class);

    /** 基数采样的行数上限。基数判断只需要量级，不需要精确值 */
    private static final int CARDINALITY_SAMPLE_ROWS = 5000;

    /** 单列采样的超时。单列失败不阻断整表 */
    private static final int CARDINALITY_TIMEOUT_SECONDS = 10;

    private final MetaReader metaReader;
    private final DatasourceResolver resolver;

    public ModelingDraftService(MetaReader metaReader, DatasourceResolver resolver) {
        this.metaReader = metaReader;
        this.resolver = resolver;
    }

    // ─────────────────────────────────────────────────────────────
    // 草稿结构。字段与 spec §5 对齐
    // ─────────────────────────────────────────────────────────────

    /**
     * 一份域草稿。
     *
     * @param tables    表草稿
     * @param joins     关联候选（**可能同一对表有多条**，命名歧义时全列出）
     * @param warnings  需要管理员注意的事
     * @param stats     统计信息，用来看性能是否符合预期
     */
    public record Draft(List<TableDraft> tables, List<JoinInferrer.JoinCandidate> joins,
                        List<String> warnings, Stats stats) {
    }

    /**
     * 一张表的草稿。
     */
    public record TableDraft(String name, String label, String mode,
                             List<ColumnDraft> columns) {
        public TableDraft {
            columns = List.copyOf(columns);
        }
    }

    /**
     * 一列的草稿。
     *
     * @param alias             业务名候选
     * @param aliasConfirmed    **关键字段**：false 表示这是机器生成的，请确认或改掉
     * @param enabled           是否启用。敏感列默认 false
     * @param confidence        置信度
     * @param reason            为什么这么推断
     */
    public record ColumnDraft(String col, String type, List<Aggregation> agg,
                              String alias, boolean aliasConfirmed,
                              boolean enabled, String confidence, String reason,
                              String role) {
        public ColumnDraft {
            agg = List.copyOf(agg);
        }
    }

    /**
     * 统计。
     *
     * @param elapsedMs 总耗时。验收要求 5 张表 &lt; 3 秒
     */
    public record Stats(int tables, int columns, int joins, long elapsedMs) {
    }

    /**
     * 生成草稿。<b>不落库。</b>
     *
     * @param datasourceId 数据源 ID
     * @param tableNames   要建模的表。null 或空表示全部
     */
    public Draft generate(String datasourceId, List<String> tableNames) {
        long start = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();

        // ① 读元数据
        List<TableMeta> allTables = metaReader.listTables(datasourceId);
        List<TableMeta> selected = selectTables(allTables, tableNames);

        if (selected.isEmpty()) {
            return new Draft(List.of(), List.of(),
                    List.of("选中的表在数据源里不存在，或数据源里没有任何表"),
                    new Stats(0, 0, 0, System.currentTimeMillis() - start));
        }

        // ② 逐表读列
        Map<String, List<ColumnMeta>> columnsOf = new LinkedHashMap<>();
        for (TableMeta table : selected) {
            try {
                List<ColumnMeta> columns = metaReader.listColumns(datasourceId, table.name());
                columnsOf.put(table.name(), columns);
                if (columns.isEmpty()) {
                    warnings.add("表 " + table.name() + " 读不到列定义，已跳过");
                }
            } catch (RuntimeException e) {
                // 单表失败不阻断整次生成——一张表没权限不该让整个草稿失败
                warnings.add("表 " + table.name() + " 读取失败，已跳过：" + e.getMessage());
                log.warn("读取表 {} 的列失败", table.name(), e);
            }
        }

        // ③ 基数采样（只对文本列，见 spec §4.4）
        Map<String, Integer> cardinalities = estimateCardinalities(datasourceId, columnsOf, warnings);

        // ④ 逐列推断
        List<TableDraft> tableDrafts = new ArrayList<>();
        int totalColumns = 0;
        int sensitiveCount = 0;
        for (TableMeta table : selected) {
            List<ColumnMeta> columns = columnsOf.get(table.name());
            if (columns == null || columns.isEmpty()) {
                continue;
            }
            List<ColumnDraft> columnDrafts = new ArrayList<>();
            for (ColumnMeta column : columns) {
                int card = cardinalities.getOrDefault(key(table.name(), column.name()), -1);
                ColumnInferrer.Inference inf = ColumnInferrer.infer(column, card);
                boolean sensitive = inf.role() == ColumnInferrer.Role.SENSITIVE;
                if (sensitive) {
                    sensitiveCount++;
                }
                // **一条推断可能展开成多条登记（双登记）。**
                //
                // 语义模型里一个物理列可以有多个身份：
                //   · 维度：能分组、能当筛选条件（`WHERE term_years = 10`）
                //   · 指标：能聚合（`AVG(term_years)`、`COUNT(DISTINCT province)`）
                //
                // 只登记一种会丢掉另一种问法：只当指标则「保障年限为 10 年的保单」
                // 永远问不出来（实测日志里有 13 次这种失败）；
                // 只当维度则「平均保障年限是多少」问不出来。
                //
                // 实测：AdventureWorks 用单登记建完，覆盖度报 23 条建议，
                // 其中 33 个分类列缺 COUNT_DISTINCT。
                columnDrafts.addAll(expand(column, inf, sensitive));
            }
            tableDrafts.add(new TableDraft(
                    table.name(),
                    table.comment() == null || table.comment().isBlank()
                            ? table.name() : table.comment(),
                    "TABLE",
                    columnDrafts));
            totalColumns += columnDrafts.size();
        }

        // ⑤ 表间关联
        List<JoinInferrer.JoinCandidate> joins =
                JoinInferrer.infer(selected, columnsOf, List.of());

        // ⑥ 汇总告警
        if (sensitiveCount > 0) {
            warnings.add(sensitiveCount + " 个列命中敏感列规则，已默认关闭");
        }
        long ambiguous = joins.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        j -> j.left() + "." + j.leftColumn(),
                        java.util.stream.Collectors.counting()))
                .values().stream().filter(c -> c > 1).count();
        if (ambiguous > 0) {
            warnings.add(ambiguous + " 个列有多个关联候选，需人工选择（挑错会导致数字不对但不报错）");
        }
        long unconfirmed = tableDrafts.stream()
                .flatMap(t -> t.columns().stream())
                .filter(c -> !c.aliasConfirmed() && c.enabled())
                .count();
        if (unconfirmed > 0) {
            warnings.add(unconfirmed + " 个列的业务名是机器生成的，上线前请确认或修改");
        }

        return new Draft(tableDrafts, joins, warnings,
                new Stats(tableDrafts.size(), totalColumns, joins.size(),
                        System.currentTimeMillis() - start));
    }

    // ─────────────────────────────────────────────────────────────

    /**
     * 把一条推断展开成一条或多条列登记。
     *
     * <h2>维度与指标为什么必须分开登记</h2>
     *
     * <p>语义模型里「维度」和「指标」是两个身份，同一个物理列可以同时具备：
     *
     * <table>
     *   <tr><th>身份</th><th>能回答什么</th></tr>
     *   <tr><td>维度</td><td>「保障年限为 10 年的保单」——当筛选条件</td></tr>
     *   <tr><td>指标</td><td>「平均保障年限是多少」——当聚合对象</td></tr>
     * </table>
     *
     * <p>只登记一种就丢掉另一种问法。实测日志里「配了聚合方式，不能作为维度」
     * 出现 52 次——那正是只登记了指标、没登记维度的后果。
     *
     * <h2>别名怎么区分</h2>
     *
     * <p>维度用原注释/列名；指标在名字前加语义前缀（「平均X」「X数」「X合计」），
     * 让管理员一眼看出这是同列的另一条登记，而不是重复项。
     */
    private static List<ColumnDraft> expand(ColumnMeta column, ColumnInferrer.Inference inf,
                                            boolean sensitive) {
        String baseAlias = ColumnInferrer.aliasCandidate(column);
        boolean aliasOk = !ColumnInferrer.aliasNeedsConfirmation(column) && !sensitive;

        // 敏感列不展开：关掉就都关掉，不给它两条记录让管理员误以为只关了一半
        if (sensitive || inf.aggregations().isEmpty()) {
            return List.of(new ColumnDraft(column.name(), column.type(),
                    inf.aggregations(), baseAlias, aliasOk, !sensitive,
                    inf.confidence().name(), inf.reason(), inf.role().name()));
        }

        List<ColumnDraft> out = new ArrayList<>();

        // ① 维度那条。**先加**——它是筛选条件的基础，
        //    管理员看草稿时先看到「这一列能用来筛选」更符合直觉
        out.add(new ColumnDraft(column.name(), column.type(), List.of(),
                baseAlias, aliasOk, true,
                inf.confidence().name(),
                inf.reason() + "；本条为维度（可用于筛选与分组）",
                ColumnInferrer.Role.DIMENSION.name()));

        // ② 指标那条（或几条）。每个聚合一条——同一列出现两条 SUM 没有意义，
        //    但 SUM 与 AVG 的语义不同，都要给
        for (Aggregation agg : inf.aggregations()) {
            out.add(new ColumnDraft(column.name(), column.type(), List.of(agg),
                    metricAlias(baseAlias, agg), aliasOk, true,
                    inf.confidence().name(),
                    inf.reason() + "；本条为指标（" + agg + "）",
                    ColumnInferrer.Role.METRIC.name()));
        }
        return out;
    }

    /**
     * 指标那条的别名。
     *
     * <p>加语义前缀而不是加后缀「（指标）」：管理员和模型看的都是这个名字，
     * 「平均保障年限」比「保障年限（AVG）」更接近人话，
     * 而模型选字段时靠的正是名字的语义。
     */
    private static String metricAlias(String base, Aggregation agg) {
        return switch (agg) {
            case AVG -> "平均" + base;
            case SUM -> base + "合计";
            case COUNT -> base + "计数";
            case COUNT_DISTINCT -> base + "去重数";
            case MAX -> "最大" + base;
            case MIN -> "最小" + base;
            case NONE -> base;
        };
    }

    private static List<TableMeta> selectTables(List<TableMeta> all, List<String> wanted) {
        if (wanted == null || wanted.isEmpty()) {
            return all;
        }
        Set<String> lower = new LinkedHashSet<>();
        for (String w : wanted) {
            if (w != null && !w.isBlank()) {
                lower.add(w.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return all.stream()
                .filter(t -> lower.contains(t.name().toLowerCase(java.util.Locale.ROOT)))
                .toList();
    }

    /**
     * 估计文本列的基数。
     *
     * <p>采样而非全表：基数判断只需要量级。单列失败不阻断——权限或超时都应该
     * 只让那一列标记"未采样"，而不是让整次生成失败。
     */
    private Map<String, Integer> estimateCardinalities(String datasourceId,
                                                       Map<String, List<ColumnMeta>> columnsOf,
                                                       List<String> warnings) {
        Map<String, Integer> out = new LinkedHashMap<>();
        DataSource ds;
        try {
            ds = resolver.resolve(datasourceId);
        } catch (RuntimeException e) {
            warnings.add("基数采样不可用（数据源解析失败），全部列按未采样处理：" + e.getMessage());
            return out;
        }

        int failures = 0;
        for (Map.Entry<String, List<ColumnMeta>> e : columnsOf.entrySet()) {
            String table = e.getKey();
            for (ColumnMeta column : e.getValue()) {
                // 只对文本列采样：数值列的基数对角色判断没帮助
                if (!looksTextual(column.type())) {
                    continue;
                }
                try {
                    out.put(key(table, column.name()),
                            countDistinct(ds, table, column.name()));
                } catch (Exception ex) {
                    failures++;
                    log.debug("采样 {}.{} 失败：{}", table, column.name(), ex.getMessage());
                }
            }
        }
        if (failures > 0) {
            warnings.add(failures + " 个列采样失败，按未采样处理（不影响其他列）");
        }
        return out;
    }

    private static int countDistinct(DataSource ds, String table, String column) throws Exception {
        // 先采样再计数：直接 COUNT(DISTINCT) 大表会扫全表
        String sql = "SELECT COUNT(DISTINCT t.c) FROM (SELECT " + quote(column) + " AS c FROM "
                + quote(table) + " LIMIT " + CARDINALITY_SAMPLE_ROWS + ") t";
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.setQueryTimeout(CARDINALITY_TIMEOUT_SECONDS);
            try (ResultSet rs = st.executeQuery(sql)) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    /** 表名/列名的引号。反引号兼容 MySQL 与 H2；PostgreSQL 会不认，届时按方言改 */
    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private static boolean looksTextual(String type) {
        if (type == null) {
            return false;
        }
        String t = type.toLowerCase(java.util.Locale.ROOT);
        return t.contains("char") || t.contains("text") || t.contains("enum")
                || t.contains("string");
    }

    private static String key(String table, String column) {
        return table + "\u0000" + column;
    }
}

package com.liusongboyy.chatdata.engine.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 把库里的行解析成 {@link Domain}。
 *
 * <h2>解析失败时抛出，并且带上足够的上下文</h2>
 *
 * <p>坏配置的报错如果只说"解析失败"，运维得逐个域去试才能找到是哪个。
 * 故每条错误都带域 id 与名称。
 *
 * <h2>未知字段的容忍度</h2>
 *
 * <p>JSON 里多出来的键**忽略**（Jackson 默认行为），少了的键走默认值。
 * 理由是这份配置由管理页写入，字段会随功能演进增减；
 * 因为多了一个不认识的键就拒绝加载整个域，会让升级变得很难受。
 * 但**取值非法**（如聚合方式写错）仍然抛异常——那会渲染出错误的 SQL。
 */
public final class DomainLoader {

    private static final Logger log = LoggerFactory.getLogger(DomainLoader.class);
    private static final ObjectMapper M = new ObjectMapper();

    private DomainLoader() {
    }

    /**
     * 组装一个域（不含术语与主题）。
     *
     * <p>保留这个重载是为了让只关心表与权限的调用方（含测试）不必构造空列表。
     */
    public static Domain load(DomainRow row, List<DomainTableRow> tables, String dbType) {
        return load(row, tables, List.of(), List.of(), dbType);
    }

    /**
     * 组装一个域。
     *
     * @param row    域行
     * @param tables 该域下的注册表行
     * @param terms  该域下的术语行
     * @param topics 该域下的主题行
     * @param dbType 绑定数据源的类型；数据源不存在时为 null
     */
    public static Domain load(DomainRow row, List<DomainTableRow> tables,
                              List<TermRow> terms, List<TopicRow> topics, String dbType) {
        String domainId = String.valueOf(row.id());

        List<RegisteredTable> registered = new ArrayList<>(tables.size());
        for (DomainTableRow t : tables) {
            registered.add(loadTable(domainId, row.name(), t));
        }

        return new Domain(
                domainId,
                row.name(),
                row.datasourceId() == null ? null : String.valueOf(row.datasourceId()),
                dbType,
                row.enabled(),
                registered,
                loadJoins(domainId, row.name(), row.joinsJson()),
                loadPermission(domainId, row.name(), row.permJson()),
                loadTerms(domainId, row.name(), terms),
                loadTopics(domainId, row.name(), topics),
                Domain.Mode.parse(row.mode()));
    }

    private static RegisteredTable loadTable(String domainId, String domainName, DomainTableRow row) {
        // 显示名与物理名都给出来：前者是管理员在界面上看到的，后者是库里的。
        // 只给一个的话，另一边的人得先做一次翻译才能定位。
        String tableDesc = (row.label() == null || row.label().isBlank() || row.label().equals(row.name()))
                ? row.name()
                : row.label() + "(" + row.name() + ")";
        String where = "域「%s」(id=%s) 的注册表「%s」(id=%d)"
                .formatted(domainName, domainId, tableDesc, row.id());
        try {
            return new RegisteredTable(
                    String.valueOf(row.id()),
                    row.name(),
                    row.label(),
                    parseMode(row.mode(), where),
                    row.sqlText(),
                    row.enabled(),
                    loadColumns(row.columnsJson(), where));
        } catch (SemanticException e) {
            // 子构造器（ColumnDef / RegisteredTable）抛的异常只有局部信息，
            // 如"列定义缺少物理列名"——不知道是哪个域哪张表。
            // 这里补上上下文，否则运维得逐个域去试才能定位。
            throw new SemanticException(e.reason(), where + "：" + e.getMessage());
        } catch (Exception e) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    where + " 加载失败：" + e.getMessage());
        }
    }

    private static RegisteredTable.Mode parseMode(String raw, String where) {
        if (raw == null || raw.isBlank()) {
            return RegisteredTable.Mode.TABLE;
        }
        String m = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (m) {
            case "TABLE" -> RegisteredTable.Mode.TABLE;
            case "SQL" -> RegisteredTable.Mode.SQL;
            default -> throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    where + " 的 mode 非法：" + raw + "（只支持 TABLE / SQL）");
        };
    }

    private static List<ColumnDef> loadColumns(String json, String where) throws Exception {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode arr = M.readTree(json);
        if (!arr.isArray()) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    where + " 的 columns_json 不是数组");
        }
        List<ColumnDef> cols = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            JsonNode dv = n.path("derived");
            DerivedMetric derived = dv.isMissingNode() || dv.isNull()
                    ? null
                    : DerivedMetric.from(dv);
            MetricCondition condition = MetricCondition.from(n.path("condition"));
            cols.add(new ColumnDef(
                    text(n, "col"),
                    text(n, "type"),
                    // 未知聚合方式在这里抛异常。回落 NONE 会把本该聚合的指标
                    // 当维度渲染进 GROUP BY——查询能跑通，但结果是错的
                    Aggregation.parse(text(n, "agg")),
                    text(n, "alias"),
                    // enabled 缺省视为**可见**。注意这与助手权限的 enabled 缺省相反——
                    // 那里缺省是拒绝。这里的语义是"列默认参与问数"，
                    // 而权限那层是"默认不放行"，两者的方向本就不同。
                    !n.has("enabled") || n.path("enabled").asBoolean(true),
                    derived,
                    condition,
                    text(n, "description"),
                    stringListOf(n.path("samples"))));
        }
        return cols;
    }

    private static List<TableJoin> loadJoins(String domainId, String domainName, String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode arr = M.readTree(json);
            if (!arr.isArray()) {
                log.warn("域「{}」(id={}) 的 joins_json 不是数组，按无关联处理", domainName, domainId);
                return List.of();
            }
            List<TableJoin> joins = new ArrayList<>(arr.size());
            for (JsonNode n : arr) {
                joins.add(TableJoin.from(n));
            }
            return joins;
        } catch (Exception e) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "域「%s」(id=%s) 的 joins_json 解析失败：%s".formatted(domainName, domainId, e.getMessage()));
        }
    }

    private static DomainPermission loadPermission(String domainId, String domainName, String json) {
        if (json == null || json.isBlank()) {
            // 没有权限配置 = 没有任何授权。DomainPermission.from(null) 会构造出
            // 空授权 + isPublic=false，即**失败关闭**（厂商此处是对所有已认证用户开放）。
            log.warn("域「{}」(id={}) 没有权限配置，按拒绝处理", domainName, domainId);
            return DomainPermission.from(null);
        }
        try {
            return DomainPermission.from(M.readTree(json));
        } catch (Exception e) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "域「%s」(id=%s) 的 perm_json 解析失败：%s".formatted(domainName, domainId, e.getMessage()));
        }
    }

    /** 取值样例数组。缺失或不是数组都当作没有——它只是 prompt 的辅助信息。 */
    private static List<String> stringListOf(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            String v = item.asText();
            if (v != null && !v.isBlank()) {
                out.add(v.trim());
            }
        }
        return out;
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static List<Term> loadTerms(String domainId, String domainName, List<TermRow> rows) {
        List<Term> out = new ArrayList<>();
        for (TermRow r : rows) {
            String where = "域「%s」(id=%s) 的术语「%s」(id=%d)"
                    .formatted(domainName, domainId, r.name(), r.id());
            try {
                out.add(new Term(r.name(), parseStringList(r.synonymsJson(), where),
                        r.description(), Term.parseApplyKind(r.applyKind())));
            } catch (SemanticException e) {
                throw new SemanticException(e.reason(), where + "：" + e.getMessage());
            }
        }
        return out;
    }

    private static List<Topic> loadTopics(String domainId, String domainName, List<TopicRow> rows) {
        List<Topic> out = new ArrayList<>();
        for (TopicRow r : rows) {
            String where = "域「%s」(id=%s) 的主题「%s」(id=%d)"
                    .formatted(domainName, domainId, r.name(), r.id());
            try {
                out.add(new Topic(r.name(), r.description(), r.published(),
                        parseStringList(r.fieldsJson(), where)));
            } catch (SemanticException e) {
                throw new SemanticException(e.reason(), where + "：" + e.getMessage());
            }
        }
        return out;
    }

    /** 解析字符串数组。坏 JSON 抛异常并带上位置——静默丢掉等于悄悄改配置。 */
    private static List<String> parseStringList(String json, String where) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode arr = M.readTree(json);
            if (!arr.isArray()) {
                throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                        where + " 的 JSON 不是数组");
            }
            List<String> out = new ArrayList<>();
            arr.forEach(n -> {
                String v = n.asText();
                if (v != null && !v.isBlank()) {
                    out.add(v.trim());
                }
            });
            return out;
        } catch (SemanticException e) {
            throw e;
        } catch (Exception e) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    where + " 的 JSON 解析失败：" + e.getMessage());
        }
    }
}

package com.liusongboyy.chatdata.engine.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.liusongboyy.chatdata.engine.semantic.ColumnProfile;
import com.liusongboyy.chatdata.engine.semantic.ColumnProfiler;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.DomainLoader;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量给一个域的所有注册表做列画像，并把结果写回 {@code columns_json}。
 *
 * <h2>为什么是一次批量、而不是每列点一下</h2>
 *
 * <p>画像是"配置一次、长期受益"的维护动作：管理员建完域之后跑一遍就够。
 * 逐列触发会让这件事永远做不完——而它的收益恰恰来自**覆盖面**：
 * BIRD 的 +7.3 个百分点是"所有列都有注释"时的数字，只有一半有就只剩一半。
 *
 * <h2>只写 description 与 samples，不碰别的</h2>
 *
 * <p>画像结果回填时**逐键合并**：别名、聚合方式、派生定义、统计条件全部原样保留。
 * 整段替换会把管理员配的东西冲掉，而那种损失不会报错——只是某天某个指标没了。
 */
public class AdminColumnProfileService {

    private static final Logger log = LoggerFactory.getLogger(AdminColumnProfileService.class);
    private static final ObjectMapper M = new ObjectMapper();

    /** 描述长度上限。太长的描述会把 prompt 撑长，收益却递减。 */
    private static final int DESC_MAX = 200;

    private final AdminDomainService domains;
    private final com.liusongboyy.chatdata.engine.datasource.MetaReader metaReader;
    private final com.liusongboyy.chatdata.engine.datasource.DatasourceResolver datasources;
    private final ColumnProfiler profiler;

    public AdminColumnProfileService(AdminDomainService domains,
                                     com.liusongboyy.chatdata.engine.datasource.MetaReader metaReader,
                                     com.liusongboyy.chatdata.engine.datasource.DatasourceResolver datasources,
                                     ColumnProfiler profiler) {
        this.domains = domains;
        this.metaReader = metaReader;
        this.datasources = datasources;
        this.profiler = profiler;
    }

    /**
     * 一次画像的结果。
     *
     * @param tables  每张表的处理结果
     * @param columns 实际写入描述的列数
     */
    public record ProfileResult(List<TableProfile> tables, int columns) {
    }

    public record TableProfile(String table, int columns, String error) {
    }

    /**
     * 给整个域做画像。
     *
     * @param domainId 域 id
     * @param operator 操作人，进日志
     */
    public ProfileResult profileDomain(long domainId, String operator) {
        var domainRow = domains.get(domainId);
        String dsId = domainRow.datasourceId() == null ? null : String.valueOf(domainRow.datasourceId());
        var tables = domains.listTables(domainId);

        List<TableProfile> results = new ArrayList<>();
        int totalColumns = 0;

        for (com.liusongboyy.chatdata.engine.semantic.DomainTableRow row : tables) {
            if (!row.enabled()) {
                continue;
            }
            try {
                int written = profileTable(domainRow, dsId, row, operator);
                results.add(new TableProfile(row.label() == null ? row.name() : row.label(),
                        written, null));
                totalColumns += written;
            } catch (RuntimeException e) {
                // 一张表失败不影响其余：画像的价值在覆盖面，能补一张是一张
                log.warn("表画像失败：domain={} table={} 原因={}",
                        domainId, row.name(), e.getMessage());
                results.add(new TableProfile(row.name(), 0, e.getMessage()));
            }
        }
        log.info("列画像完成：domain={} by={} 表 {} 张，列 {} 个",
                domainId, operator, results.size(), totalColumns);
        return new ProfileResult(results, totalColumns);
    }

    /** 处理一张表：画像 → 生成描述 → 写回 columns_json。 */
    private int profileTable(com.liusongboyy.chatdata.engine.semantic.DomainRow domainRow,
                             String datasourceId,
                             com.liusongboyy.chatdata.engine.semantic.DomainTableRow row,
                             String operator) {
        Domain domain = DomainLoader.load(domainRow, domains.listTables(domainRow.id()),
                List.of(), List.of(), null);
        RegisteredTable table = domain.tableById(String.valueOf(row.id()))
                .orElseThrow(() -> new AdminException("注册表不存在：" + row.id()));

        List<ColumnProfile> profiles = profiler.profile(datasourceId, table);
        if (profiles.isEmpty()) {
            return 0;
        }

        Map<String, ColumnProfile> byColumn = new LinkedHashMap<>();
        profiles.forEach(p -> byColumn.put(p.column(), p));

        // 逐键合并：只改 description 与 samples，其余原样保留
        JsonNode existing;
        try {
            existing = row.columnsJson() == null || row.columnsJson().isBlank()
                    ? M.createArrayNode() : M.readTree(row.columnsJson());
        } catch (Exception e) {
            throw new AdminException("这张表的列定义不是合法 JSON，已跳过：" + e.getMessage());
        }

        int written = 0;
        for (JsonNode node : existing) {
            if (!(node instanceof ObjectNode col)) {
                continue;
            }
            ColumnProfile p = byColumn.get(col.path("col").asText());
            if (p == null) {
                continue;   // 派生指标没有物理列，不画像
            }
            String desc = describe(col, p);
            if (desc != null) {
                col.put("description", desc);
            }
            if (!p.samples().isEmpty()) {
                ArrayNode sa = col.putArray("samples");
                p.samples().forEach(sa::add);
            }
            written++;
        }

        // 走管理服务保存，复用它的表名校验与权限检查
        domains.updateTableColumns(row.id(), existing.toString(), operator);
        return written;
    }

    /**
     * 生成一句描述。
     *
     * <h2>为什么是拼装而不是再调一次模型</h2>
     *
     * <p>画像已经给了确定的信息：类型、基数、取值。拼出来的描述
     * （"工单状态，取值 OPEN、CLOSED、RESOLVED，共 3 种"）**准确且零成本**；
     * 再调模型只会让它把同样的事实换个说法，还多一次失败可能。
     *
     * <p>模型该在的地方是"把 20 个字段串成一段业务说明"，那是另一件事。
     */
    private static String describe(ObjectNode col, ColumnProfile p) {
        String alias = col.path("alias").asText("");
        String name = alias.isBlank() ? p.column() : alias;

        StringBuilder sb = new StringBuilder(name).append("（").append(p.type()).append("）");
        if (!p.samples().isEmpty() && p.isCategorical()) {
            sb.append("，取值 ").append(String.join("、", p.samples()));
        } else if (!p.samples().isEmpty()) {
            sb.append("，如 ").append(String.join("、", p.samples()));
        }
        if (p.distinctCount() > 0) {
            sb.append("，共 ").append(p.distinctCount()).append(" 种");
        }
        String desc = sb.toString();
        return desc.length() <= DESC_MAX ? desc : desc.substring(0, DESC_MAX) + "…";
    }
}

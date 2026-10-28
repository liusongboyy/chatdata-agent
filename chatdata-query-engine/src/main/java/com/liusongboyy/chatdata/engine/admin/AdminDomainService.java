package com.liusongboyy.chatdata.engine.admin;

import com.liusongboyy.chatdata.engine.admin.mapper.SemanticAdminMapper;
import com.liusongboyy.chatdata.engine.datasource.MetaReader;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.DomainLoader;
import com.liusongboyy.chatdata.engine.semantic.DomainRow;
import com.liusongboyy.chatdata.engine.semantic.DomainTableRow;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import com.liusongboyy.chatdata.engine.semantic.TableJoin;
import com.liusongboyy.chatdata.engine.semantic.TermRow;
import com.liusongboyy.chatdata.engine.semantic.TopicRow;
import com.liusongboyy.chatdata.engine.semantic.mapper.SemanticConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 语义域与域内注册表的管理。
 *
 * <h2>删除域的连带处理</h2>
 *
 * <p>删域时**连同它的注册表一起删**。表上没建外键，不做这件事会留下孤儿行——
 * 它们不影响功能，但会让"这个库里的表都是干什么的"变得说不清，
 * 而且下次有人建一个同 id 段的域时可能撞上。
 */
public class AdminDomainService {

    private static final Logger log = LoggerFactory.getLogger(AdminDomainService.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final SemanticConfigMapper readMapper;
    private final SemanticAdminMapper writeMapper;
    private final MetaReader metaReader;

    public AdminDomainService(SemanticConfigMapper readMapper, SemanticAdminMapper writeMapper,
                              MetaReader metaReader) {
        this.readMapper = readMapper;
        this.writeMapper = writeMapper;
        this.metaReader = metaReader;
    }

    // ── 域 ──

    /** 全部域（含停用的）。 */
    public List<DomainRow> list() {
        return readMapper.selectAllDomains();
    }

    public DomainRow get(long id) {
        DomainRow row = readMapper.selectDomainById(id);
        if (row == null) {
            throw new AdminException("数据域不存在：id=" + id);
        }
        return row;
    }

    public long create(DomainInput input, String operator) {
        DomainInput v = input.validated();
        requireNameAvailable(v.name(), null);

        writeMapper.insertDomain(v.name(), v.description(), v.datasourceId(), v.status(),
                v.mode(), v.joinsJson(), v.permJson(), v.sortNo(), operator);

        return list().stream()
                .filter(d -> d.name().equals(v.name()))
                .map(DomainRow::id)
                .findFirst()
                .orElseThrow(() -> new AdminException("新建数据域后未能读回，请刷新列表确认"));
    }

    public void update(long id, DomainInput input, String operator) {
        get(id);   // 不存在就报错，不要静默什么都不做
        DomainInput v = input.validated();
        requireNameAvailable(v.name(), id);

        writeMapper.updateDomain(id, v.name(), v.description(), v.datasourceId(), v.status(),
                v.mode(), v.joinsJson(), v.permJson(), v.sortNo(), operator);
        log.info("数据域已更新：id={} name={} by={}", id, v.name(), operator);
    }

    /** 删除域及其全部注册表。 */
    public void delete(long id, String operator) {
        DomainRow row = get(id);

        int tables = writeMapper.countTablesInDomain(id);
        // 先删子表再删主表。顺序反了会短暂出现"域没了但表还在"的状态，
        // 而那个状态下启动校验会报一堆 BROKEN_JOIN_REF 之类的噪音
        writeMapper.deleteTablesByDomain(id);
        // 术语与主题同样要连带删——表上没建外键，留着就是孤儿行
        writeMapper.deleteTermsByDomain(id);
        writeMapper.deleteTopicsByDomain(id);
        writeMapper.deleteDomain(id);

        log.info("数据域已删除：id={} name={} 连带 {} 张注册表 by={}", id, row.name(), tables, operator);
    }

    // ── 注册表 ──

    public List<DomainTableRow> listTables(long domainId) {
        get(domainId);   // 域不存在就直接报，别返回空列表让调用方以为"这个域没表"
        return readMapper.selectTablesByDomain(domainId);
    }

    public long createTable(long domainId, DomainTableInput input, String operator) {
        get(domainId);
        DomainTableInput v = input.validated();
        requireTableNameAvailable(domainId, v.name(), null);
        validateAgainstRealSchema(domainId, v);

        writeMapper.insertDomainTable(domainId, v.name(), v.label(), v.mode(), v.sqlText(),
                v.columnsJson(), v.enabled(), v.sortNo(), operator);

        return listTables(domainId).stream()
                .filter(t -> t.name().equals(v.name()))
                .map(DomainTableRow::id)
                .findFirst()
                .orElseThrow(() -> new AdminException("新建注册表后未能读回，请刷新列表确认"));
    }

    public void updateTable(long tableId, DomainTableInput input, String operator) {
        DomainTableRow existing = readMapper.selectTablesByDomain(findDomainIdOf(tableId)).stream()
                .filter(t -> t.id() == tableId)
                .findFirst()
                .orElseThrow(() -> new AdminException("注册表不存在：id=" + tableId));

        DomainTableInput v = input.validated();
        requireTableNameAvailable(existing.domainId(), v.name(), tableId);
        validateAgainstRealSchema(existing.domainId(), v);

        writeMapper.updateDomainTable(tableId, v.name(), v.label(), v.mode(), v.sqlText(),
                v.columnsJson(), v.enabled(), v.sortNo(), operator);
        log.info("注册表已更新：id={} name={} by={}", tableId, v.name(), operator);
    }

    /**
     * 只更新某张表的 {@code columns_json}。
     *
     * <p>给列画像用：它只回填描述与样例，别名、聚合方式、派生定义都不能碰。
     * 走 {@link #updateTable} 会把整份表单传一遍，而调用方并没有那些字段——
     * 一旦某处漏传，就会把管理员的配置冲掉，且不报错。
     */
    /**
     * 建模覆盖度检查：这个域配得够不够用。
     *
     * <p>与启动校验同一个实现（{@link ModelingCoverageCheck}）——
     * 两处各写一套的话，管理台说"没问题"而启动日志说"缺 AVG"，那种不一致比不检查更糟。
     */
    public List<com.liusongboyy.chatdata.engine.startup.EngineConfigViolation>
            modelingCheck(long domainId) {
        DomainRow row = get(domainId);
        String dbType = row.datasourceId() == null ? null
                : readMapper.selectEnabledDatasources().stream()
                        .filter(d -> d.id() == row.datasourceId())
                        .map(com.liusongboyy.chatdata.engine.datasource.DatasourceDef::dbType)
                        .findFirst()
                        .orElse(null);
        Domain domain = DomainLoader.load(row, listTables(domainId),
                List.of(), List.of(), dbType);
        return com.liusongboyy.chatdata.engine.startup.ModelingCoverageCheck.check(
                domain, new SemanticModel(domain));
    }

    public void updateTableColumns(long tableId, String columnsJson, String operator) {
        // 先确认这张表存在，否则 UPDATE 命中 0 行会被当成成功
        boolean exists = readMapper.selectTablesByDomain(findDomainIdOf(tableId)).stream()
                .anyMatch(t -> t.id() == tableId);
        if (!exists) {
            throw new AdminException("注册表不存在：id=" + tableId);
        }
        // **用专用语句**：通用的 updateDomainTable 会把排序号等字段一并覆盖成传入值，
        // 而这里手里只有 columns_json
        writeMapper.updateColumnsJson(tableId, columnsJson, operator);
        log.info("注册表列定义已更新（画像回填）：id={} by={}", tableId, operator);
    }

    public void deleteTable(long tableId, String operator) {
        long domainId = findDomainIdOf(tableId);
        boolean exists = readMapper.selectTablesByDomain(domainId).stream()
                .anyMatch(t -> t.id() == tableId);
        if (!exists) {
            throw new AdminException("注册表不存在：id=" + tableId);
        }

        writeMapper.deleteDomainTable(tableId);
        log.info("注册表已删除：id={} by={}", tableId, operator);
    }

    /**
     * 反查注册表属于哪个域。
     *
     * <p>没有 `selectDomainTableById` 这样的查询——为它单开一条 SQL 不值得，
     * 而域的数量是个位数。等域多起来再加。
     */
    private long findDomainIdOf(long tableId) {
        return list().stream()
                .filter(d -> readMapper.selectTablesByDomain(d.id()).stream()
                        .anyMatch(t -> t.id() == tableId))
                .map(DomainRow::id)
                .findFirst()
                .orElseThrow(() -> new AdminException("注册表不存在：id=" + tableId));
    }

    private void requireNameAvailable(String name, Long excludeId) {
        boolean taken = list().stream()
                .anyMatch(d -> d.name().equals(name) && (excludeId == null || d.id() != excludeId));
        if (taken) {
            throw new AdminException("已存在同名数据域：" + name);
        }
    }

    private void requireTableNameAvailable(long domainId, String name, Long excludeId) {
        boolean taken = readMapper.selectTablesByDomain(domainId).stream()
                .anyMatch(t -> t.name().equals(name) && (excludeId == null || t.id() != excludeId));
        if (taken) {
            throw new AdminException("本域内已存在同名表：" + name);
        }
    }

    /**
     * 拿真实库结构核对注册表声明。
     *
     * <h2>为什么这条校验重要</h2>
     *
     * <p>实测踩到过：注册表里写了 {@code ins_customer.org_code}，而那张表根本没有这一列。
     * 注册、保存、启动校验**全都通过**，直到用户提问、SQL 执行时才炸，
     * 报错是 {@code Unknown column 't2.org_code'}——用户看不懂，管理员得翻日志。
     *
     * <p>有了这条校验，那个错误在保存时就变成"这几列在库里不存在"。
     *
     * <h2>读不到元数据时不拦</h2>
     *
     * <p>数据源连不上（网络、密码、库停了）时**不拒绝保存**——
     * 那会把"暂时连不上"变成"配不了"。但这时也**不能断言列不存在**，
     * 故只在成功读到表结构、且确实找不到某列时才报错。
     *
     * <p>这条取舍很重要：校验的价值在于挡住真错误，而不是在环境不通时添堵。
     */
    private void validateAgainstRealSchema(long domainId, DomainTableInput input) {
        // 只有物理表能核对；SQL 虚拟表的列来自子查询，库里没有对应的表
        if (!"TABLE".equals(input.mode())) {
            return;
        }

        DomainRow domain = get(domainId);
        if (domain.datasourceId() == null) {
            // 绑定的是"应用默认库"，引擎拿不到它的连接，跳过
            return;
        }

        String dsId = String.valueOf(domain.datasourceId());
        boolean tableExists;
        try {
            tableExists = metaReader.tableExists(dsId, input.name());
        } catch (Exception e) {
            log.warn("核对注册表时读不到数据源元数据，跳过校验：domain={} table={} 原因={}",
                    domainId, input.name(), e.getMessage());
            return;
        }

        if (!tableExists) {
            throw new AdminException(
                    "数据源里没有名为「%s」的表。请检查表名（注意大小写与前后空格），"
                            .formatted(input.name())
                            + "或确认该表属于数据源绑定的库。");
        }

        List<String> declared = input.columns().stream()
                .map(DomainTableInput.ColumnInput::col)
                .filter(c -> c != null && !c.isBlank())
                .toList();

        List<String> missing = metaReader.findMissingColumns(dsId, input.name(), declared);
        if (!missing.isEmpty()) {
            throw new AdminException(
                    "这几列在「%s」里不存在：%s。请核对列名——渲染时会原样引用它们，"
                            .formatted(input.name(), String.join("、", missing))
                            + "差一个字符就会在执行时报 Unknown column。");
        }
    }

    /**
     * 保存表关联。
     *
     * <h2>校验引用的注册表必须存在</h2>
     *
     * <p>关联里存的是注册表 id。指向一个不存在的 id 时，多表问题会失败而单表问题照常——
     * **部分可用比全不可用更难发现**。故在这里拦住。
     *
     * <h2>不校验连接列是否存在</h2>
     *
     * <p>连接列是物理列名，理论上可以拿 {@code MetaReader} 核对。
     * 但那要求两张表都已能从数据源读到，而关联可能是在表还没配完时先建的。
     * 留到问数时由渲染器报——那里的报错已经能指明是哪张表的哪个列。
     */
    public void saveJoins(long domainId, String joinsJson, String operator) {
        DomainRow domain = get(domainId);

        List<TableJoin> joins = parseJoins(domain, joinsJson);

        java.util.Set<String> tableIds = new java.util.HashSet<>();
        readMapper.selectTablesByDomain(domainId).forEach(t -> tableIds.add(String.valueOf(t.id())));

        for (TableJoin j : joins) {
            if (!tableIds.contains(j.leftTableId()) || !tableIds.contains(j.rightTableId())) {
                throw new AdminException(
                        "表关联 %d 引用了本域内不存在的注册表：left=%s right=%s。现有注册表 id：%s"
                                .formatted(j.id(), j.leftTableId(), j.rightTableId(), tableIds));
            }
        }

        writeMapper.updateDomainJoins(domainId, joins.isEmpty() ? null : joinsJson, operator);
        log.info("表关联已更新：domain={} 关联数={} by={}", domainId, joins.size(), operator);
    }

    /** 解析并归一化关联。坏 JSON 直接报错——静默丢掉关联等于悄悄拆掉多表能力。 */
    private List<TableJoin> parseJoins(DomainRow domain, String joinsJson) {
        if (joinsJson == null || joinsJson.isBlank()) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode arr = JSON.readTree(joinsJson);
            if (!arr.isArray()) {
                throw new AdminException("表关联必须是 JSON 数组");
            }
            List<TableJoin> out = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                out.add(TableJoin.from(n));
            }
            return out;
        } catch (AdminException e) {
            throw e;
        } catch (Exception e) {
            throw new AdminException("表关联格式不正确：" + e.getMessage());
        }
    }

    // ── 业务术语 ──

    public List<TermRow> listTerms(long domainId) {
        get(domainId);
        return readMapper.selectTermsByDomain(domainId);
    }

    public long createTerm(long domainId, TermInput input, String operator) {
        get(domainId);
        TermInput v = input.validated();
        requireTermNameAvailable(domainId, v.name(), null);

        writeMapper.insertTerm(domainId, v.name(), v.synonymsJson(), v.description(),
                v.applyKind(), null, null, 0, operator);

        return listTerms(domainId).stream()
                .filter(t -> t.name().equals(v.name()))
                .map(TermRow::id)
                .findFirst()
                .orElseThrow(() -> new AdminException("新建术语后未能读回，请刷新列表确认"));
    }

    public void updateTerm(long termId, TermInput input, String operator) {
        TermRow existing = findTerm(termId);
        TermInput v = input.validated();
        requireTermNameAvailable(existing.domainId(), v.name(), termId);

        writeMapper.updateTerm(termId, v.name(), v.synonymsJson(), v.description(),
                v.applyKind(), null, null, 0, operator);
        log.info("术语已更新：id={} name={} by={}", termId, v.name(), operator);
    }

    public void deleteTerm(long termId, String operator) {
        TermRow t = findTerm(termId);
        writeMapper.deleteTerm(termId);
        log.info("术语已删除：id={} name={} by={}", termId, t.name(), operator);
    }

    private TermRow findTerm(long termId) {
        return list().stream()
                .flatMap(d -> readMapper.selectTermsByDomain(d.id()).stream())
                .filter(t -> t.id() == termId)
                .findFirst()
                .orElseThrow(() -> new AdminException("术语不存在：id=" + termId));
    }

    private void requireTermNameAvailable(long domainId, String name, Long excludeId) {
        boolean taken = readMapper.selectTermsByDomain(domainId).stream()
                .anyMatch(t -> t.name().equals(name) && (excludeId == null || t.id() != excludeId));
        if (taken) {
            throw new AdminException("本域内已存在同名术语：" + name);
        }
    }

    // ── 查询主题 ──

    public List<TopicRow> listTopics(long domainId) {
        get(domainId);
        return readMapper.selectTopicsByDomain(domainId);
    }

    public long createTopic(long domainId, TopicInput input, String operator) {
        get(domainId);
        TopicInput v = input.validated();
        requireTopicNameAvailable(domainId, v.name(), null);

        writeMapper.insertTopic(domainId, v.name(), v.description(), v.status(),
                v.fieldsJson(), 0, operator);

        return listTopics(domainId).stream()
                .filter(t -> t.name().equals(v.name()))
                .map(TopicRow::id)
                .findFirst()
                .orElseThrow(() -> new AdminException("新建主题后未能读回，请刷新列表确认"));
    }

    public void updateTopic(long topicId, TopicInput input, String operator) {
        TopicRow existing = findTopic(topicId);
        TopicInput v = input.validated();
        requireTopicNameAvailable(existing.domainId(), v.name(), topicId);

        writeMapper.updateTopic(topicId, v.name(), v.description(), v.status(),
                v.fieldsJson(), 0, operator);
        log.info("主题已更新：id={} name={} by={}", topicId, v.name(), operator);
    }

    public void deleteTopic(long topicId, String operator) {
        TopicRow t = findTopic(topicId);
        writeMapper.deleteTopic(topicId);
        log.info("主题已删除：id={} name={} by={}", topicId, t.name(), operator);
    }

    private TopicRow findTopic(long topicId) {
        return list().stream()
                .flatMap(d -> readMapper.selectTopicsByDomain(d.id()).stream())
                .filter(t -> t.id() == topicId)
                .findFirst()
                .orElseThrow(() -> new AdminException("主题不存在：id=" + topicId));
    }

    private void requireTopicNameAvailable(long domainId, String name, Long excludeId) {
        boolean taken = readMapper.selectTopicsByDomain(domainId).stream()
                .anyMatch(t -> t.name().equals(name) && (excludeId == null || t.id() != excludeId));
        if (taken) {
            throw new AdminException("本域内已存在同名主题：" + name);
        }
    }
}

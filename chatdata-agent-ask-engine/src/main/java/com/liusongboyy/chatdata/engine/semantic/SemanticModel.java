package com.liusongboyy.chatdata.engine.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一个语义域的解析模型：业务名 → 具体某张表上的某一列。
 *
 * <p>由 {@link Domain} 构造，构造时建立索引，之后只读、可并发使用。
 *
 * <h2>两个关键决策</h2>
 *
 * <h3>① 字段歧义时拒绝，不猜</h3>
 *
 * <p>若同一个业务名在域内多张表上都能匹配（例如两张表都有「区域」），
 * **抛 {@link SemanticException.Reason#FIELD_AMBIGUOUS} 而不是任选一张**。
 * 猜错的后果是查询打到错误的表上，返回一个**看起来合理但错误**的数字——
 * 而用户没有办法察觉。这类错误必须让管理员在配置层解决。
 *
 * <h3>② 表别名由本模型统一分配</h3>
 *
 * <p>{@link FieldRef#tableAlias()} 里的别名由这里生成，渲染器直接用。
 * 这样做的原因是权限：行规则配的是 {@code field: region}，谓词必须写成
 * {@code "t1"."region" = ?} 才能确定作用在哪张表上。
 * 如果别名由渲染器各自决定，权限模块就拿不到它。
 *
 * <p>别名按注册表 id 排序后分配（{@code t1, t2, ...}），**保证同一份配置每次生成的
 * SQL 完全一致**——否则同一问题两次问出不同 SQL，审计和回归测试都做不了。
 */
public final class SemanticModel {

    private final Domain domain;
    private final List<RegisteredTable> visibleTables;
    /** 注册表 id → 别名。 */
    private final Map<String, String> aliasByTableId;
    /** 注册表 id → 表。 */
    private final Map<String, RegisteredTable> tableById;
    /** 规范化名字 → 候选 (表, 列)。 */
    private final Map<String, List<Candidate>> index;

    private record Candidate(RegisteredTable table, ColumnDef column) {
    }

    public SemanticModel(Domain domain) {
        if (domain == null) {
            throw new IllegalArgumentException("domain 不能为空");
        }
        this.domain = domain;

        // 按 id 排序后再分配别名，保证顺序稳定 → 同一配置每次生成同样的 SQL
        this.visibleTables = domain.visibleTables().stream()
                .sorted(Comparator.comparing(RegisteredTable::id))
                .toList();

        Map<String, String> aliases = new LinkedHashMap<>();
        Map<String, RegisteredTable> byId = new LinkedHashMap<>();
        Map<String, List<Candidate>> idx = new LinkedHashMap<>();

        int n = 0;
        for (RegisteredTable t : visibleTables) {
            n++;
            aliases.put(t.id(), "t" + n);
            byId.put(t.id(), t);

            for (ColumnDef c : t.columns()) {
                if (!c.enabled()) {
                    // 对 LLM 不可见的列不参与解析——它不该被问出来
                    continue;
                }
                for (String key : keysOf(c)) {
                    idx.computeIfAbsent(key, k -> new ArrayList<>()).add(new Candidate(t, c));
                }
            }
        }

        this.aliasByTableId = Map.copyOf(aliases);
        this.tableById = Map.copyOf(byId);
        this.index = Map.copyOf(idx);
    }

    /**
     * 业务名与物理列名都进索引，比对时统一小写。
     *
     * <p>用 {@code LinkedHashSet} 去重：配置里 {@code alias} 与 {@code col} 常常相同
     * （如都叫 {@code premium}），不去重会把同一个候选加进索引两次，
     * 于是一个完全正常的字段被误判成"歧义"。
     */
    private static List<String> keysOf(ColumnDef c) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(2);
        if (c.alias() != null && !c.alias().isBlank()) {
            keys.add(norm(c.alias()));
        }
        keys.add(norm(c.col()));
        return List.copyOf(keys);
    }

    private static String norm(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }

    public Domain domain() {
        return domain;
    }

    /** 对 LLM 可见的表，已按 id 排序。用于 prompt 装配与渲染。 */
    public List<RegisteredTable> visibleTables() {
        return visibleTables;
    }

    /** 取某张注册表在本次查询中的别名。 */
    public String aliasOf(String tableId) {
        String a = aliasByTableId.get(tableId);
        if (a == null) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "注册表 " + tableId + " 不在本域的可见表内");
        }
        return a;
    }

    /**
     * 解析一个业务名。
     *
     * @param name 业务名或物理列名
     * @param kind 期望的角色
     * @throws SemanticException 找不到、有歧义、或角色不符
     */
    public FieldRef resolve(String name, FieldKind kind) {
        if (name == null || name.isBlank()) {
            throw new SemanticException(SemanticException.Reason.FIELD_NOT_FOUND, "字段名为空");
        }

        List<Candidate> candidates = index.getOrDefault(norm(name), List.of());

        if (candidates.isEmpty()) {
            throw new SemanticException(SemanticException.Reason.FIELD_NOT_FOUND,
                    "本数据域内找不到字段「" + name + "」。可用字段见域内已启用的注册表");
        }

        // 歧义：同名匹配到多个列。**不猜，直接拒绝**。
        // 判定用 size 而非"不同表数"——同一张表内两列撞名同样是歧义，
        // 按"不同表数"判会让它静默取第一个，那也是猜。
        if (candidates.size() > 1) {
            List<String> names = candidates.stream()
                    .map(c -> c.table().displayName() + "." + c.column().displayName())
                    .distinct()
                    .toList();
            throw new SemanticException(SemanticException.Reason.FIELD_AMBIGUOUS,
                    "字段「" + name + "」在本域内匹配到多处：" + names
                            + "。请为它们设置不同的业务名，或关闭不用的列");
        }

        Candidate c = candidates.get(0);
        ColumnDef col = c.column();

        // 角色校验。指标必须配了聚合方式，维度必须没配——两者渲染位置不同，混了会出错结果
        if (kind == FieldKind.METRIC && !col.isMetric()) {
            throw new SemanticException(SemanticException.Reason.FIELD_KIND_MISMATCH,
                    "「" + name + "」没有配置聚合方式，不能作为指标。"
                            + "请在语义建模中为它设置 SUM / COUNT 等聚合，或把它当维度用");
        }
        if (kind == FieldKind.DIMENSION && col.isMetric()) {
            throw new SemanticException(SemanticException.Reason.FIELD_KIND_MISMATCH,
                    "「" + name + "」配置了聚合方式（" + col.agg() + "），不能作为维度。"
                            + "若确需按它分组，请在语义建模中另加一条不聚合的列定义");
        }

        return new FieldRef(aliasByTableId.get(c.table().id()), col.col(), col.agg(), kind);
    }

    /** 解析指标。 */
    public FieldRef resolveMetric(String name) {
        return resolve(name, FieldKind.METRIC);
    }

    /** 解析维度。 */
    public FieldRef resolveDimension(String name) {
        return resolve(name, FieldKind.DIMENSION);
    }

    /**
     * 解析一个字段，**不校验角色**。
     *
     * <p>用于行级权限的字段定位：规则配的是 {@code field: region}，
     * 只关心"这个列在哪张表上"，不关心它是指标还是维度。
     *
     * <p>仍然做歧义检查——同一名字在多张表上都能匹配时抛异常。
     * 权限谓词注到错误的表上，比查询失败危险得多。
     */
    public FieldRef resolveAny(String name) {
        if (name == null || name.isBlank()) {
            throw new SemanticException(SemanticException.Reason.FIELD_NOT_FOUND, "字段名为空");
        }
        List<Candidate> candidates = index.getOrDefault(norm(name), List.of());
        if (candidates.isEmpty()) {
            throw new SemanticException(SemanticException.Reason.FIELD_NOT_FOUND,
                    "本数据域内找不到字段「" + name + "」。"
                            + "行级/列级规则里引用的字段必须是域内已注册且启用的列");
        }
        if (candidates.size() > 1) {
            List<String> names = candidates.stream()
                    .map(c -> c.table().displayName() + "." + c.column().displayName())
                    .distinct()
                    .toList();
            throw new SemanticException(SemanticException.Reason.FIELD_AMBIGUOUS,
                    "字段「" + name + "」在本域内匹配到多处：" + names
                            + "。权限规则无法确定作用在哪张表上，请为其设置唯一的业务名");
        }
        Candidate c = candidates.get(0);
        return new FieldRef(aliasByTableId.get(c.table().id()), c.column().col(),
                c.column().agg(), c.column().isMetric() ? FieldKind.METRIC : FieldKind.DIMENSION);
    }

    /**
     * 由表别名反查注册表 id。
     *
     * <p>渲染器要拿表 id 去规划 join——别名是渲染期才分配的，join 配置里存的是 id。
     */
    public String tableIdOfAlias(String alias) {
        for (Map.Entry<String, String> e : aliasByTableId.entrySet()) {
            if (e.getValue().equals(alias)) {
                return e.getKey();
            }
        }
        throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                "找不到别名 " + alias + " 对应的注册表");
    }

    /** 注册表 id → 别名。 */
    public String aliasOfTable(String tableId) {
        return aliasOf(tableId);
    }

    /**
     * 该列是不是日期/时间类型。
     *
     * <h2>为什么放在这里而不是各调用方自己判</h2>
     *
     * <p>渲染器要用它决定"能不能按粒度截断"，图表推荐要用它决定"用折线还是柱状"。
     * 两处各判一次的话，一旦口径不同就会出现「SQL 按天分组、图表却按日期画折线」
     * 这类不一致——而两边单看都是对的，很难查。
     *
     * <p>**不抛异常**：判不出来就当不是日期。这个方向的默认是安全的——
     * 不截断的 SQL 仍然正确（只是粒度粗），而把非日期列拿去截断会直接报错。
     */
    public boolean isTemporal(FieldRef ref) {
        if (ref == null) {
            return false;
        }
        for (RegisteredTable t : visibleTables) {
            if (!ref.tableAlias().equals(aliasByTableId.get(t.id()))) {
                continue;
            }
            for (ColumnDef c : t.columns()) {
                if (c.col().equalsIgnoreCase(ref.column())) {
                    return isTemporalType(c.type());
                }
            }
        }
        return false;
    }

    /**
     * 数据库类型名是不是日期/时间。
     *
     * <p>各家的写法不一：{@code date} / {@code datetime} / {@code timestamp} /
     * {@code timestamptz} / {@code smalldatetime} / {@code datetime2} /
     * {@code datetimeoffset} / {@code DATE} / {@code TIMESTAMP}…
     * 逐个枚举既列不全也会随版本变，故按**子串**判。
     *
     * <p>{@code year} 要单列：MySQL 的 {@code YEAR} 既不含 date 也不含 time。
     */
    public static boolean isTemporalType(String dbType) {
        if (dbType == null || dbType.isBlank()) {
            return false;
        }
        String t = dbType.trim().toLowerCase(Locale.ROOT);
        return t.contains("date") || t.contains("time") || t.equals("year");
    }

    /** 域内所有可用于问数的字段名，供 prompt 装配与错误提示。 */
    public List<String> availableFieldNames() {
        return visibleTables.stream()
                .flatMap(t -> t.columns().stream())
                .filter(ColumnDef::enabled)
                .map(ColumnDef::displayName)
                .distinct()
                .sorted()
                .toList();
    }
}

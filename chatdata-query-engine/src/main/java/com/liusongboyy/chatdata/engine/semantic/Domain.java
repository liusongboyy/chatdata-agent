package com.liusongboyy.chatdata.engine.semantic;

import com.liusongboyy.chatdata.engine.permission.DomainPermission;

import java.util.List;

/**
 * 语义域——围绕一个数据源构建的语义模型单元。
 *
 * <p>定义该范围内可用的表、表间关联、业务术语，以及权限规则（见 {@code GLOSSARY.md}）。
 * 一次提问先路由到域，再在其中取数。
 *
 * @param id           域标识
 * @param name         域名称
 * @param datasourceId 绑定的数据源 id，可为空（空表示用应用默认库）
 * @param dbType       数据源类型（如 {@code MYSQL5.7}），用于取 {@code SqlDialect}。
 *                     由加载器从绑定的数据源解析后填入——渲染器需要它，单独传一路参数只是噪音
 * @param enabled      是否启用
 * @param tables       域内注册表
 * @param joins        表间关联。**方向已归一化**：RIGHT JOIN 在解析时转成了交换操作数的 LEFT JOIN，
 *                     见 {@link TableJoin}
 * @param permission   权限配置
 * @param terms        业务术语。解决"同一个东西的不同说法"——实测中模型会跟着
 *                     用户的措辞走（问「保费合计」产出 metrics:["保费合计"]），
 *                     术语让它在**一开始**就知道那些说法指同一个字段
 * @param topics       查询主题。把一次问数的字段范围收敛到一个子集，
 *                     表多时能显著缩短 prompt 并降低选错表的概率
 */
public record Domain(
        String id,
        String name,
        String datasourceId,
        String dbType,
        boolean enabled,
        List<RegisteredTable> tables,
        List<TableJoin> joins,
        DomainPermission permission,
        List<Term> terms,
        List<Topic> topics,
        Mode mode
) {

    /**
     * 语义域的模式。**它决定这个域有没有行级/列级权限**，所以不是一个风格开关。
     *
     * <p>{@link #MODELED} 走"语义建模 → DSL → 我们渲染 SQL"，行规则谓词拼进 WHERE、
     * 脱敏列不进 SELECT。{@link #RAW} 让模型自己写 SQL，形状任意（CTE/UNION/子查询），
     * 没有安全的注入点——{@code docs/adr/0002} 已否决"解析后注入谓词"，故只做表级授权。
     */
    public enum Mode {
        /** 建模模式。默认值，既有域全是这个。 */
        MODELED,
        /** 直连模式：只勾表，模型自己写 SQL。**不做行/列权限。** */
        RAW;

        public static Mode parse(String raw) {
            // 空/未知一律回落到 MODELED：**默认必须是更严格的那个**，
            // 配置写坏时不能悄悄降级成一个没有行列表权限的模式
            if (raw == null || raw.isBlank()) {
                return MODELED;
            }
            return "raw".equalsIgnoreCase(raw.trim()) ? RAW : MODELED;
        }
    }

    /**
     * 兼容构造器：不指定模式即建模模式。
     *
     * <p>既有的调用点与测试（十余处）不必改。加模式这件事对它们没有语义影响——
     * 它们全都在测建模链路。
     */
    public Domain(String id, String name, String datasourceId, String dbType, boolean enabled,
                  List<RegisteredTable> tables, List<TableJoin> joins, DomainPermission permission,
                  List<Term> terms, List<Topic> topics) {
        this(id, name, datasourceId, dbType, enabled, tables, joins, permission, terms, topics,
                Mode.MODELED);
    }

    /** 是否直连模式。直连模式**没有行级/列级权限**，调用方必须区别对待。 */
    public boolean isRaw() {
        return mode == Mode.RAW;
    }

    public Domain {
        if (id == null || id.isBlank()) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "语义域缺少 id");
        }
        tables = tables == null ? List.of() : List.copyOf(tables);
        joins = joins == null ? List.of() : List.copyOf(joins);
        permission = permission == null ? DomainPermission.from(null) : permission;
        mode = mode == null ? Mode.MODELED : mode;
        terms = terms == null ? List.of() : List.copyOf(terms);
        topics = topics == null ? List.of() : List.copyOf(topics);
    }

    /** 已发布的主题。草稿不进 prompt。 */
    public List<Topic> publishedTopics() {
        return topics.stream().filter(Topic::published).toList();
    }

    /** 对 LLM 可见的表。渲染与 prompt 装配都只看这一批。 */
    public List<RegisteredTable> visibleTables() {
        return tables.stream().filter(RegisteredTable::enabled).toList();
    }

    /** 按 id 找注册表。 */
    public java.util.Optional<RegisteredTable> tableById(String tableId) {
        return tables.stream().filter(t -> t.id().equals(tableId)).findFirst();
    }

    public String displayName() {
        return (name == null || name.isBlank()) ? id : name;
    }
}

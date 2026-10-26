package com.liusongboyy.chatdata.engine.startup;

import com.liusongboyy.chatdata.engine.datasource.DatasourceDef;
import com.liusongboyy.chatdata.engine.datasource.dialect.SqlDialects;
import com.liusongboyy.chatdata.engine.permission.AssistantPermission;
import com.liusongboyy.chatdata.engine.semantic.Assistant;
import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.SemanticException;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 引擎配置的校验规则。**纯逻辑、无 Spring 依赖**，便于直接测。
 *
 * <h2>为什么要有这一层</h2>
 *
 * <p>ADR-0002 的 R1 说得很直接：<b>一个漏配的域就是一次全量数据暴露</b>，
 * 而且失败形态是静默的。同样地，一个坏掉的域配置不会在启动时报错，
 * 只会在用户提问时失败——那时错误信息离现场很远。
 *
 * <p>故把"配置是否可用"和"配置是否安全"这两件事都提前到启动时。
 *
 * <h2>校验项</h2>
 *
 * <table>
 *   <tr><th>类别</th><th>为什么是错误而不是警告</th></tr>
 *   <tr><td>未设置访问授权</td><td>失败形态是静默的：不报错，数据都能看到</td></tr>
 *   <tr><td>数据源不可用</td><td>该域的所有查询都会失败，且错因会被埋在执行失败里</td></tr>
 *   <tr><td>没有可用的表</td><td>域问不出任何东西，却仍占着路由位置</td></tr>
 *   <tr><td>表关联引用无效</td><td>多表问题会失败，单表问题却正常——**部分可用比全不可用更难发现**</td></tr>
 *   <tr><td>没有可用字段</td><td>同上，且往往意味着列定义被整批关掉了</td></tr>
 *   <tr><td>派生指标引用无效</td><td>只坏一个指标，同域其他指标都正常——最难发现</td></tr>
 * </table>
 */
public final class EngineConfigValidator {

    private EngineConfigValidator() {
    }

    /**
     * 校验数据源自身是否可用。
     *
     * <p>这些检查刻意**不放在 {@code DatasourceDef} 的构造器里**：
     * 构造器抛异常会让 MyBatis 的整条列表查询失败，一行坏数据就炸掉整份启动报告。
     * 配置校验的目标是"一次把所有问题列全"。
     *
     * @return 违规列表；空表示该数据源可用
     */
    public static List<EngineConfigViolation> validateDatasource(DatasourceDef ds) {
        List<EngineConfigViolation> violations = new ArrayList<>();
        String id = String.valueOf(ds.id());

        if (ds.name() == null || ds.name().isBlank()) {
            violations.add(new EngineConfigViolation(
                    EngineConfigViolation.Category.DATASOURCE_UNUSABLE, id, "(未命名)", "数据源缺少名称"));
        }
        if (ds.url() == null || ds.url().isBlank()) {
            violations.add(new EngineConfigViolation(
                    EngineConfigViolation.Category.DATASOURCE_UNUSABLE, id, ds.name(), "数据源缺少 JDBC URL"));
        }
        if (!SqlDialects.supports(ds.dbType())) {
            violations.add(new EngineConfigViolation(
                    EngineConfigViolation.Category.DATASOURCE_UNUSABLE, id, ds.name(),
                    "数据源类型「" + ds.dbType() + "」没有对应的 SQL 方言，无法渲染查询。"
                            + "已支持：" + SqlDialects.supportedTypes()));
        }
        return violations;
    }

    /**
     * 校验一个已加载的域。
     *
     * @param domain      已加载的域
     * @param datasources 全部启用中的数据源，键为 id
     */
    public static List<EngineConfigViolation> validate(Domain domain,
                                                       Map<Long, DatasourceDef> datasources) {
        // 两种模式的不变量不同，分开校验。混在一段里迟早会出现
        // "对 RAW 域报『没有可用字段』"这类无意义告警
        if (domain.isRaw()) {
            return validateRaw(domain, datasources);
        }

        List<EngineConfigViolation> violations = new ArrayList<>();
        String id = domain.id();
        String name = domain.displayName();

        // ① 授权（R1）—— 最要紧的一条
        if (!domain.permission().hasAnyGrant()) {
            violations.add(new EngineConfigViolation(
                    EngineConfigViolation.Category.NO_GRANT, id, name,
                    "grantRoles / grantUsers 皆空且 isPublic=false，"
                            + "该域对所有已通过认证的用户开放。"
                            + "请补配授权，或把 unrestrictedRoles 写清楚"));
        }

        // ② 数据源
        if (domain.datasourceId() != null) {
            DatasourceDef ds = findDatasource(datasources, domain.datasourceId());
            if (ds == null) {
                violations.add(new EngineConfigViolation(
                        EngineConfigViolation.Category.DATASOURCE_UNUSABLE, id, name,
                        "绑定的数据源 id=" + domain.datasourceId() + " 不存在或未启用"));
            } else if (!SqlDialects.supports(ds.dbType())) {
                violations.add(new EngineConfigViolation(
                        EngineConfigViolation.Category.DATASOURCE_UNUSABLE, id, name,
                        "数据源「" + ds.name() + "」的类型 " + ds.dbType()
                                + " 没有对应的 SQL 方言。已支持：" + SqlDialects.supportedTypes()));
            }
        }

        // ③ 可见的表
        List<RegisteredTable> visible = domain.visibleTables();
        if (visible.isEmpty()) {
            violations.add(new EngineConfigViolation(
                    EngineConfigViolation.Category.NO_VISIBLE_TABLE, id, name,
                    "域内没有对 LLM 可见的表（enabled=1）。这个域问不出任何东西"));
            // 没有表就无需再查字段与关联了
            return violations;
        }

        // ④ 可用字段
        boolean hasAnyField = visible.stream()
                .anyMatch(t -> t.columns().stream().anyMatch(c -> c.enabled()));
        if (!hasAnyField) {
            violations.add(new EngineConfigViolation(
                    EngineConfigViolation.Category.NO_FIELD, id, name,
                    "域内所有表的列都被关掉了（enabled=false），没有任何可用于问数的字段"));
        }

        // ⑤ 表关联引用
        Set<String> tableIds = new HashSet<>();
        domain.tables().forEach(t -> tableIds.add(t.id()));

        domain.joins().stream()
                .filter(j -> !tableIds.contains(j.leftTableId()) || !tableIds.contains(j.rightTableId()))
                .forEach(j -> violations.add(new EngineConfigViolation(
                        EngineConfigViolation.Category.BROKEN_JOIN_REF, id, name,
                        "表关联 %d 引用了域内不存在的注册表：left=%s right=%s。现有注册表 id：%s"
                                .formatted(j.id(), j.leftTableId(), j.rightTableId(), tableIds))));

        // ⑥ 派生指标的输入引用
        violations.addAll(validateDerived(domain));

        return violations;
    }

    /**
     * 校验派生指标的输入能否解析。
     *
     * <p>派生指标存的是**别名**（{@code left: "赔付金额"}），不是列名。
     * 别名改过一次、或同名匹配到多张表，这个指标就永远问不出来——
     * 而 DSL 里的写法与普通指标一样，模型和用户都看不出差别。
     *
     * <p>用 {@link SemanticModel#resolveMetricRef} 而不是自己比对字符串：
     * 启动校验的判据必须与运行时完全一致，否则会出现"启动说没问题、问的时候报错"。
     */
    private static List<EngineConfigViolation> validateDerived(Domain domain) {
        List<EngineConfigViolation> out = new ArrayList<>();
        SemanticModel model = new SemanticModel(domain);

        for (RegisteredTable t : domain.visibleTables()) {
            for (ColumnDef c : t.columns()) {
                // 派生指标与带条件的指标都要解析一遍：两者都引用别的字段，
                // 引错时只有在用户提问那一刻才暴露
                boolean derived = c.derived() != null;
                if (!c.enabled() || (!derived && c.condition() == null)) {
                    continue;
                }
                try {
                    model.resolveMetricRef(c.alias());
                } catch (SemanticException e) {
                    out.add(new EngineConfigViolation(derived
                            ? EngineConfigViolation.Category.BROKEN_DERIVED_METRIC
                            : EngineConfigViolation.Category.BROKEN_METRIC_CONDITION,
                            domain.id(), domain.displayName(),
                            "表「" + t.displayName() + "」的指标「" + c.displayName() + "」无法解析："
                                    + e.getMessage()));
                }
            }
        }
        return out;
    }

    /**
     * 直连模式（RAW）的域。
     *
     * <p>只校验**它真正会用到的东西**：授权、数据源、白名单里的表。
     * 刻意不查列定义、聚合方式与表关联——那些在直连模式下根本不参与问数
     * （模型看的是库里的真实表结构，不是这里登记的列别名）。对它们报警只会制造噪音。
     *
     * <p>唯一必须拦下的是**行/列规则**：该模式没有注入点，配了也不生效。
     */
    private static List<EngineConfigViolation> validateRaw(Domain domain,
                                                           Map<Long, DatasourceDef> datasources) {
        List<EngineConfigViolation> out = new ArrayList<>();
        String id = domain.id();
        String name = domain.displayName();

        // ① 授权：直连模式下它就是**表级授权**的闸门，一样不能空
        if (!domain.permission().hasAnyGrant()) {
            out.add(new EngineConfigViolation(
                    EngineConfigViolation.Category.NO_GRANT, id, name,
                    "直连模式域也必须配置访问授权（grantRoles / grantUsers 或 isPublic），"
                            + "否则它对所有已认证用户开放"));
        }

        // ② 数据源：直连模式下我们是**直接连库**的，缺了什么都做不了
        if (domain.datasourceId() == null || domain.datasourceId().isBlank()) {
            out.add(new EngineConfigViolation(
                    EngineConfigViolation.Category.DATASOURCE_UNUSABLE, id, name,
                    "直连模式必须绑定数据源——它就是「要连哪个库」"));
        } else if (findDatasource(datasources, domain.datasourceId()) == null) {
            out.add(new EngineConfigViolation(
                    EngineConfigViolation.Category.DATASOURCE_UNUSABLE, id, name,
                    "绑定的数据源 id=" + domain.datasourceId() + " 不存在或未启用"));
        }

        // ③ 行/列规则：配了也不生效，必须报
        if (!domain.permission().rowRules().isEmpty() || !domain.permission().colRules().isEmpty()) {
            out.add(new EngineConfigViolation(
                    EngineConfigViolation.Category.RAW_DOMAIN_WITH_RULES, id, name,
                    "直连模式由模型自己写 SQL，行级/列级规则没有安全的注入点，**配了不会生效**。"
                            + "请清空 rowRules / colRules，或改用建模模式（MODELED）"));
        }

        // ④ 白名单至少一张表，否则问不出任何东西
        if (domain.visibleTables().isEmpty()) {
            out.add(new EngineConfigViolation(
                    EngineConfigViolation.Category.NO_VISIBLE_TABLE, id, name,
                    "直连模式至少要勾选一张表（那就是允许模型访问的白名单）"));
        }

        return out;
    }

    /**
     * 助理能不能**真正被用起来**。
     *
     * <h2>它挡的是什么</h2>
     *
     * <p>助理层与域层是**两道独立的闸门**（ADR-0002 / PRD §7）：助理层管"能不能看到并点进这个助理"，
     * 域层管"能不能看这个域的数据"。**授予助理不等于授予域**——两层的授权各写各的。
     *
     * <p>于是有一个很容易犯的配置错误：某角色被放进助理的授权名单，却没被放进
     * 绑定域的授权名单。结果是那个人**看得见助理、点进去必然被拒**，
     * 而且拒绝信息是"您没有该数据域的访问权限"——他根本不知道问题出在配置上。
     * 这个错已经真实发生过一次（见下）。
     *
     * <h2>只报角色，不报用户</h2>
     *
     * <p>用户的判定需要"这个用户有哪些角色"，而引擎刻意不认识宿主的用户模型
     * （见 {@link RoleDirectory}）。拿不到就猜，会刷出一屏假告警——
     * 而一个总在误报的校验，最后一定被关掉，那比没有校验更糟。
     *
     * @param domainsById 已加载的域，键为域 id
     */
    public static List<EngineConfigViolation> validateAssistantReachability(
            Assistant assistant, Map<String, Domain> domainsById) {
        List<EngineConfigViolation> out = new ArrayList<>();
        String id = assistant.id();
        String name = assistant.displayName();

        if (!assistant.isOnline()) {
            // 草稿 / 已下线的助理本来就不该被问，授权写成什么样都无所谓
            return out;
        }

        AssistantPermission perm = assistant.permission();

        // ① 开着访问控制却一个人都没授权 = 谁都用不了它（在界面上根本不会出现）
        if (perm.enabled() && perm.grantRoles().isEmpty() && perm.grantUsers().isEmpty()) {
            out.add(new EngineConfigViolation(
                    EngineConfigViolation.Category.ASSISTANT_DEAD_END, id, name,
                    "已上线且启用了访问控制，但授权名单为空——**任何人都看不到这个助理**。"
                            + "要么补上授权，要么把它改成草稿/下线"));
            return out;
        }

        // ② 没有绑定域：任何人经它提问都走不到渲染
        String domainId = assistant.primaryDomainId();
        if (domainId == null || domainId.isBlank()) {
            out.add(new EngineConfigViolation(
                    EngineConfigViolation.Category.ASSISTANT_DEAD_END, id, name,
                    "没有绑定数据域，任何人经它提问都会失败（问数需要一个落点域）"));
            return out;
        }

        Domain domain = domainsById.get(domainId);
        if (domain == null) {
            out.add(new EngineConfigViolation(
                    EngineConfigViolation.Category.ASSISTANT_DEAD_END, id, name,
                    "绑定的数据域 id=" + domainId + " 不存在或未启用——经它提问一律失败"));
            return out;
        }

        // ③ 域对所有人开放时，助理怎么授权都到得了，无需再比
        if (domain.permission().isPublic()) {
            return out;
        }

        Set<String> covered = domain.permission().grantRoles();
        Set<String> uncovered = new TreeSet<>(perm.grantRoles());
        uncovered.removeAll(covered);
        for (String role : uncovered) {
            out.add(new EngineConfigViolation(
                    EngineConfigViolation.Category.ASSISTANT_DEAD_END, id, name,
                    "角色「%s」在这个助理的授权名单里，但它在绑定的域「%s」上没有访问权——"
                            .formatted(role, domain.displayName())
                            + "该角色看得见助理、点进去必定被拒（域层拦下）。"
                            + "**授予助理不等于授予域**，两道闸门要分别配"));
        }
        return out;
    }

    /**
     * 数据源 id 比对。
     *
     * <p>域的 {@code datasourceId} 是字符串、数据源主键是 long，
     * 直接比会因类型不同而恒不相等——那会让这条校验**永远通过**，
     * 而"校验永远通过"比没有校验更糟。
     */
    private static DatasourceDef findDatasource(Map<Long, DatasourceDef> datasources, String domainDatasourceId) {
        try {
            return datasources.get(Long.parseLong(domainDatasourceId.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

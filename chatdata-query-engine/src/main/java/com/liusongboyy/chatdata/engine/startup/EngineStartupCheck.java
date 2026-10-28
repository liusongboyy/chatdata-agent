package com.liusongboyy.chatdata.engine.startup;

import com.liusongboyy.chatdata.engine.datasource.DatasourceDef;
import com.liusongboyy.chatdata.engine.semantic.Assistant;
import com.liusongboyy.chatdata.engine.semantic.AssistantLoader;
import com.liusongboyy.chatdata.engine.semantic.AssistantRow;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.DomainLoader;
import com.liusongboyy.chatdata.engine.semantic.DomainRow;
import com.liusongboyy.chatdata.engine.semantic.DomainTableRow;
import com.liusongboyy.chatdata.engine.semantic.mapper.SemanticConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 启动时校验引擎配置：每个启用中的域都必须**可用**且**已设授权**。
 *
 * <p>这是厂商那套 {@code AccessGrantStartupCheck} 在自研引擎里的等价物，
 * 见 ADR-0002 的 R1 与 PRD FR-3.8。
 *
 * <p><strong>运维提示：</strong>配置存放在 {@code ask_domain} / {@code ask_domain_table}，
 * 用 SQL 就能改，因此不会出现"改不了配置所以起不来、起不来所以改不了配置"的死锁。
 * 这一点很重要——它是"默认拒绝启动"这个选择的底气。
 *
 * <h2>校验的是「运行时真正生效的那份配置」</h2>
 *
 * <p>数据策略归业务模块所有（ADR-0010）：运行时读的就是
 * {@code ask_domain.perm_json} / {@code ask_assistant.perm_json}，
 * 启动校验也用同一份，不存在“校验看一份、运行看另一份”的偏差。
 *
 * <p>取不到角色清单（IAM 不可用）时**跳过**角色引用校验并记一条日志：
 * 拦启动会把一次 IAM 抖动变成崩溃循环，静默跳过又会让"校验通过"变成一句空话。
 */
@Component
@EnableConfigurationProperties(EngineStartupProperties.class)
public class EngineStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EngineStartupCheck.class);

    private final SemanticConfigMapper mapper;
    private final EngineStartupProperties properties;
    /**
     * 角色清单。**可选**：宿主没提供就直接跳过角色引用校验。
     *
     * <p>用 {@code ObjectProvider} 而不是必填依赖，是为了让这一层校验保持"可有可无"——
     * 引擎单测、或把引擎嵌进一个没有角色目录的系统时，不该因为缺这个东西起不来。
     */
    private final ObjectProvider<RoleDirectory> roleDirectory;

    /** 不带角色清单：跳过角色引用校验，仍校验本地配置。 */
    public EngineStartupCheck(SemanticConfigMapper mapper, EngineStartupProperties properties) {
        this(mapper, properties, null);
    }

    /**
     * Spring 用的构造器。
     *
     * <p><strong>{@code @Autowired} 不能省。</strong>类里有多个构造器时，
     * Spring 不会自己挑一个——它会去找**无参**构造器，找不到就报
     * {@code No default constructor found}。加了这个注解才是明确告诉它用哪个。
     */
    @Autowired
    public EngineStartupCheck(SemanticConfigMapper mapper, EngineStartupProperties properties,
                              ObjectProvider<RoleDirectory> roleDirectory) {
        this.mapper = mapper;
        this.properties = properties;
        this.roleDirectory = roleDirectory;
    }

    /**
     * 已知角色（角色码 → 是否启用）。
     *
     * @return {@code null} 表示拿不到角色清单，此时**跳过**角色引用校验——
     *         把"拿不到"当成"一个角色都没有"会把所有配置误报成"角色不存在"
     */
    private Map<String, Boolean> knownRoles() {
        Map<String, Boolean> roles = null;
        if (roleDirectory != null) {
            RoleDirectory dir = roleDirectory.getIfAvailable();
            if (dir != null) {
                roles = dir.roles().orElse(null);
            }
        }
        if (roles == null) {
            // **必须留下痕迹。**静默跳过比不校验更糟：启动日志里写着"校验通过"，
            // 而角色引用这一项根本没查过。真要出问题时，人会以为它查过了
            log.info("未取得角色清单，本次跳过「权限配置引用的角色是否存在」这一项校验");
        } else {
            log.info("角色清单已加载（{} 个角色），将校验权限配置里的角色引用", roles.size());
        }
        return roles;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            log.warn("引擎配置启动校验被关闭（chatdata.engine.startup.enabled=false）。"
                    + "一个漏配授权的域会对所有已认证用户开放，且失败是静默的");
            return;
        }

        List<EngineConfigViolation> violations = collectViolations();

        if (violations.isEmpty()) {
            log.info("引擎配置校验通过");
            return;
        }

        report(violations);
    }

    /**
     * 加载全部启用中的域并逐条校验。加载失败本身也是一条违规。
     *
     * <p>包级可见以便测试直接驱动——不必为了测它去构造一个 {@code ApplicationArguments}。
     */
    List<EngineConfigViolation> collectViolations() {
        List<EngineConfigViolation> violations = new ArrayList<>();
        Map<String, Boolean> roles = knownRoles();

        Map<Long, DatasourceDef> datasources = new LinkedHashMap<>();
        for (DatasourceDef ds : mapper.selectEnabledDatasources()) {
            datasources.put(ds.id(), ds);
            // 数据源自身的问题也要报——否则一个 dbType 写错的数据源，
            // 只会在引用它的域上报"数据源不可用"，真实原因被藏在下一层
            violations.addAll(EngineConfigValidator.validateDatasource(ds));
        }

        // 助理的可达性要拿域来比对，故把加载成功的域留一份
        Map<String, Domain> loadedDomains = new LinkedHashMap<>();

        List<DomainRow> rows = mapper.selectEnabledDomains();
        for (DomainRow row : rows) {
            String dbType = datasources.containsKey(row.datasourceId())
                    ? datasources.get(row.datasourceId()).dbType()
                    : null;

            Domain domain;
            try {
                List<DomainTableRow> tables = mapper.selectTablesByDomain(row.id());
                domain = DomainLoader.load(row, tables, List.of(), List.of(), dbType);
            } catch (Exception e) {
                // 加载失败 = 该域完全不可用。这条必须单独报，
                // 否则运维看到的是"域 X 校验失败"却不知道是加载阶段就挂了
                violations.add(new EngineConfigViolation(
                        EngineConfigViolation.Category.DOMAIN_NOT_LOADABLE,
                        String.valueOf(row.id()), row.name(),
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                continue;
            }
            violations.addAll(EngineConfigValidator.validate(domain, datasources));

            // 角色引用：写的角色是否真的存在。**与上面的校验分开**，
            // 因为它判的是"配置写错的角色名"，而不是"域配得对不对"
            if (roles != null) {
                violations.addAll(RoleReferenceValidator.validateDomain(
                        domain.permission(), domain.id(), domain.displayName(), roles));
            }
            loadedDomains.put(domain.id(), domain);
        }

        violations.addAll(validateAssistants(roles, loadedDomains));
        return violations;
    }

    /**
     * 校验在线助理。
     *
     * <p>助理此前**完全不在启动校验范围内**——只有域被检查了，而助理的授权写错
     * （比如授权里写了一个不存在的角色）同样是静默失效，且它决定的是
     * "用户能不能看到这个助理"，界面上的表现是"助理凭空少了一个"。
     */
    private List<EngineConfigViolation> validateAssistants(Map<String, Boolean> roles,
                                                          Map<String, Domain> domainsById) {
        List<EngineConfigViolation> out = new ArrayList<>();
        List<AssistantRow> assistants;
        try {
            assistants = mapper.selectOnlineAssistants();
        } catch (Exception e) {
            log.warn("读取在线助理失败，跳过助理校验：{}", e.getMessage());
            return out;
        }

        for (AssistantRow row : assistants) {
            String id = String.valueOf(row.id());

            Assistant assistant;
            try {
                assistant = AssistantLoader.load(row);
            } catch (Exception e) {
                // 与域一致：加载失败本身要报，否则"助理少了一个"没人知道为什么
                out.add(new EngineConfigViolation(
                        EngineConfigViolation.Category.DOMAIN_NOT_LOADABLE, id, row.name(),
                        "助理配置无法解析：" + (e.getMessage() == null
                                ? e.getClass().getSimpleName() : e.getMessage())));
                continue;
            }

            // 与绑定域的授权交叉比对：看得见、点进去必然被拒的那种死路
            out.addAll(EngineConfigValidator.validateAssistantReachability(assistant, domainsById));

            if (roles != null) {
                out.addAll(RoleReferenceValidator.validateAssistant(
                        assistant.permission().enabled(), assistant.permission().grantRoles(),
                        id, row.name(), roles));
            }
        }
        return out;
    }

    private void report(List<EngineConfigViolation> violations) {
        // **先按严重度分，再按类别分。**
        // 分错了的后果不对称：把 WARNING 当 ERROR 会拦住一次本该正常的启动，
        // 把 ERROR 当 WARNING 会放过一次静默的数据外露
        List<EngineConfigViolation> errors = violations.stream()
                .filter(v -> v.category().isFatal()).toList();
        List<EngineConfigViolation> warnings = violations.stream()
                .filter(v -> !v.category().isFatal()).toList();

        if (!warnings.isEmpty()) {
            // 警告**永远**只记日志，不受 fail-on-violation 影响——
            // 那个开关管的是"要不要因此拒绝启动"，而警告的定义就是不拒绝
            log.warn("引擎配置有 {} 项值得处理的问题（不影响启动）：{}",
                    warnings.size(), System.lineSeparator() + format(warnings));
        }

        if (errors.isEmpty()) {
            log.info("引擎配置校验通过（{} 项警告）", warnings.size());
            return;
        }

        String message = format(errors);

        if (properties.isFailOnViolation()) {
            // 抛出而不是 System.exit：让 Spring 走正常的失败路径，
            // 把这条信息连同上下文一起打进启动日志
            throw new IllegalStateException(message);
        }
        log.error(message);
    }

    /** 分组渲染。同一类别的处理方式相同，混在一个列表里读起来费劲。 */
    private static String format(List<EngineConfigViolation> violations) {
        Map<EngineConfigViolation.Category, List<EngineConfigViolation>> byCategory =
                violations.stream().collect(Collectors.groupingBy(
                        EngineConfigViolation::category,
                        LinkedHashMap::new,
                        Collectors.toList()));

        StringBuilder sb = new StringBuilder();
        sb.append(violations.size()).append(" 项引擎配置违规：").append(System.lineSeparator());
        byCategory.forEach((category, list) -> {
            sb.append(System.lineSeparator())
                    .append("  【").append(category.label()).append("】").append(list.size()).append(" 项")
                    .append(System.lineSeparator());
            list.forEach(v -> sb.append("    - ")
                    .append(v.domainName()).append(" (id=").append(v.domainId()).append(")：")
                    .append(v.message())
                    .append(System.lineSeparator()));
        });
        sb.append(System.lineSeparator())
                .append("配置存放在 ask_domain / ask_domain_table / ask_assistant，用 SQL 即可修正。");
        return sb.toString();
    }
}

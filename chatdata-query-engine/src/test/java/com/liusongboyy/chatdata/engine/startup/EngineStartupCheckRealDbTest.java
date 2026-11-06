package com.liusongboyy.chatdata.engine.startup;

import com.liusongboyy.chatdata.engine.semantic.AssistantRow;
import com.liusongboyy.chatdata.engine.semantic.DomainRow;
import com.liusongboyy.chatdata.engine.semantic.DomainTableRow;
import com.liusongboyy.chatdata.engine.semantic.mapper.SemanticConfigMapper;
import com.liusongboyy.chatdata.engine.datasource.DatasourceDef;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动校验的真库验证：**对着种子数据跑一遍，应当零违规**。
 *
 * <p>这条测试的价值在于它是一个**回归哨兵**——种子数据或校验规则任一方改坏，
 * 它都会红。前面那些单测各自验证一条规则，这条验证"整套规则在真实配置上不误报"。
 *
 * <p>误报比漏报更危险：一个总是红的启动校验会被关掉，
 * 而关掉之后连真的违规也看不见了。
 */
class EngineStartupCheckRealDbTest {

    private static final String URL =
            "jdbc:mysql://127.0.0.1:3306/chatdata_ask?useUnicode=true&characterEncoding=UTF-8"
                    + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    /** 角色清单在 IAM 库里（`sys_role` 已退役，见 `chatdata-agent-design/iam-platform/spec.md`）。 */
    private static final String IAM_URL =
            "jdbc:mysql://127.0.0.1:3306/chatdata_iam?useUnicode=true&characterEncoding=UTF-8"
                    + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    private static final String USER = "root";
    private static final String PASSWORD = "root";

    /** 供测试注入数据。用动态代理免去为几条只读方法建一个测试 Mapper。 */
    private static final class FakeMapper implements InvocationHandler {
        private final List<DatasourceDef> datasources = new ArrayList<>();
        private final List<DomainRow> domains = new ArrayList<>();
        private final List<DomainTableRow> tables = new ArrayList<>();
        private final List<AssistantRow> assistants = new ArrayList<>();

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            return switch (method.getName()) {
                case "selectEnabledDatasources" -> List.copyOf(datasources);
                case "selectEnabledDomains" -> List.copyOf(domains);
                case "selectTablesByDomain" -> tables.stream()
                        .filter(t -> t.domainId() == (Long) args[0])
                        .toList();
                case "selectOnlineAssistants" -> List.copyOf(assistants);
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }

    private static FakeMapper fake = new FakeMapper();

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(reachable(URL), "本地 MySQL 不可用，跳过启动校验的真库验证");
        // 角色清单在 IAM 库；少了它这条测试没有意义，跳过而不是报"读取 iam_role 失败"
        Assumptions.assumeTrue(reachable(IAM_URL), "本地 IAM 库不可用，跳过启动校验的真库验证");

        SemanticConfigMapper realMapper = buildMapper();
        fake.datasources.addAll(realMapper.selectEnabledDatasources());
        fake.domains.addAll(realMapper.selectEnabledDomains());
        for (DomainRow d : fake.domains) {
            fake.tables.addAll(realMapper.selectTablesByDomain(d.id()));
        }
        fake.assistants.addAll(realMapper.selectOnlineAssistants());

        knownRoles = Map.copyOf(loadRoleCodes());
    }

    private static boolean reachable(String url) {
        try (Connection c = DriverManager.getConnection(url, USER, PASSWORD)) {
            return c.isValid(3);
        } catch (Exception e) {
            return false;
        }
    }

    private static Map<String, Boolean> knownRoles = Map.of();

    /**
     * 直接从 IAM 的 {@code iam_role} 读角色清单。
     *
     * <p>用裸 JDBC 而不是 {@code IamRoleDirectoryClient}：引擎模块不依赖任何 IAM 客户端
     * （角色存在哪里是宿主应用的事，见 {@link RoleDirectory} 的说明），
     * 为了一个测试把那条依赖引进来，会把一条本来有意不存在的边固定下来。
     *
     * <p>读的是**运行期真正生效的那张表**。这里曾经读 {@code sys_role}：
     * 那张表比 IAM 多一个 `管理员`，于是"种子数据零违规"是靠一份更宽松的角色清单
     * 换来的——测试绿着，生产上却会把引用 `管理员` 的配置判成失效。
     */
    private static Map<String, Boolean> loadRoleCodes() {
        Map<String, Boolean> roles = new LinkedHashMap<>();
        try (Connection c = DriverManager.getConnection(IAM_URL, USER, PASSWORD);
             java.sql.Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT code, enabled FROM iam_role")) {
            while (rs.next()) {
                String code = rs.getString(1);
                if (code != null && !code.isBlank()) {
                    roles.put(code, rs.getBoolean(2));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取 iam_role 失败", e);
        }
        return roles;
    }

    private static SemanticConfigMapper buildMapper() {
        com.mysql.cj.jdbc.MysqlDataSource ds = new com.mysql.cj.jdbc.MysqlDataSource();
        ds.setURL(URL);
        ds.setUser(USER);
        ds.setPassword(PASSWORD);

        org.apache.ibatis.session.Configuration cfg = new org.apache.ibatis.session.Configuration(
                new org.apache.ibatis.mapping.Environment("test",
                        new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), ds));
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(SemanticConfigMapper.class);
        return new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(cfg)
                .openSession().getMapper(SemanticConfigMapper.class);
    }

    private static EngineStartupCheck check() {
        SemanticConfigMapper proxy = (SemanticConfigMapper) Proxy.newProxyInstance(
                SemanticConfigMapper.class.getClassLoader(),
                new Class<?>[]{SemanticConfigMapper.class},
                fake);
        // 用 ObjectProvider 的桩把角色清单喂进去，走的是与生产同一条路径
        ObjectProvider<RoleDirectory> dir = provider(() -> Optional.of(knownRoles));
        return new EngineStartupCheck(proxy, new EngineStartupProperties(), dir);
    }

    /**
     * 最小的 {@link ObjectProvider} 桩。
     *
     * <p>只有 {@code getObject(Object...)}、{@code getObject()}、{@code iterator()}
     * 是抽象方法，其余（含 {@code getIfAvailable}）都有默认实现并会走到 {@code getObject}。
     */
    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public java.util.Iterator<T> iterator() {
                return java.util.List.of(value).iterator();
            }
        };
    }

    /**
     * 种子域：`sql/09-ask-engine-seed.sql` 建出来的那些，id 与名称都固定。
     *
     * <p><strong>只断言种子域，不断言整个库。</strong>这条哨兵要抓的是"校验规则误报"，
     * 而库里还有人在管理台建的域——建到一半（没绑数据源、没注册表）时校验本来就该报，
     * 那是校验在正常工作。要求全库零违规会把"用户刚建了一个域"变成构建失败。
     */
    private static final Set<Long> SEED_DOMAIN_IDS = Set.of(1L);

    @Test
    @DisplayName("★ 种子域应当零违规 —— 这条是防止校验规则误报的回归哨兵")
    void seedDataHasNoViolations() {
        List<EngineConfigViolation> violations = check().collectViolations();

        System.out.println("[启动校验] 全库违规数 = " + violations.size());
        violations.forEach(v -> System.out.println("  " + v.describe()));

        // 违规只对**种子域**断言。其它域（管理台手工建的）建到一半时报警是正确的
        List<EngineConfigViolation> seedViolations = violations.stream()
                .filter(v -> v.domainId() != null
                        && SEED_DOMAIN_IDS.contains(Long.valueOf(v.domainId())))
                .toList();

        assertThat(seedViolations)
                .as("种子域是刻意配成合规的；这里非空说明要么种子被改坏了，"
                        + "要么校验规则误报——而总是红的校验会被关掉，那比不校验更糟")
                .isEmpty();
    }

    /**
     * 角色引用这条校验**真的会触发**。
     *
     * <p>做法是把角色清单里去掉一个真实存在的角色，配置一个字不改——
     * 于是所有引用了该角色的授权与规则都成了"引用了不存在的角色"。
     * 这样验的是"校验接上了没有"，而不是"规则写得对不对"（后者由纯单测覆盖），
     * 且**不需要改动任何真实数据**。
     */
    @Test
    @DisplayName("★ 抽掉一个真实角色后，引用它的配置必须全部被报出来")
    void detectsDanglingRoleReferences() {
        Assumptions.assumeTrue(knownRoles.containsKey("分析师"),
                "种子角色「分析师」不在 iam_role 里，本用例无意义");

        Map<String, Boolean> withoutAnalyst = new LinkedHashMap<>(knownRoles);
        withoutAnalyst.remove("分析师");

        SemanticConfigMapper proxy = (SemanticConfigMapper) Proxy.newProxyInstance(
                SemanticConfigMapper.class.getClassLoader(),
                new Class<?>[]{SemanticConfigMapper.class}, fake);
        EngineStartupCheck c = new EngineStartupCheck(proxy, new EngineStartupProperties(),
                provider(() -> Optional.of(withoutAnalyst)));

        List<EngineConfigViolation> violations = c.collectViolations();

        // 只看**角色引用**这一类。库里还可能有与角色无关的违规（例如管理台新建、
        // 尚未配表就启用的域），那些和本用例要验的事无关，不该让它失败
        // 只看**种子域**上的角色引用。库里还有人在管理台建的域（同样可能引用「分析师」），
        // 那些违规是正确的，但不是本用例要验的对象
        List<EngineConfigViolation> dangling = violations.stream()
                .filter(v -> v.category() == EngineConfigViolation.Category.DANGLING_GRANT_ROLE
                        || v.category() == EngineConfigViolation.Category.DANGLING_RULE_ROLE)
                .filter(v -> v.domainId() != null
                        && SEED_DOMAIN_IDS.contains(Long.valueOf(v.domainId())))
                .toList();

        assertThat(dangling)
                .as("「分析师」被引用于域的授权、助理的授权，以及一条列规则，抽掉后必须全部报出来")
                .isNotEmpty()
                .allSatisfy(v -> assertThat(v.message()).contains("分析师"));

        // 两类引用的严重度**必须不同**，这是这道校验的核心设计
        List<EngineConfigViolation> grants = dangling.stream()
                .filter(v -> v.category() == EngineConfigViolation.Category.DANGLING_GRANT_ROLE).toList();
        List<EngineConfigViolation> rules = dangling.stream()
                .filter(v -> v.category() == EngineConfigViolation.Category.DANGLING_RULE_ROLE).toList();

        // 断言"域与助理两类都覆盖到了"，而不是写死总条数：
        // 种子数据里加一个引用就会让写死的数字变红，而那不是校验逻辑坏了
        assertThat(grants)
                .as("种子域的授权 + 该域下助理的授权都引用了「分析师」")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(grants).allSatisfy(v -> assertThat(v.category().isFatal())
                .as("授权名单里的角色失效是「少给了权限」，用户会来报障，但不该拦住启动")
                .isFalse());

        // 种子数据里 保险分析域 有一条对 premium 脱敏的列规则，写的正是「分析师」。
        // 角色一旦失效，脱敏就整条不生效——这是**静默的数据过度暴露**，必须拦住启动
        assertThat(rules)
                .as("保险分析域的列规则引用了「分析师」")
                .hasSize(1);
        assertThat(rules.get(0).category().isFatal())
                .as("行列规则失效是「多给了权限」，与授权失效不是一个量级")
                .isTrue();
        assertThat(rules.get(0).message()).contains("明文");
    }

    @Test
    @DisplayName("★ 把某个域的授权清空后，校验必须报出来")
    void detectsMissingGrant() {
        // 复制一份种子数据，把 perm_json 换成空授权
        FakeMapper before = fake;
        try {
            fake = new FakeMapper();
            before.datasources.forEach(fake.datasources::add);
            before.domains.forEach(d -> fake.domains.add(new DomainRow(
                    d.id(), d.name(), d.datasourceId(), d.status(), d.joinsJson(),
                    // 空授权：厂商语义下这对所有已认证用户开放
                    "{\"v\":1,\"isPublic\":false,\"grantRoles\":[],\"grantUsers\":[]}")));
            fake.tables.addAll(before.tables);

            List<EngineConfigViolation> violations = check().collectViolations();

            assertThat(violations)
                    .as("授权为空是 ADR-0002 的 R1，必须被拦下")
                    .extracting(EngineConfigViolation::category)
                    .contains(EngineConfigViolation.Category.NO_GRANT);
        } finally {
            fake = before;
        }
    }

    @Test
    @DisplayName("★ 把某个域的 perm_json 改坏后，报的是「域加载失败」而不是别的")
    void detectsUnloadableDomain() {
        FakeMapper before = fake;
        try {
            fake = new FakeMapper();
            before.datasources.forEach(fake.datasources::add);
            before.domains.forEach(d -> fake.domains.add(new DomainRow(
                    d.id(), d.name(), d.datasourceId(), d.status(), d.joinsJson(),
                    "{这不是合法 JSON")));
            fake.tables.addAll(before.tables);

            List<EngineConfigViolation> violations = check().collectViolations();

            // 断言"存在针对该域的加载失败违规"，而**不是**断言总数——
            // 后者假设库里只有一个启用域，而管理员随时可能再加一个。
            // 这类"数量恰好等于 N"的断言在有真实数据的库里注定会烂。
            assertThat(violations)
                    .as("加载失败要单独归类——否则运维看到的是「域 X 校验失败」，"
                            + "却不知道它在加载阶段就挂了")
                    .anySatisfy(v -> {
                        assertThat(v.category())
                                .isEqualTo(EngineConfigViolation.Category.DOMAIN_NOT_LOADABLE);
                        assertThat(v.domainName()).isEqualTo("保险分析域");
                    });
        } finally {
            fake = before;
        }
    }
}

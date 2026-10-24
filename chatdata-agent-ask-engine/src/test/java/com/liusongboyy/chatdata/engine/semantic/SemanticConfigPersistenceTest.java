package com.liusongboyy.chatdata.engine.semantic;

import com.liusongboyy.chatdata.engine.datasource.DatasourceDef;
import com.liusongboyy.chatdata.engine.datasource.dialect.SqlDialects;
import com.liusongboyy.chatdata.engine.nlsql.AskDsl;
import com.liusongboyy.chatdata.engine.nlsql.AskSqlRenderer;
import com.liusongboyy.chatdata.engine.nlsql.RenderedSql;
import com.liusongboyy.chatdata.engine.permission.PermissionContext;
import com.liusongboyy.chatdata.engine.permission.PermissionDecision;
import com.liusongboyy.chatdata.engine.permission.PermissionGuard;
import com.liusongboyy.chatdata.engine.semantic.mapper.SemanticConfigMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闭环验证：**库里的配置 → Mapper → 加载器 → 渲染器 → 真实数据**。
 *
 * <p>前面每个环节都有独立的测试，但独立通过不等于串起来能跑：
 * 列名映射对不对、record 能不能被 MyBatis 构造、JSON 能不能解析、
 * 渲染出的 SQL 能不能真执行——这些只有整条链路跑一遍才知道。
 *
 * <p>依赖 {@code sql/08-ask-engine.sql} 与 {@code sql/09-ask-engine-seed.sql} 已执行。
 * 连不上库时**跳过**，但跳过意味着本次构建没有验证这一层。
 */
class SemanticConfigPersistenceTest {

    /**
     * 配置库与数据库**已分开**：语义配置（{@code ask_*}）在 chatdata_ask，
     * 被查询的业务数据（{@code ins_*}）在 chatdata_demo。
     *
     * <p>本测试的链路是「配置库 → Mapper → 加载器 → 渲染器 → 数据库」，
     * 两个库都要连。合成一个 URL 只能测到一半。
     */
    private static final String CONFIG_URL =
            "jdbc:mysql://127.0.0.1:3306/chatdata_ask?useUnicode=true&characterEncoding=UTF-8"
                    + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    private static final String DATA_URL =
            "jdbc:mysql://127.0.0.1:3306/chatdata_demo?useUnicode=true&characterEncoding=UTF-8"
                    + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    private static final String USER = "root";
    private static final String PASSWORD = "root";

    /** 金标准，由 SQL 直接算出。 */
    private static final BigDecimal GOLD_华东_生效 = new BigDecimal("369140.20");

    private static SqlSessionFactory factory;

    @BeforeAll
    static void setUp() {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setURL(CONFIG_URL);
        ds.setUser(USER);
        ds.setPassword(PASSWORD);

        boolean ok;
        try (Connection c = ds.getConnection()) {
            ok = c.isValid(3);
        } catch (Exception e) {
            ok = false;
        }
        Assumptions.assumeTrue(ok, "本地 MySQL 不可用，跳过持久化闭环验证");

        Environment env = new Environment("test", new JdbcTransactionFactory(), ds);
        Configuration cfg = new Configuration(env);
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(SemanticConfigMapper.class);
        factory = new SqlSessionFactoryBuilder().build(cfg);
    }

    /** 从库里装配一个语义模型。 */
    private static SemanticModel loadModelFromDb(SqlSession session) {
        List<DomainRow> domains = session.getMapper(SemanticConfigMapper.class).selectEnabledDomains();
        assertThat(domains).as("种子数据应当至少有一个启用的域").isNotEmpty();

        DomainRow row = domains.stream()
                .filter(d -> "保险分析域".equals(d.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到种子域「保险分析域」，sql/09-ask-engine-seed.sql 执行了吗？"));

        List<DomainTableRow> tables = session.getMapper(SemanticConfigMapper.class)
                .selectTablesByDomain(row.id());

        // 从绑定的数据源取 dbType —— 渲染器要用它选方言
        String dbType = session.getMapper(SemanticConfigMapper.class)
                .selectEnabledDatasources().stream()
                .filter(d -> String.valueOf(d.id()).equals(String.valueOf(row.datasourceId())))
                .map(DatasourceDef::dbType)
                .findFirst()
                .orElseThrow(() -> new AssertionError("域绑定的数据源不存在"));

        return new SemanticModel(DomainLoader.load(row, tables, dbType));
    }

    private static Object execute(RenderedSql r) throws Exception {
        try (Connection c = java.sql.DriverManager.getConnection(DATA_URL, USER, PASSWORD);
             PreparedStatement ps = c.prepareStatement(r.sql())) {
            for (int i = 0; i < r.params().size(); i++) {
                ps.setObject(i + 1, r.params().get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getObject(1);
            }
        }
    }

    @Test
    @DisplayName("★ 库里的配置能装配成模型 —— 列名映射与 record 构造都对")
    void mapperMapsRowsToRecords() {
        try (SqlSession session = factory.openSession()) {
            SemanticModel m = loadModelFromDb(session);

            assertThat(m.domain().name()).isEqualTo("保险分析域");
            assertThat(m.domain().dbType()).as("dbType 应从绑定的数据源解析出来").isEqualTo("MYSQL5.7");
            // 断言"种子里那两张在"，而不是写死总数：
            // 保险域后来又加了 ins_claim，写死 hasSize 会让一次正常建模变更把测试打红
            assertThat(m.visibleTables()).as("种子数据含保单表与客户表")
                    .hasSizeGreaterThanOrEqualTo(2);
            assertThat(m.visibleTables()).extracting(RegisteredTable::name)
                    .contains("ins_policy", "ins_customer");

            // 字段能解析出来，说明 columns_json 解析正确
            assertThat(m.resolveMetric("保费").column()).isEqualTo("premium");
            assertThat(m.resolveDimension("区域").column()).isEqualTo("region");
            assertThat(m.resolveDimension("生效日期").column()).isEqualTo("effect_date");
        }
    }

    @Test
    @DisplayName("★ 权限配置从库里读出来即生效 —— 分析师在 unrestrictedRoles 里")
    void permissionLoadedFromDb() {
        try (SqlSession session = factory.openSession()) {
            SemanticModel m = loadModelFromDb(session);

            PermissionDecision d = PermissionGuard.check(
                    null, m.domain().permission(), PermissionContext.of("u1", Set.of("分析师")));

            assertThat(d).isInstanceOfSatisfying(PermissionDecision.Allowed.class,
                    x -> assertThat(x.maskedColumns())
                            .as("种子配置里分析师对 premium 脱敏")
                            .containsExactly("premium"));
        }
    }

    @Test
    @DisplayName("★★ 全链路：库里配置 → 渲染 SQL → 真执行 → 答案与金标准一致")
    void endToEndFromDatabaseToRealData() throws Exception {
        try (SqlSession session = factory.openSession()) {
            SemanticModel m = loadModelFromDb(session);

            PermissionDecision ok = PermissionGuard.check(
                    null, m.domain().permission(), PermissionContext.of("u1", Set.of("分析师")));
            assertThat(ok.isAllowed()).isTrue();

            // 注意：分析师对 premium 脱敏，所以这条查询的金额会是 ***
            // 这里换一个不脱敏的角色来验证金额 —— 用「区域经理」，它受行规则限制
            PermissionDecision manager = PermissionGuard.check(
                    null, m.domain().permission(), PermissionContext.of("u1", Set.of("区域经理")));
            assertThat(manager.isAllowed()).isTrue();

            AskDsl dsl = new AskDsl(List.of("保费"), List.of(), null,
                    List.of(new AskDsl.Filter("状态", "=", "生效")));

            RenderedSql r = AskSqlRenderer.render(
                    dsl, m, SqlDialects.of(m.domain().dbType()), manager, LocalDate.now());

            System.out.println("[持久化闭环] 从库里装配后渲染出的 SQL：");
            System.out.println("  " + r.sql());
            System.out.println("  参数：" + r.params());

            BigDecimal actual = new BigDecimal(String.valueOf(execute(r)));

            assertThat(actual)
                    .as("区域经理受行规则限制只看华东；这个数字来自库里的 perm_json，不是测试里写死的")
                    .isEqualByComparingTo(GOLD_华东_生效);
        }
    }

    @Test
    @DisplayName("★ 库里的配置足以定位时间列 —— 渲染器按别名找到「生效日期」")
    void timeColumnResolvableFromDbConfig() throws Exception {
        try (SqlSession session = factory.openSession()) {
            SemanticModel m = loadModelFromDb(session);

            PermissionDecision manager = PermissionGuard.check(
                    null, m.domain().permission(), PermissionContext.of("u1", Set.of("区域经理")));

            AskDsl dsl = new AskDsl(List.of("保费"), List.of(),
                    new AskDsl.TimeRange("今年", null, null), List.of());

            RenderedSql r = AskSqlRenderer.render(
                    dsl, m, SqlDialects.of(m.domain().dbType()), manager, LocalDate.now());

            assertThat(r.sql()).contains("`t1`.`effect_date`");
            assertThat(r.params()).hasSize(3);   // 起、止、以及行规则的 region
        }
    }

    @Test
    @DisplayName("★ 密码不进列表投影，只能单独取 —— 断言的是设计，不是数据")
    void passwordIsSeparateFromListProjection() {
        // 断言 DatasourceDef **根本没有**密码字段。
        // 这比"取出来是 null"结实：后者依赖种子数据的状态，
        // 一旦有人给本地库补了密码，测试就会红——而它本该红在别的地方。
        assertThat(DatasourceDef.class.getRecordComponents())
                .as("密码一旦成为列表投影的字段，它就会跟着对象出现在日志、缓存、异常堆栈里")
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("password", "passwordEnc", "password_enc");

        try (SqlSession session = factory.openSession()) {
            SemanticConfigMapper mapper = session.getMapper(SemanticConfigMapper.class);

            List<DatasourceDef> list = mapper.selectEnabledDatasources();
            assertThat(list).isNotEmpty();

            // 单独取得到（种子里可能为空，也可能已被本地验证填过——两者都正常）
            String enc = mapper.selectDatasourcePassword(list.get(0).id());
            assertThat(list.get(0).toString())
                    .as("toString 也不能泄露连接串与用户名")
                    .doesNotContain("jdbc:")
                    .doesNotContain("root");
            // 只要求"能单独取"，不要求具体取值
            assertThat(enc == null || !enc.isBlank()).isTrue();
        }
    }

    @Test
    @DisplayName("批量取表的 SQL 可用 —— 避免按域逐个查产生 N+1")
    void batchTableQueryWorks() {
        try (SqlSession session = factory.openSession()) {
            SemanticConfigMapper mapper = session.getMapper(SemanticConfigMapper.class);
            List<Long> ids = mapper.selectEnabledDomains().stream().map(DomainRow::id).toList();

            List<DomainTableRow> rows = mapper.selectTablesByDomains(ids);

            assertThat(rows).isNotEmpty();
            assertThat(rows).allSatisfy(r -> assertThat(ids).contains(r.domainId()));
        }
    }

    /**
     * 诊断输出：库里各域与其表数量。
     *
     * <p>这条测试的断言很轻——它的价值在**输出**：当别的测试失败时，
     * 这几行能立刻说明"是种子数据没灌，还是加载逻辑有问题"。
     */
    @Test
    @DisplayName("诊断输出：库里各域与其表数量")
    void diagnosticDump() {
        try (SqlSession session = factory.openSession()) {
            SemanticConfigMapper mapper = session.getMapper(SemanticConfigMapper.class);
            List<DomainRow> domains = mapper.selectEnabledDomains();

            System.out.println("[持久化闭环] 库中启用中的域：");
            for (DomainRow d : domains) {
                List<DomainTableRow> tables = mapper.selectTablesByDomain(d.id());
                System.out.printf("  id=%d name=%s 表数=%d%n", d.id(), d.name(), tables.size());
            }

            assertThat(domains).as("至少要有种子域，否则后面的测试全都无从谈起").isNotEmpty();
        }
    }

    @Test
    @DisplayName("★★ 多表 join 全链路：库里配置 → 两表 join → 真执行 → 不产生重复行")
    void multiTableJoinFromDatabase() throws Exception {
        try (SqlSession session = factory.openSession()) {
            SemanticModel m = loadModelFromDb(session);
            assertThat(m.visibleTables())
                    .as("种子数据应当包含保单表与客户表")
                    .hasSizeGreaterThanOrEqualTo(2);

            PermissionDecision manager = PermissionGuard.check(
                    null, m.domain().permission(), PermissionContext.of("u1", Set.of("区域经理")));

            // 指标在保单表、维度在客户表 —— 必须 join 才查得出来
            AskDsl dsl = new AskDsl(List.of("保费"), List.of("客户姓名"), null,
                    List.of(new AskDsl.Filter("状态", "=", "生效")));

            RenderedSql r = AskSqlRenderer.render(
                    dsl, m, SqlDialects.of(m.domain().dbType()), manager, LocalDate.now());

            System.out.println("[持久化闭环] 多表 join 渲染结果：");
            System.out.println("  " + r.sql());
            System.out.println("  参数：" + r.params());

            assertThat(r.sql())
                    .as("指标在保单表、维度在客户表，必须出现 join")
                    .contains("INNER JOIN")
                    .contains("ON");

            // 逐行取回并求和 —— 这不只是验证 SQL 能跑，而是验证**没有因 join 产生重复行**
            //
            // 注意：列序号**不能硬编码**。渲染器把维度排在指标前面
            // （SELECT 客户姓名, 保费），所以第 1 列是姓名不是金额。
            // RenderedSql.columns() 就是为此存在的。
            int premiumIdx = -1;
            for (int i = 0; i < r.columns().size(); i++) {
                if ("保费".equals(r.columns().get(i).name())) {
                    premiumIdx = i;
                }
            }
            assertThat(premiumIdx)
                    .as("渲染产物必须告诉调用方每一列是什么，否则只能靠猜列序")
                    .isGreaterThanOrEqualTo(0);

            BigDecimal sum = BigDecimal.ZERO;
            int rows = 0;
            try (Connection c = java.sql.DriverManager.getConnection(DATA_URL, USER, PASSWORD);
                 PreparedStatement ps = c.prepareStatement(r.sql())) {
                for (int i = 0; i < r.params().size(); i++) {
                    ps.setObject(i + 1, r.params().get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        sum = sum.add(new BigDecimal(String.valueOf(rs.getObject(premiumIdx + 1))));
                        rows++;
                    }
                }
            }

            System.out.printf("[持久化闭环] 按客户分组返回 %d 行，金额合计 %s%n", rows, sum);

            assertThat(rows).as("应当按客户拆成多行").isGreaterThan(1);
            assertThat(sum)
                    .as("多对一 join 不该放大金额；合计必须仍等于华东生效总额。"
                            + "若 join 写错（如连到错误的列）会因重复匹配而金额翻倍")
                    .isEqualByComparingTo(GOLD_华东_生效);
        }
    }
}

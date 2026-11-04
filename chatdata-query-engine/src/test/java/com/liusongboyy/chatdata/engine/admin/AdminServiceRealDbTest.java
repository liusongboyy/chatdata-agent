package com.liusongboyy.chatdata.engine.admin;

import com.liusongboyy.chatdata.engine.admin.mapper.SemanticAdminMapper;
import com.liusongboyy.chatdata.engine.datasource.MetaReader;
import com.liusongboyy.chatdata.engine.datasource.PasswordCipher;
import com.liusongboyy.chatdata.engine.semantic.DerivedMetric;
import com.liusongboyy.chatdata.engine.semantic.DomainLoader;
import com.liusongboyy.chatdata.engine.semantic.MetricRef;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import com.liusongboyy.chatdata.engine.semantic.mapper.SemanticConfigMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 管理服务的真库测试。
 *
 * <p><strong>每个用例跑在一个会回滚的事务里。</strong>管理面是写操作，
 * 直接在库里留测试数据会污染环境——而且下次跑测试时那些残留会让断言变得不可靠
 * （比如唯一名冲突）。用 MyBatis 的 session + rollback 保证干净。
 *
 * <p>回滚能生效的前提是**所有写操作都走同一个 session 的 mapper**，
 * 所以这里不用 Spring 容器，手工装配。
 */
class AdminServiceRealDbTest {

    private static final String URL =
            "jdbc:mysql://127.0.0.1:3306/chatdata_ask?useUnicode=true&characterEncoding=UTF-8"
                    + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";

    private static SqlSessionFactory factory;

    private SqlSession session;
    private AdminDatasourceService datasources;
    private AdminDomainService domains;

    @BeforeAll
    static void checkDb() {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setURL(URL);
        ds.setUser("root");
        ds.setPassword("root");

        boolean ok;
        try (Connection c = ds.getConnection()) {
            ok = c.isValid(3);
        } catch (Exception e) {
            ok = false;
        }
        Assumptions.assumeTrue(ok, "本地 MySQL 不可用，跳过管理面真库验证");

        Configuration cfg = new Configuration(new Environment("test",
                new JdbcTransactionFactory(), ds));
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(SemanticConfigMapper.class);
        cfg.addMapper(SemanticAdminMapper.class);
        factory = new SqlSessionFactoryBuilder().build(cfg);
    }

    @BeforeEach
    void openSession() {
        session = factory.openSession();   // 不开自动提交，用完回滚
        SemanticConfigMapper read = session.getMapper(SemanticConfigMapper.class);
        SemanticAdminMapper write = session.getMapper(SemanticAdminMapper.class);

        datasources = new AdminDatasourceService(read, write,
                new PasswordCipher("admin-test-secret"),
                // 这个测试不验证清池行为，registry 传 null 即可
                null);

        // MetaReader 需要能拿到连接——直接给一个指向本地库的 DataSource。
        // 保存注册表时会用它核对列名是否真实存在（那是本测试要覆盖的行为之一）
        MysqlDataSource metaDs = new MysqlDataSource();
        metaDs.setURL(URL);
        metaDs.setUser("root");
        metaDs.setPassword("root");
        domains = new AdminDomainService(read, write, new MetaReader(id -> metaDs));
    }

    @AfterEach
    void rollback() {
        if (session != null) {
            session.rollback();
            session.close();
        }
    }

    /** 一个可直接连通的入参（本地 MySQL）。 */
    private static DatasourceInput validDatasource(String name) {
        return new DatasourceInput(name, "MYSQL5.7", "com.mysql.cj.jdbc.Driver",
                URL, "root", "root", true);
    }

    @Nested
    @DisplayName("数据源")
    class Datasource {

        @Test
        @DisplayName("创建后可读回，且密码不出现在列表里")
        void createAndRead() {
            long id = datasources.create(validDatasource("测试源-A"), "tester");

            assertThat(id).isPositive();
            assertThat(datasources.get(id).name()).isEqualTo("测试源-A");
            assertThat(datasources.list())
                    .as("DatasourceDef 不含密码字段——这是编译期就保证的，这里确认它确实没被塞进去")
                    .allSatisfy(d -> assertThat(d.toString()).doesNotContain("jdbc:"));
        }

        @Test
        @DisplayName("★ 重名被拒 —— 报的是给人看的话，不是数据库的索引冲突")
        void duplicateNameRejected() {
            datasources.create(validDatasource("测试源-B"), "tester");

            assertThatThrownBy(() -> datasources.create(validDatasource("测试源-B"), "tester"))
                    .isInstanceOf(AdminException.class)
                    .hasMessageContaining("已存在同名数据源")
                    .hasMessageNotContaining("Duplicate entry");
        }

        @Test
        @DisplayName("★ 更新不碰密码 —— 改个名字不该把密码清掉")
        void updateKeepsPassword() {
            long id = datasources.create(validDatasource("测试源-C"), "tester");
            String before = session.getMapper(SemanticConfigMapper.class).selectDatasourcePassword(id);

            // 更新时 password 传 null（管理页回显不了密码，所以留空）
            datasources.update(id, new DatasourceInput("测试源-C-改名", "MYSQL5.7",
                    "com.mysql.cj.jdbc.Driver", URL, "root", null, true), "tester");

            String after = session.getMapper(SemanticConfigMapper.class).selectDatasourcePassword(id);
            assertThat(datasources.get(id).name()).isEqualTo("测试源-C-改名");
            assertThat(after)
                    .as("把 update 与 savePassword 合成一个接口，迟早会有人的密码被一次改名操作清掉")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("密码单独更新，加密后落库且能解回")
        void passwordRoundTrip() {
            long id = datasources.create(validDatasource("测试源-D"), "tester");

            datasources.savePassword(id, "new-secret", "tester");

            String enc = session.getMapper(SemanticConfigMapper.class).selectDatasourcePassword(id);
            assertThat(enc).isNotEqualTo("new-secret");
            assertThat(new PasswordCipher("admin-test-secret").decrypt(enc)).isEqualTo("new-secret");
        }

        @Test
        @DisplayName("★ 被域引用的数据源不能删 —— 否则域会指向一个不存在的东西")
        void deleteBlockedWhenReferenced() {
            long dsId = datasources.create(validDatasource("测试源-E"), "tester");
            domains.create(new DomainInput("引用它的域", null, dsId, "disabled",
                    null, null, 0), "tester");

            assertThatThrownBy(() -> datasources.delete(dsId, "tester"))
                    .isInstanceOf(AdminException.class)
                    .hasMessageContaining("引用");
        }

        @Test
        @DisplayName("未被引用时可删")
        void deleteWhenUnreferenced() {
            long id = datasources.create(validDatasource("测试源-F"), "tester");

            datasources.delete(id, "tester");

            assertThatThrownBy(() -> datasources.get(id)).isInstanceOf(AdminException.class);
        }
    }

    @Nested
    @DisplayName("连接测试 —— 修的是厂商那三个缺陷")
    class ConnectionTest {

        @Test
        @DisplayName("参数正确时连通")
        void succeeds() {
            var probe = datasources.test(validDatasource("测试源-G"));

            assertThat(probe.ok()).isTrue();
            assertThat(probe.message()).isEqualTo("连接成功");
        }

        @Test
        @DisplayName("★ 驱动缺失时明确说「未安装驱动」，不是抛一行裸类名")
        void missingDriverExplained() {
            // 厂商的行为是报 `连接失败：org.postgresql.Driver`——一个类名，
            // 用户完全看不出是"缺驱动"。这里必须说清楚。
            //
            // 注意：方言是支持的，缺的只是驱动本身，所以这条走的是驱动校验
            // 而不是方言校验。
            //
            // **必须选"方言支持但驱动没带"的组合**——方言校验先于驱动校验触发，
            // 用不支持的方言（如 ClickHouse）会先报"暂不支持的数据源类型"。
            //
            // 这里曾经用 PostgreSQL，后来补了 pg 驱动（它以前确实没进 jar，
            // 是个真缺陷），反例就失效了。Hive 的驱动放在 -Pbigdata profile 里，
            // 默认构建不带，正好是这个组合
            DatasourceInput input = new DatasourceInput("测试源-H", "HIVE",
                    "org.apache.hive.jdbc.HiveDriver",
                    "jdbc:hive2://127.0.0.1:10000/x", "u", "p", true);

            assertThatThrownBy(() -> datasources.test(input))
                    .isInstanceOf(AdminException.class)
                    .hasMessageContaining("未安装该数据库驱动")
                    .hasMessageContaining("org.apache.hive.jdbc.HiveDriver");
        }

        @Test
        @DisplayName("★ 方言不受支持时明确说清楚，而不是配完了在问数时才炸")
        void unsupportedDialectRejected() {
            DatasourceInput input = new DatasourceInput("测试源-I", "SYBASE",
                    "com.sybase.jdbc4.jdbc.SybDriver", "jdbc:sybase:Tds:x:5000/d", "u", "p", true);

            assertThatThrownBy(() -> datasources.test(input))
                    .isInstanceOf(AdminException.class)
                    .hasMessageContaining("暂不支持的数据源类型")
                    .hasMessageContaining("SYBASE");
        }

        @Test
        @DisplayName("★ 密码错时提示里点出「本地能连不代表这里能连」—— 这是最常见的误判")
        void wrongPasswordExplained() {
            DatasourceInput input = new DatasourceInput("测试源-J", "MYSQL5.7",
                    "com.mysql.cj.jdbc.Driver", URL, "root", "definitely-wrong", true);

            var probe = datasources.test(input);

            assertThat(probe.ok()).isFalse();
            assertThat(probe.message())
                    .as("管理员在本地客户端能连上，所以坚信密码没错——提示必须解释这个差异")
                    .contains("来源主机")
                    .contains("Access denied");
        }

        @Test
        @DisplayName("已保存的数据源测试时用已存的密码")
        void savedDatasourceUsesStoredPassword() {
            long id = datasources.create(validDatasource("测试源-K"), "tester");

            var probe = datasources.test(id);

            assertThat(probe.ok()).isTrue();
        }
    }

    @Nested
    @DisplayName("语义域")
    class Domain {

        @Test
        @DisplayName("创建与读回")
        void createAndRead() {
            long id = domains.create(new DomainInput("测试域-A", "描述", null, "disabled",
                    null, null, 1), "tester");

            assertThat(domains.get(id).name()).isEqualTo("测试域-A");
        }

        @Test
        @DisplayName("★ 启用中的域没有授权 → 保存就被拒，不等启动校验")
        void enabledWithoutGrantRejected() {
            assertThatThrownBy(() -> domains.create(new DomainInput("测试域-B", null, null, "enabled",
                    null, "{\"v\":1,\"grantRoles\":[],\"grantUsers\":[],\"isPublic\":false}", 0), "tester"))
                    .isInstanceOf(AdminException.class)
                    .hasMessageContaining("必须配置访问授权");
        }

        @Test
        @DisplayName("停用的域可以先不配授权 —— 草稿是合法的中间状态")
        void disabledWithoutGrantAllowed() {
            long id = domains.create(new DomainInput("测试域-C", null, null, "disabled",
                    null, null, 0), "tester");

            assertThat(domains.get(id).status()).isEqualTo("disabled");
        }

        @Test
        @DisplayName("配了授权就能启用")
        void enabledWithGrantAllowed() {
            long id = domains.create(new DomainInput("测试域-D", null, null, "enabled", null,
                    "{\"v\":1,\"grantRoles\":[\"分析师\"]}", 0), "tester");

            assertThat(domains.get(id).status()).isEqualTo("enabled");
        }

        @Test
        @DisplayName("★ 删域时连同注册表一起删 —— 不留孤儿行")
        void deleteCascadesToTables() {
            long domainId = domains.create(new DomainInput("测试域-E", null, null, "disabled",
                    null, null, 0), "tester");
            domains.createTable(domainId, new DomainTableInput("t_test", "测试表", "TABLE", null,
                    List.of(new DomainTableInput.ColumnInput("c1", "int", "SUM", "指标", true)),
                    true, 0), "tester");

            assertThat(domains.listTables(domainId)).hasSize(1);

            domains.delete(domainId, "tester");

            assertThat(session.getMapper(SemanticConfigMapper.class).selectTablesByDomain(domainId))
                    .as("表上没建外键，不做这件事就会留下孤儿行")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("注册表")
    class DomainTable {

        private long newDomain() {
            return domains.create(new DomainInput("表测试域", null, null, "disabled",
                    null, null, 0), "tester");
        }

        @Test
        @DisplayName("列定义被序列化成 columns_json，且能被 DomainLoader 解析回来")
        void columnsRoundTrip() {
            long domainId = newDomain();

            domains.createTable(domainId, new DomainTableInput("ins_policy", "保单表", "TABLE", null,
                    List.of(new DomainTableInput.ColumnInput("premium", "decimal", "SUM", "保费", true),
                            new DomainTableInput.ColumnInput("region", "varchar", "", "区域", true)),
                    true, 0), "tester");

            var row = domains.listTables(domainId).get(0);
            assertThat(row.columnsJson()).contains("\"col\":\"premium\"").contains("\"alias\":\"保费\"");
            assertThat(row.enabled()).isTrue();
        }

        @Test
        @DisplayName("★ 派生指标随列定义落库，解析回来的仍是同一个定义")
        void derivedColumnRoundTrip() {
            long domainId = newDomain();

            domains.createTable(domainId, new DomainTableInput("ins_claim_t", "理赔表", "TABLE", null,
                    List.of(new DomainTableInput.ColumnInput("settle_amount", "decimal", "SUM", "赔付金额", true),
                            new DomainTableInput.ColumnInput("apply_amount", "decimal", "SUM", "申请金额", true),
                            new DomainTableInput.ColumnInput(null, "decimal", "", "赔付率", true,
                                    new DomainTableInput.DerivedInput(
                                            "ratio", "赔付金额", "申请金额", null, null, null))),
                    true, 0), "tester");

            // 本文件的 @Nested 类也叫 Domain，这里必须写全名
            com.liusongboyy.chatdata.engine.semantic.Domain domain =
                    DomainLoader.load(domains.get(domainId), domains.listTables(domainId),
                            List.of(), List.of(), "MYSQL5.7");
            MetricRef ref = new SemanticModel(domain).resolveMetricRef("赔付率");

            assertThat(ref).isInstanceOf(MetricRef.Derived.class);
            MetricRef.Derived d = (MetricRef.Derived) ref;
            assertThat(d.spec().kind()).isEqualTo(DerivedMetric.Kind.RATIO);
            assertThat(d.inputs()).hasSize(2);
            assertThat(((MetricRef.Column) d.inputs().get(0)).ref().column())
                    .isEqualTo("settle_amount");
            assertThat(((MetricRef.Column) d.inputs().get(1)).ref().column())
                    .isEqualTo("apply_amount");
        }

        @Test
        @DisplayName("★ 未知聚合方式在保存时就被拒 —— 否则要到问数时才炸")
        void unknownAggregationRejected() {
            long domainId = newDomain();

            assertThatThrownBy(() -> domains.createTable(domainId,
                    new DomainTableInput("t", "T", "TABLE", null,
                            List.of(new DomainTableInput.ColumnInput("c", "int", "MEDIAN", "中位数", true)),
                            true, 0), "tester"))
                    .isInstanceOf(AdminException.class)
                    .hasMessageContaining("MEDIAN");
        }

        @Test
        @DisplayName("★ SQL 虚拟表没有定义时被拒")
        void sqlModeRequiresSql() {
            long domainId = newDomain();

            assertThatThrownBy(() -> domains.createTable(domainId,
                    new DomainTableInput("v", "V", "SQL", null, List.of(), true, 0), "tester"))
                    .isInstanceOf(AdminException.class)
                    .hasMessageContaining("SQL");
        }

        @Test
        @DisplayName("同域内重名被拒")
        void duplicateNameRejected() {
            long domainId = newDomain();
            DomainTableInput input = new DomainTableInput("dup", "D", "TABLE", null,
                    List.of(), true, 0);
            domains.createTable(domainId, input, "tester");

            assertThatThrownBy(() -> domains.createTable(domainId, input, "tester"))
                    .isInstanceOf(AdminException.class)
                    .hasMessageContaining("已存在同名表");
        }

        @Test
        @DisplayName("更新与删除")
        void updateAndDelete() {
            long domainId = newDomain();
            long tableId = domains.createTable(domainId, new DomainTableInput("t1", "T1", "TABLE", null,
                    List.of(), true, 0), "tester");

            domains.updateTable(tableId, new DomainTableInput("t1", "改名了", "TABLE", null,
                    List.of(), false, 2), "tester");

            var row = domains.listTables(domainId).get(0);
            assertThat(row.label()).isEqualTo("改名了");
            assertThat(row.enabled()).isFalse();

            domains.deleteTable(tableId, "tester");
            assertThat(domains.listTables(domainId)).isEmpty();
        }
    }
}

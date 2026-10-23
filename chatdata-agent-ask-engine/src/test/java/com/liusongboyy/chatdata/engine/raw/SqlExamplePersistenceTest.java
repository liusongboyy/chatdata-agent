package com.liusongboyy.chatdata.engine.raw;

import com.liusongboyy.chatdata.engine.admin.mapper.SemanticAdminMapper;
import com.liusongboyy.chatdata.engine.semantic.mapper.SemanticConfigMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import com.liusongboyy.chatdata.engine.admin.AdminException;
import com.liusongboyy.chatdata.engine.admin.AdminSqlExampleService;
import com.liusongboyy.chatdata.engine.admin.SqlExampleInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SQL 范例库的真库读写。
 *
 * <h2>为什么必须有这一条</h2>
 *
 * <p>这里踩过一次：给列起别名写成了 {@code sql_text AS sql}，而 <b>{@code sql} 是 MySQL
 * 保留字</b>——一调到就 {@code SQLSyntaxErrorException}。**所有单测都是绿的**，
 * 因为 {@code @Select} 里的 SQL 只有真库才会执行；是手动调接口才发现的。
 *
 * <p>这类"注解里的 SQL 有语法错"的问题，只有对着真库跑一遍才拦得住。
 * 所以本测试刻意覆盖**每一个查询**（读启用中的、读全部、按 id 读、更新、删除），
 * 而不是只测一条链路。
 *
 * <p>用**独立的 domain_id**（{@code 999001}）而不是真实域：既保证按域隔离的需求能被验证，
 * 又不会污染真实数据；每个用例结束后按该域清理。
 */
class SqlExamplePersistenceTest {

    private static final String URL =
            "jdbc:mysql://127.0.0.1:3306/jimureport?useUnicode=true&characterEncoding=UTF-8"
                    + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    private static final String USER = "root";
    private static final String PASSWORD = "root";

    /** 专供本测试的域 id。真实域不可能用它（自增从 1 开始）。 */
    private static final long TEST_DOMAIN = 999001L;
    private static final long OTHER_DOMAIN = 999002L;

    private static SqlSessionFactory factory;

    @BeforeAll
    static void setUp() {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setURL(URL);
        ds.setUser(USER);
        ds.setPassword(PASSWORD);

        boolean ok;
        try (Connection c = ds.getConnection()) {
            ok = c.isValid(3);
        } catch (Exception e) {
            ok = false;
        }
        Assumptions.assumeTrue(ok, "本地 MySQL 不可用，跳过范例库的真库验证");
        Assumptions.assumeTrue(tableExists(ds), "ask_sql_example 未建（见 sql/14-ask-sql-example.sql），跳过");

        Configuration cfg = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), ds));
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(SemanticConfigMapper.class);
        cfg.addMapper(SemanticAdminMapper.class);
        factory = new SqlSessionFactoryBuilder().build(cfg);
    }

    private static boolean tableExists(MysqlDataSource ds) {
        try (Connection c = ds.getConnection();
             var st = c.createStatement();
             var rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables "
                             + "WHERE table_schema = DATABASE() AND table_name = 'ask_sql_example'")) {
            return rs.next() && rs.getInt(1) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 自增提交，省去每个用例手动 commit；测试用的独立域在结束时清理。 */
    private static SqlSession session() {
        return factory.openSession(true);
    }

    @AfterEach
    void cleanUp() {
        try (SqlSession s = session()) {
            SemanticAdminMapper m = s.getMapper(SemanticAdminMapper.class);
            for (SqlExample e : m.selectAllSqlExamples(TEST_DOMAIN)) {
                m.deleteSqlExample(e.id());
            }
            for (SqlExample e : m.selectAllSqlExamples(OTHER_DOMAIN)) {
                m.deleteSqlExample(e.id());
            }
        }
    }

    private static long insert(SqlSession s, long domainId, String question, boolean enabled) {
        SemanticAdminMapper m = s.getMapper(SemanticAdminMapper.class);
        m.insertSqlExample(domainId, question, "SELECT 1 AS a FROM ins_policy",
                "要点", "分档、档位", "MANUAL", enabled, 0, "test");
        return m.selectAllSqlExamples(domainId).stream()
                .filter(e -> e.question().equals(question))
                .map(SqlExample::id)
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("★ 插入后能读回 —— 这条会抓住「注解里的 SQL 有语法错」")
    void insertThenReadBack() {
        try (SqlSession s = session()) {
            insert(s, TEST_DOMAIN, "各区域的保费合计是多少", true);

            List<SqlExample> enabled =
                    s.getMapper(SemanticConfigMapper.class).selectSqlExamplesByDomain(TEST_DOMAIN);

            assertThat(enabled).singleElement().satisfies(e -> {
                assertThat(e.domainId()).isEqualTo(TEST_DOMAIN);
                assertThat(e.question()).isEqualTo("各区域的保费合计是多少");
                assertThat(e.sql()).isEqualTo("SELECT 1 AS a FROM ins_policy");
                assertThat(e.note()).isEqualTo("要点");
                assertThat(e.keywords()).isEqualTo("分档、档位");
                assertThat(e.enabled()).isTrue();
            });
        }
    }

    @Test
    @DisplayName("按 id 读单条也要能跑 —— 它是更新/删除的前置检查")
    void selectById() {
        try (SqlSession s = session()) {
            long id = insert(s, TEST_DOMAIN, "单条读", true);

            SqlExample one = s.getMapper(SemanticAdminMapper.class).selectSqlExampleById(id);

            assertThat(one).isNotNull();
            assertThat(one.id()).isEqualTo(id);
        }
    }

    @Test
    @DisplayName("★ 停用的不进检索，但管理台仍能看到")
    void disabledIsExcludedFromRetrieval() {
        try (SqlSession s = session()) {
            insert(s, TEST_DOMAIN, "已停用的范例", false);

            assertThat(s.getMapper(SemanticConfigMapper.class)
                    .selectSqlExamplesByDomain(TEST_DOMAIN))
                    .as("停用的不该参与检索")
                    .isEmpty();
            assertThat(s.getMapper(SemanticAdminMapper.class).selectAllSqlExamples(TEST_DOMAIN))
                    .as("但管理台要能看到，否则改不回来")
                    .hasSize(1);
        }
    }

    @Test
    @DisplayName("★ 按域隔离 —— 跨域照抄 SQL 必然查不到表")
    void isolatedByDomain() {
        try (SqlSession s = session()) {
            insert(s, TEST_DOMAIN, "A 域的范例", true);
            insert(s, OTHER_DOMAIN, "B 域的范例", true);

            assertThat(s.getMapper(SemanticConfigMapper.class)
                    .selectSqlExamplesByDomain(TEST_DOMAIN))
                    .extracting(SqlExample::question)
                    .containsExactly("A 域的范例");
        }
    }

    @Test
    @DisplayName("更新生效，且关键词/启用状态都能改")
    void updateTakesEffect() {
        try (SqlSession s = session()) {
            long id = insert(s, TEST_DOMAIN, "原标题", true);

            s.getMapper(SemanticAdminMapper.class).updateSqlExample(id, "新标题",
                    "SELECT 2 AS b FROM ins_policy", "新要点", "新词", false, 5, "test");

            SqlExample after = s.getMapper(SemanticAdminMapper.class).selectSqlExampleById(id);
            assertThat(after.question()).isEqualTo("新标题");
            assertThat(after.sql()).contains("SELECT 2");
            assertThat(after.keywords()).isEqualTo("新词");
            assertThat(after.enabled()).isFalse();
        }
    }

    @Test
    @DisplayName("删除后读不到")
    void deleteRemoves() {
        try (SqlSession s = session()) {
            long id = insert(s, TEST_DOMAIN, "待删除", true);

            s.getMapper(SemanticAdminMapper.class).deleteSqlExample(id);

            assertThat(s.getMapper(SemanticAdminMapper.class).selectSqlExampleById(id)).isNull();
            assertThat(s.getMapper(SemanticAdminMapper.class).selectAllSqlExamples(TEST_DOMAIN))
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("★ 多行中文与长 SQL 都能存 —— TEXT 列 + utf8mb4")
    void handlesChineseAndLongSql() {
        String longSql = "SELECT " + "a,".repeat(500) + "b FROM ins_policy";
        try (SqlSession s = session()) {
            SemanticAdminMapper m = s.getMapper(SemanticAdminMapper.class);
            m.insertSqlExample(TEST_DOMAIN, "中文问法：各区域保费合计", longSql,
                    "要点里也有中文", null, "MANUAL", true, 0, "test");
            long id = m.selectAllSqlExamples(TEST_DOMAIN).get(0).id();

            SqlExample e = m.selectSqlExampleById(id);
            assertThat(e.question()).contains("各区域保费合计");
            assertThat(e.sql()).hasSameSizeAs(longSql);
            assertThat(e.keywords()).as("关键词可为空").isNull();
        }
    }

    @Test
    @DisplayName("★ 同一域内问法重复被拒，且给的是人话而不是 500")
    void duplicateQuestionIsRejected() {
        try (SqlSession s = session()) {
            insert(s, TEST_DOMAIN, "各区域的保费合计是多少", true);

            AdminSqlExampleService service =
                    new AdminSqlExampleService(s.getMapper(SemanticAdminMapper.class));

            assertThatThrownBy(() -> service.create(TEST_DOMAIN,
                    new SqlExampleInput("各区域的保费合计是多少", "SELECT 1", null), "test"))
                    .as("重复问法要么被服务端说清楚，要么就会变成一句「操作失败」")
                    .isInstanceOf(AdminException.class)
                    .hasMessageContaining("已经有范例");
        }
    }

    @Test
    @DisplayName("换个域用同样的问法是允许的 —— 不同域引用不同的表")
    void sameQuestionInAnotherDomainIsAllowed() {
        try (SqlSession s = session()) {
            insert(s, TEST_DOMAIN, "同一个问法", true);

            assertThatCode(() -> insert(s, OTHER_DOMAIN, "同一个问法", true))
                    .as("唯一键的作用域是 (domain_id, question)，不是全局 question")
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("更新成别人已占用的问法也要被说清楚")
    void duplicateOnUpdateIsRejected() {
        try (SqlSession s = session()) {
            insert(s, TEST_DOMAIN, "问法甲", true);
            long other = insert(s, TEST_DOMAIN, "问法乙", true);

            AdminSqlExampleService service =
                    new AdminSqlExampleService(s.getMapper(SemanticAdminMapper.class));

            assertThatThrownBy(() -> service.update(other,
                    new SqlExampleInput("问法甲", "SELECT 1", null), "test"))
                    .isInstanceOf(AdminException.class)
                    .hasMessageContaining("已经被别的范例占用");
        }
    }
}

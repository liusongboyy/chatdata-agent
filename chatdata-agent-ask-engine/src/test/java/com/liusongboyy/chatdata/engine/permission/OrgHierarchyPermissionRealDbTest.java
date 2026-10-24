package com.liusongboyy.chatdata.engine.permission;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 组织树层级行权限：**对真实数据验证**前缀匹配不会越权。
 *
 * <h2>为什么必须在真库上验</h2>
 *
 * <p>谓词长什么样（{@code LIKE ?} 与参数 {@code /1/10/%}）可以单测断言，
 * 但"这条谓词在真实组织树上的结果集对不对"只能靠执行。
 * 层级权限错的后果是**看到不该看的数据**，而且失败形态是静默的——
 * 不报错，只是数字比实际大。这类问题必须在 CI 里挡住。
 *
 * <h2>它自建数据、自己清理</h2>
 *
 * <p>用独立的 schema {@code chatdata_perm_demo}，跑完删掉。
 * 不去碰业务库：层级权限的验证需要一棵形状受控的组织树
 * （尤其要有一对"前缀相似但不同子树"的节点），插到真实库里是污染。
 */
class OrgHierarchyPermissionRealDbTest {

    private static final String SCHEMA = "chatdata_perm_demo_test";
    private static final String URL = "jdbc:mysql://127.0.0.1:3306/" + SCHEMA
            + "?useUnicode=true&characterEncoding=UTF-8&useSSL=false"
            + "&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    private static final String ROOT_URL = "jdbc:mysql://127.0.0.1:3306/"
            + "?useUnicode=true&characterEncoding=UTF-8&useSSL=false"
            + "&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    private static final String USER = "root";
    private static final String PASSWORD = "root";

    private static boolean available;

    @BeforeAll
    static void setUp() {
        try (Connection c = DriverManager.getConnection(ROOT_URL, USER, PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            st.execute("CREATE DATABASE " + SCHEMA + " DEFAULT CHARSET utf8mb4");
        } catch (Exception e) {
            available = false;
            return;
        }
        available = true;
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE org (id BIGINT PRIMARY KEY, name VARCHAR(64), "
                    + "path VARCHAR(200))");
            st.execute("CREATE TABLE store (id BIGINT PRIMARY KEY, name VARCHAR(64), "
                    + "org_id BIGINT)");
            st.execute("CREATE TABLE order_txn (id BIGINT PRIMARY KEY, store_id BIGINT, "
                    + "amount DECIMAL(12,2))");
            //           便利群 /1/
            //           ├─ 华东 /1/10/
            //           │    └─ 部门A /1/10/20/  └─ 组 /1/10/20/30/  └─ 门店1 /1/10/20/30/40/
            //           ├─ 华东X /1/100/          ← 前缀陷阱：'/1/10%' 会误匹配它
            //           │    └─ X门店 /1/100/80/90/
            //           └─ 华北 /1/11/  └─ 北京门店 /1/11/50/60/70/
            st.execute("INSERT INTO org VALUES "
                    + "(1,'便利群','/1/'),"
                    + "(10,'华东','/1/10/'),(20,'部门A','/1/10/20/'),"
                    + "(30,'组','/1/10/20/30/'),(40,'上海门店1','/1/10/20/30/40/'),"
                    + "(100,'华东X','/1/100/'),(90,'X门店','/1/100/80/90/'),"
                    + "(11,'华北','/1/11/'),(70,'北京门店','/1/11/50/60/70/')");
            st.execute("INSERT INTO store VALUES "
                    + "(1,'上海门店1',40),(2,'X门店',90),(3,'北京门店',70)");
            st.execute("INSERT INTO order_txn VALUES "
                    + "(1,1,100.00),(2,1,200.00),(3,2,300.00),(4,3,400.00)");
        } catch (Exception e) {
            available = false;
        }
    }

    @AfterAll
    static void tearDown() {
        try (Connection c = DriverManager.getConnection(ROOT_URL, USER, PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
        } catch (Exception ignored) {
            // 清理失败不影响结论；下次运行会先 DROP
        }
    }

    /** 按给定的组织路径集合查订单，返回 (订单 id, 所属组织路径)。 */
    private static List<Long> orderIdsFor(String op, List<String> paths) throws Exception {
        String json = """
                [{"id":1,"role":"R","field":"org_path","op":"%s","sysVar":"orgPaths","dynamic":true}]"""
                .formatted(op);
        var M = new com.fasterxml.jackson.databind.ObjectMapper();
        var perm = DomainPermission.from(
                M.createObjectNode().set("rowRules", M.readTree(json)));
        var ctx = new PermissionContext("u", java.util.Set.of("R"),
                java.util.Map.of("orgPaths", paths));

        SqlPredicate pred = ((RowScope.Restricted) RowRuleCompiler.compile(perm, ctx)).predicate();
        // 列占位符换成真实列名——这里没有渲染器，直接映射
        pred = pred.mapColumns(col -> "org.path");

        String sql = "SELECT o.id FROM order_txn o JOIN store s ON s.id = o.store_id "
                + "JOIN org ON org.id = s.org_id WHERE " + pred.sql() + " ORDER BY o.id";

        List<Long> ids = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < pred.params().size(); i++) {
                ps.setObject(i + 1, pred.params().get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    @Test
    @DisplayName("★ 层级正确：各级用户只看到自己子树下的单子")
    void eachLevelSeesItsOwnSubtree() throws Exception {
        assumeTrue(available, "需要本机 MySQL");

        assertThat(orderIdsFor("starts_with", List.of("/1/")))
                .as("集团总部看全部").containsExactly(1L, 2L, 3L, 4L);

        assertThat(orderIdsFor("starts_with", List.of("/1/10/")))
                .as("华东只看华东的门店").containsExactly(1L, 2L);

        assertThat(orderIdsFor("starts_with", List.of("/1/10/20/30/40/")))
                .as("门店只看自己").containsExactly(1L, 2L);

        assertThat(orderIdsFor("starts_with", List.of("/1/11/")))
                .as("华北只看华北").containsExactly(4L);
    }

    @Test
    @DisplayName("★ 前缀陷阱：/1/10% 会误伤 /1/100/，带尾斜杠才正确")
    void prefixTrapIsReal() throws Exception {
        assumeTrue(available, "需要本机 MySQL");

        // 这是错误做法的对照：值不带尾斜杠时，华东会看到华东X的单子
        List<Long> wrong = orderIdsFor("starts_with", List.of("/1/10"));
        assertThat(wrong)
                .as("只写到 /1/10 时，'/1/10%' 把 /1/100/ 也匹配进来——这是越权")
                .contains(3L);

        // 正确做法：路径以分隔符结尾
        List<Long> right = orderIdsFor("starts_with", List.of("/1/10/"));
        assertThat(right)
                .as("带尾斜杠后 /1/100/ 不再匹配")
                .doesNotContain(3L);
    }

    @Test
    @DisplayName("★ 兼岗：多个组织取并集，不是交集也不是只取第一个")
    void multipleOrgsUnion() throws Exception {
        assumeTrue(available, "需要本机 MySQL");

        assertThat(orderIdsFor("starts_with", List.of("/1/10/", "/1/11/")))
                .as("华东 + 华北 = 两边的门店，但不含华东X")
                .containsExactly(1L, 2L, 4L);
    }

    @Test
    @DisplayName("★ 精确匹配（店长）不会连带子节点")
    void exactMatchDoesNotIncludeChildren() throws Exception {
        assumeTrue(available, "需要本机 MySQL");

        // 店长用 = 而不是前缀：组织树将来在门店下加子节点时，店长不该跟着看到
        assertThat(orderIdsFor("=", List.of("/1/10/")))
                .as("'=' 只匹配路径完全等于该值的行")
                .isEmpty();

        assertThat(orderIdsFor("=", List.of("/1/10/20/30/40/")))
                .containsExactly(1L, 2L);
    }
}

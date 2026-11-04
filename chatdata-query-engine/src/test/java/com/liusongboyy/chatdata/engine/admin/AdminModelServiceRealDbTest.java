package com.liusongboyy.chatdata.engine.admin;

import com.liusongboyy.chatdata.engine.agent.AskEngineModelProperties;
import com.liusongboyy.chatdata.engine.datasource.PasswordCipher;
import com.liusongboyy.chatdata.engine.model.ModelService;
import com.liusongboyy.chatdata.engine.model.mapper.ModelMapper;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型管理的**真库验证**。
 *
 * <h2>为什么这条必须连真库</h2>
 *
 * <p>这条路里有三样东西是单测验不到的：
 *
 * <ol>
 *   <li><b>INSERT / UPDATE 的列序与占位符个数</b>——手写 SQL 的经典错误，
 *       假 mapper 不解析 SQL，永远发现不了</li>
 *   <li>{@code allowed_roles} 的 JSON 往返——写成 {@code admin} 而不是
 *       {@code ["admin"]}，MySQL 会拒绝（JSON 列），而错误发生在写库那一刻</li>
 *   <li>新加的 {@code selectDomainsByModelKey}——一次都没跑过真实 schema</li>
 * </ol>
 *
 * <h2>⚠️ 会碰真实数据的用例必须先把自己隔离</h2>
 *
 * <p>开发库里已经有真实模型（`default`，全局默认）。大部分用例只操作
 * {@code zz-it-*} 前缀的测试数据，清理时按前缀删，不会伤到它。
 *
 * <p><b>但「设默认」与「停用」这两个动作会波及全表：</b>
 * {@code clearOtherDefaults} 是
 * {@code UPDATE ... WHERE model_key <> ?}，它会把真实模型的默认标记一起摘掉；
 * 而"检查全局只有一个默认"这条断言也依赖表里的真实数据。
 *
 * <p>实测踩到过：跑完一次测试，`default` 的 {@code is_default} 变成了 0，
 * 问数随即失败（"没有默认模型"），而失败原因与测试毫无表面关联。
 *
 * <p>所以这两类用例改成<b>先把这个库里的默认状态存下来、跑完恢复</b>
 * （见 {@link #snapshotDefaults()} / {@link #restoreDefaults}）。
 * 不指望"测完会自己干净"——那种事没发生过。
 */
class AdminModelServiceRealDbTest {

    private static final String URL =
            "jdbc:mysql://127.0.0.1:3306/chatdata_ask?useUnicode=true&characterEncoding=UTF-8"
                    + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    private static final String USER = "root";
    private static final String PASSWORD = "root";

    /** 测试键前缀。清理时按它删——绝不会误删真实模型。 */
    private static final String PREFIX = "zz-it-";

    private static SqlSessionFactory factory;
    private static PasswordCipher cipher;

    @BeforeAll
    static void checkDb() {
        Assumptions.assumeTrue(reachable(), "本地 MySQL 不可用，跳过真库测试");
        var ds = new PooledDataSource("com.mysql.cj.jdbc.Driver", URL, USER, PASSWORD);
        var cfg = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), ds));
        cfg.addMapper(ModelMapper.class);
        cfg.setMapUnderscoreToCamelCase(true);
        factory = new SqlSessionFactoryBuilder().build(cfg);
        cipher = new PasswordCipher("admin-model-realdb-test-secret");
    }

    private static boolean reachable() {
        try (Connection ignored = DriverManager.getConnection(URL, USER, PASSWORD)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 跑之前把"哪些键是默认"记下来。
     *
     * <p>见类注释：{@code clearOtherDefaults} 会波及真实模型，
     * 而它不会被"按前缀清理"救回来。
     */
    private static List<String> snapshotDefaults() {
        List<String> out = new java.util.ArrayList<>();
        try (SqlSession s = factory.openSession(true)) {
            var rs = s.getConnection().createStatement().executeQuery(
                    "SELECT model_key FROM ask_model WHERE is_default = 1");
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    /** 恢复默认标记：先全清，再把快照里的键设回去。 */
    private static void restoreDefaults(List<String> keys) {
        try (SqlSession s = factory.openSession(true)) {
            var conn = s.getConnection();
            conn.createStatement().executeUpdate("UPDATE ask_model SET is_default = 0");
            for (String k : keys) {
                var st = conn.prepareStatement(
                        "UPDATE ask_model SET is_default = 1 WHERE model_key = ?");
                st.setString(1, k);
                st.executeUpdate();
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("恢复默认标记失败——这个问题必须立刻修", e);
        }
    }

    @AfterEach
    void cleanup() {
        try (SqlSession s = factory.openSession(true)) {
            s.getConnection().createStatement().executeUpdate(
                    "DELETE FROM ask_model WHERE model_key LIKE '" + PREFIX + "%'");
        } catch (Exception e) {
            throw new IllegalStateException("清理测试数据失败", e);
        }
    }

    /** 每次新建一套服务——ModelService 有缓存，复用会读到上一次的列表。 */
    private static AdminModelService service() {
        ModelMapper mapper = factory.openSession(true).getMapper(ModelMapper.class);
        return new AdminModelService(new ModelService(mapper, cipher, null), mapper,
                new AskEngineModelProperties());
    }

    private static String key() {
        return PREFIX + UUID.randomUUID().toString().substring(0, 8);
    }

    private static ModelInput input(String key, BigDecimal in, BigDecimal out) {
        return new ModelInput(key, "测试模型 " + key, "真库测试",
                "https://api.example.com/v1", "sk-" + key, "model-x",
                List.of("admin"), in, null, out, false, true);
    }

    @Test
    @DisplayName("★ 新建后能读回来，单价与角色都落库")
    void createRoundTrip() {
        String k = key();
        AdminModelService svc = service();

        svc.create(input(k, new BigDecimal("1.5"), new BigDecimal("6")), "it");

        Map<String, Object> row = svc.list().stream()
                .filter(r -> k.equals(r.get("key"))).findFirst().orElseThrow();
        assertThat(row.get("displayName")).isEqualTo("测试模型 " + k);
        assertThat(row.get("priceInput")).isEqualTo(new BigDecimal("1.500000"));
        assertThat(row.get("priceOutput")).isEqualTo(new BigDecimal("6.000000"));
        assertThat(row.get("priced")).as("输入与输出都配了，应当算「已配单价」").isEqualTo(true);
        assertThat(row.get("hasApiKey")).as("密钥写进去了，但不回显").isEqualTo(true);
        assertThat(row.get("allowedRoles")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly("admin");
    }

    @Test
    @DisplayName("★ allowed_roles 落成合法 JSON——MySQL 的 JSON 列会拒绝别的东西")
    void allowedRolesStoredAsJsonArray() {
        String k = key();
        service().create(input(k, null, null), "it");

        // 直接查库确认是 ["admin"] 而不是 admin —— 后者 MySQL 会直接拒
        try (SqlSession s = factory.openSession(true)) {
            var st = s.getConnection().prepareStatement(
                    "SELECT allowed_roles FROM ask_model WHERE model_key = ?");
            st.setString(1, k);
            var rs = st.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("[\"admin\"]");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("★ 更新只改配置，不改密钥")
    void updateKeepsApiKey() {
        String k = key();
        AdminModelService svc = service();
        svc.create(input(k, null, null), "it");

        // 密钥留 null = 不改。这是管理页"留空"的真实语义
        svc.update(k, new ModelInput(k, "改了名字", null, "https://other/v2", null,
                "model-y", List.of(), null, null, null, false, true));

        Map<String, Object> row = svc.list().stream()
                .filter(r -> k.equals(r.get("key"))).findFirst().orElseThrow();
        assertThat(row.get("displayName")).isEqualTo("改了名字");
        assertThat(row.get("baseUrl")).isEqualTo("https://other/v2");
        assertThat(row.get("hasApiKey")).as("留空不该把密钥清掉").isEqualTo(true);
    }

    @Test
    @DisplayName("★ 全局只能有一个默认——设新的会把旧的摘掉")
    void onlyOneDefault() {
        // **先存后恢复**：这条用例会改写全表的 is_default，包括真实模型的
        List<String> saved = snapshotDefaults();
        String a = key();
        String b = key();
        AdminModelService svc = service();
        svc.create(input(a, null, null), "it");
        svc.create(input(b, null, null), "it");

        svc.update(a, new ModelInput(a, "A", null, "https://x/v1", null, "m",
                List.of(), null, null, null, true, true));
        svc.update(b, new ModelInput(b, "B", null, "https://x/v1", null, "m",
                List.of(), null, null, null, true, true));

        try (SqlSession s = factory.openSession(true)) {
            var st = s.getConnection().prepareStatement(
                    "SELECT COUNT(*) FROM ask_model WHERE is_default = 1");
            var rs = st.executeQuery();
            rs.next();
            assertThat(rs.getInt(1)).as("两个默认会让「用哪个模型」变得不确定").isEqualTo(1);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        } finally {
            restoreDefaults(saved);
        }
    }

    @Test
    @DisplayName("★ 停用摘掉默认标记（真库）")
    void disableClearsDefaultInDb() {
        // 设默认这一步会摘掉真实模型的 —— 同样要先存后恢复
        List<String> saved = snapshotDefaults();
        String k = key();
        AdminModelService svc = service();
        svc.create(new ModelInput(k, "D", null, "https://x/v1", "sk", "m",
                List.of(), null, null, null, true, true), "it");

        svc.setEnabled(k, false);

        Map<String, Object> row = svc.list().stream()
                .filter(r -> k.equals(r.get("key"))).findFirst().orElseThrow();
        assertThat(row.get("enabled")).isEqualTo(false);
        assertThat(row.get("isDefault")).as("停用的模型不能是默认").isEqualTo(false);

        restoreDefaults(saved);
    }

    @Test
    @DisplayName("★ 域绑定查询跑得通（这条 SQL 之前没跑过真实 schema）")
    void boundDomainsQueryWorks() {
        String k = key();
        AdminModelService svc = service();
        svc.create(input(k, null, null), "it");

        // 没有域绑定它 → 空列表，但不该抛异常
        assertThat(svc.boundDomains(k)).isEmpty();
    }

    @Test
    @DisplayName("键重复真被唯一索引挡住——应用层查重之外还有一道")
    void duplicateKeyBlockedByDb() {
        String k = key();
        AdminModelService svc = service();
        svc.create(input(k, null, null), "it");

        // 应用层会先拦，这里直接写库确认 DB 约束也在
        try (SqlSession s = factory.openSession(true)) {
            var mapper = s.getMapper(ModelMapper.class);
            assertThatThrownBy(() -> mapper.insert(k, "重复", null, "https://x/v1",
                    null, "m", "[]", null, null, null, null, false, true, "it"))
                    .as("缺少唯一索引时，成本统计会串")
                    .isInstanceOf(org.apache.ibatis.exceptions.PersistenceException.class);
        }
    }
}

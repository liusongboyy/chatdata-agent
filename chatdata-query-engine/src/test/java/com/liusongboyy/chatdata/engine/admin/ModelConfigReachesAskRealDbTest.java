package com.liusongboyy.chatdata.engine.admin;

import com.liusongboyy.chatdata.engine.agent.AskEngineModelProperties;
import com.liusongboyy.chatdata.engine.datasource.PasswordCipher;
import com.liusongboyy.chatdata.engine.model.ModelAccess;
import com.liusongboyy.chatdata.engine.model.ModelDef;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨功能链路：**管理页配的模型，问数页能不能看到、能不能选**。
 *
 * <h2>为什么这条非写不可</h2>
 *
 * <p>两侧各自的测试都绿：管理面验"写进去了、读得回来"，
 * 问数侧验"给定模型列表能算出可选集合"。但**中间那一环没人测**——
 * 写进去的东西到底是不是问数侧读到的那份。
 *
 * <p>而这一环出错的方式都很安静：缓存没清（写完了问数还读旧的）、
 * 角色白名单存成了 {@code admin} 而不是 {@code ["admin"]}（解析失败，
 * {@code ModelService.parseRoles} 按"谁都不可用"处理）、
 * {@code enabled} 存反了。每一条都不会报错，只是下拉里少了或多了东西。
 */
class ModelConfigReachesAskRealDbTest {

    private static final String URL =
            "jdbc:mysql://127.0.0.1:3306/chatdata_ask?useUnicode=true&characterEncoding=UTF-8"
                    + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    private static final String USER = "root";
    private static final String PASSWORD = "root";
    private static final String PREFIX = "zz-cross-";

    private static SqlSessionFactory factory;
    private static PasswordCipher cipher;

    @BeforeAll
    static void checkDb() {
        Assumptions.assumeTrue(reachable(), "本地 MySQL 不可用，跳过真库测试");
        var ds = new PooledDataSource("com.mysql.cj.jdbc.Driver", URL, USER, PASSWORD);
        var cfg = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        cfg.addMapper(ModelMapper.class);
        cfg.setMapUnderscoreToCamelCase(true);
        factory = new SqlSessionFactoryBuilder().build(cfg);
        cipher = new PasswordCipher("model-cross-test-secret");
    }

    private static boolean reachable() {
        try (Connection ignored = DriverManager.getConnection(URL, USER, PASSWORD)) {
            return true;
        } catch (Exception e) {
            return false;
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

    /** 一个管理面 + 运行期**共用同一个** ModelService 的装配（与生产一致）。 */
    private record Wired(ModelService runtime, AdminModelService admin) {
    }

    private static Wired wired() {
        // **同一个 mapper、同一个 ModelService 实例**：生产里管理面的写与问数的读
        // 走的是同一个 bean，分开造会掩盖"缓存没清"这类问题
        SqlSession session = factory.openSession(true);
        ModelMapper mapper = session.getMapper(ModelMapper.class);
        ModelService runtime = new ModelService(mapper, cipher, null);
        return new Wired(runtime, new AdminModelService(runtime, mapper,
                new AskEngineModelProperties()));
    }

    private static String key() {
        return PREFIX + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("★ 管理页新建的模型，立刻出现在问数下拉的候选里")
    void createdModelIsVisibleToAsk() {
        Wired w = wired();
        String k = key();

        w.admin().create(new ModelInput(k, "新模型", "用途", "https://x/v1", "sk", "m1",
                List.of("admin"), new BigDecimal("1"), null, new BigDecimal("5"),
                false, true), "it");

        // 管理面读完，问数侧**同一个 ModelService** 也要看到——
        // 这一条挡住的是"写完没清缓存"
        List<ModelDef> all = w.runtime().all();
        assertThat(all).extracting(ModelDef::key).contains(k);

        var selectable = ModelAccess.selectable(all, Set.of("admin"), null);
        assertThat(selectable).extracting(ModelDef::key)
                .as("admin 角色应当能选到它").contains(k);

        var forOthers = ModelAccess.selectable(all, Set.of("员工"), null);
        assertThat(forOthers).extracting(ModelDef::key)
                .as("白名单只写了 admin，员工不该看到").doesNotContain(k);
    }

    @Test
    @DisplayName("★ 停用之后问数下拉立刻看不到——不是重启后才生效")
    void disabledModelDisappearsFromAskImmediately() {
        Wired w = wired();
        String k = key();
        w.admin().create(new ModelInput(k, "会被停用", null, "https://x/v1", "sk", "m2",
                List.of(), null, null, null, false, true), "it");
        assertThat(ModelAccess.selectable(w.runtime().all(), Set.of("anyone"), null))
                .extracting(ModelDef::key).contains(k);

        w.admin().setEnabled(k, false);

        assertThat(ModelAccess.selectable(w.runtime().all(), Set.of("anyone"), null))
                .extracting(ModelDef::key)
                .as("停用后还看得到，说明缓存没清").doesNotContain(k);
    }

    @Test
    @DisplayName("★ 角色白名单的 JSON 往返没坏：只给 A 角色，B 角色就选不到")
    void roleWhitelistSurvivesRoundTrip() {
        Wired w = wired();
        String k = key();
        w.admin().create(new ModelInput(k, "受限模型", null, "https://x/v1", "sk", "m3",
                List.of("经理", "admin"), null, null, null, false, true), "it");

        ModelDef def = w.runtime().byKey(k);
        assertThat(def).isNotNull();
        assertThat(def.allowedRoles())
                .as("存成 {\"admin\"} 而不是 [\"admin\"] 时，解析会回退成「谁都不可用」")
                .containsExactlyInAnyOrder("经理", "admin");
        assertThat(def.allowRole("经理")).isTrue();
        assertThat(def.allowRole("员工")).isFalse();
    }

    @Test
    @DisplayName("★ 单价存进去后，运行时算得出钱")
    void pricesSurviveRoundTripAndPrice() {
        Wired w = wired();
        String k = key();
        w.admin().create(new ModelInput(k, "有价", null, "https://x/v1", "sk", "m4",
                List.of(), new BigDecimal("2"), new BigDecimal("0.2"), new BigDecimal("8"),
                false, true), "it");

        ModelDef def = w.runtime().byKey(k);
        assertThat(def.priced()).isTrue();

        // 1000 输入其中 800 命中、200 输出 → 200*2 + 800*0.2 + 200*8 = 2160（微元）
        var cost = com.liusongboyy.chatdata.engine.model.Cost.of(def, 1000, 800, 200);
        assertThat(cost).isNotNull();
        assertThat(cost.amount()).isEqualByComparingTo(new BigDecimal("0.00216"));
    }
}

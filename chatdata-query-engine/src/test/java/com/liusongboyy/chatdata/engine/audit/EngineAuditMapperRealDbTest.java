package com.liusongboyy.chatdata.engine.audit;

import com.liusongboyy.chatdata.engine.audit.mapper.EngineAuditMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.datasource.pooled.PooledDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计落库的**真库验证**。
 *
 * <h2>为什么这条必须连真库</h2>
 *
 * <p>加了六个 token 列与 {@code model_key} 之后，最容易错的不是 Java 代码，
 * 而是 <b>INSERT 的列序与占位符个数</b>。那种错单测永远发现不了——
 * 手写的 fake mapper 不解析 SQL；而真跑起来的表现是"审计写不进去"，
 * 而审计写入失败是<b>被吞掉</b>的（见 {@code DbAskAuditRecorder}），
 * 所以症状只是"成本看板一直是空的"，没人会怀疑到 SQL 上。
 *
 * <h2>NULL 与 0 在此处也验一次</h2>
 *
 * <p>UNKNOWN 必须落成 NULL。若某天有人把它"优化"成 0，
 * 报表会把没采到的调用算成免费——这个断言就是那条防线。
 */
class EngineAuditMapperRealDbTest {

    private static final String URL =
            "jdbc:mysql://127.0.0.1:3306/chatdata_ask?useUnicode=true&characterEncoding=UTF-8"
                    + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    private static final String USER = "root";
    private static final String PASSWORD = "root";

    private static SqlSessionFactory factory;

    @BeforeAll
    static void checkDb() {
        Assumptions.assumeTrue(reachable(), "本地 MySQL 不可用，跳过真库测试");
        var ds = new PooledDataSource("com.mysql.cj.jdbc.Driver", URL, USER, PASSWORD);
        var env = new Environment("test", new JdbcTransactionFactory(), ds);
        var cfg = new org.apache.ibatis.session.Configuration(env);
        cfg.addMapper(EngineAuditMapper.class);
        cfg.setMapUnderscoreToCamelCase(true);
        factory = new SqlSessionFactoryBuilder().build(cfg);
    }

    private static boolean reachable() {
        try (Connection ignored = DriverManager.getConnection(URL, USER, PASSWORD)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static AskAuditRecord withUsage(String username, String modelKey,
                                            Integer in, Integer out, Integer cached) {
        return new AskAuditRecord(username, "分析师", "上个月华东的保费是多少",
                null, "1", "{}", "SELECT 1", 3, 120L,
                AskAuditOutcome.SUCCESS, null, LocalDateTime.now(),
                null, null, null, null,
                modelKey, in, out, cached, null, null);
    }

    /** 写入后按用户名查回来。用用户名过滤是为了不与其他数据互相干扰。 */
    private static AskAuditRecord readBack(String username) {
        try (SqlSession session = factory.openSession()) {
            return session.getConnection() == null ? null : queryByUser(session, username);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static AskAuditRecord queryByUser(SqlSession session, String username) {
        try (var st = session.getConnection().prepareStatement(
                "SELECT model_key, input_tokens, output_tokens, cached_tokens,"
                        + " cache_creation_tokens, reasoning_tokens"
                        + " FROM ask_audit_log WHERE username = ? ORDER BY id DESC LIMIT 1")) {
            st.setString(1, username);
            try (var rs = st.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new AskAuditRecord(username, null, "q", null, null, null, null,
                        null, 0, AskAuditOutcome.SUCCESS, null, LocalDateTime.now(),
                        null, null, null, null,
                        rs.getString("model_key"),
                        (Integer) rs.getObject("input_tokens"),
                        (Integer) rs.getObject("output_tokens"),
                        (Integer) rs.getObject("cached_tokens"),
                        (Integer) rs.getObject("cache_creation_tokens"),
                        (Integer) rs.getObject("reasoning_tokens"));
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("★ INSERT 的列序与占位符对得上，六个计量字段都落库")
    void insertsAllTokenColumns() {
        String user = "it-" + UUID.randomUUID();

        try (SqlSession session = factory.openSession(true)) {
            EngineAuditMapper mapper = session.getMapper(EngineAuditMapper.class);
            int n = mapper.insert(withUsage(user, "flash", 1032, 49, 1024));
            assertThat(n).isEqualTo(1);
        }

        AskAuditRecord back = readBack(user);
        assertThat(back).as("写进去却查不到，说明 UUID 过滤不对").isNotNull();
        assertThat(back.modelKey()).isEqualTo("flash");
        assertThat(back.inputTokens()).isEqualTo(1032);
        assertThat(back.outputTokens()).isEqualTo(49);
        assertThat(back.cachedTokens()).isEqualTo(1024);
    }

    @Test
    @DisplayName("★ 未采集 → 落成 NULL，不是 0")
    void unknownUsageStoredAsNull() {
        String user = "it-" + UUID.randomUUID();

        try (SqlSession session = factory.openSession(true)) {
            // null 六件套 = 这次没采到
            session.getMapper(EngineAuditMapper.class)
                    .insert(withUsage(user, null, null, null, null));
        }

        AskAuditRecord back = readBack(user);
        assertThat(back).isNotNull();
        assertThat(back.inputTokens()).as("0 会让这条记录在成本看板上变成「免费」").isNull();
        assertThat(back.outputTokens()).isNull();
        assertThat(back.modelKey()).isNull();
    }
}

package com.liusongboyy.chatdata.engine.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liusongboyy.chatdata.engine.agent.AskDeniedException;
import com.liusongboyy.chatdata.engine.audit.AskAuditRecorder;
import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import com.liusongboyy.chatdata.engine.raw.RawSchemaBuilder;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Flux;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 直连模式的端到端行为：真库 + 假模型。
 *
 * <h2>为什么要假模型</h2>
 *
 * <p>这里要验的是**编排**：模型不查库时，文字答复能不能原样返回；模型调了
 * {@code run_query} 时，那句 SQL 与那份结果能不能被调用方拿到；模型什么都没给时，
 * 会不会被误报成"处理失败"。这些都与模型答得对不对无关——真模型在这里只会引入
 * 联网、花钱与不确定性。
 *
 * <h2>它挡的是什么</h2>
 *
 * <p>2026-10-20 页面上的那次「处理失败，请稍后重试」就是编排层把一种可解释的失败
 * 兜成了不可解释的失败。直连模式还多一条特有的坑：**能力问题**（「这里能分析哪些
 * 数据」）如果被逼着写 SQL，会得到一条无意义的查询然后失败——用户会以为系统坏了。
 * 这两类出口都必须由测试钉住。
 *
 * <p>不用 Mockito 模拟 JDBC：{@link RawSchemaBuilder} 要读真实的
 * {@code information_schema}，模拟出来的元数据反而验不了"说明书能不能生成"。
 * 本地 MySQL 不可用时整类跳过（与其它 {@code *RealDbTest} 一致）。
 */
class RawAskServiceTest {

    private static final String URL =
            "jdbc:mysql://127.0.0.1:3306/jimureport?useUnicode=true&characterEncoding=UTF-8"
                    + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    private static final String USER = "root";
    private static final String PASSWORD = "root";

    /** 本地库里一定存在的一张表，用它当直连白名单。 */
    private static final String TABLE = "ask_domain";

    private static final ObjectMapper M = new ObjectMapper();

    private static DriverManagerDataSource dataSource;
    private static RawSchemaBuilder schemas;

    @BeforeAll
    static void checkDb() {
        Assumptions.assumeTrue(reachable(), "本地 MySQL 不可用，跳过真库测试");
        dataSource = new DriverManagerDataSource(URL, USER, PASSWORD);
        schemas = new RawSchemaBuilder(id -> dataSource);
    }

    @AfterAll
    static void clearCache() {
        if (schemas != null) {
            schemas.invalidate();
        }
    }

    private static boolean reachable() {
        try (Connection ignored = DriverManager.getConnection(URL, USER, PASSWORD)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── 夹具 ──

    private static Domain rawDomain() {
        RegisteredTable table = new RegisteredTable(
                "1", TABLE, "语义域", RegisteredTable.Mode.TABLE, null, true, List.of());
        return new Domain("1372", "直连测试域", "1", "MYSQL5.7", true,
                List.of(table), List.of(), permOf("""
                {"v":1,"isPublic":false,"grantRoles":["分析师"],
                 "unrestrictedRoles":["分析师"]}"""),
                List.of(), List.of(), Domain.Mode.RAW);
    }

    private static DomainPermission permOf(String json) {
        try {
            return DomainPermission.from(M.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException("夹具 JSON 写错", e);
        }
    }

    private static RawAskService service(Model model) {
        return new RawAskService(rawDomain(), model, id -> dataSource, schemas,
                AskAuditRecorder.NOOP, List.of());
    }

    private static AskRequest asAnalyst(String question) {
        return new AskRequest(question, "u1", "u1", Set.of("分析师"), "raw-test", null);
    }

    private static ChatResponse text(String id, String value) {
        return ChatResponse.builder()
                .id(id)
                .content(List.of(TextBlock.builder().text(value).build()))
                .usage(new ChatUsage(1, 1, 0.0))
                .finishReason("stop")
                .build();
    }

    // ── 假模型 ──

    /** 从不调用工具，只给文字——"能力问题"的正确形态。 */
    private static class TextOnlyModel implements Model {
        @Override
        public Flux<ChatResponse> stream(List<Msg> msgs, List<ToolSchema> tools,
                                         GenerateOptions options) {
            return Flux.just(text("c1", "可以分析：语义域配置（ask_domain）。"));
        }

        @Override
        public String getModelName() {
            return "text-only";
        }
    }

    /** 第 1 轮发起 run_query，第 2 轮基于结果作答——数据问题的正确形态。 */
    private static final class ToolCallingModel implements Model {
        static final String SQL = "SELECT id, name FROM ask_domain ORDER BY id LIMIT 3";

        private final AtomicInteger round = new AtomicInteger();

        @Override
        public Flux<ChatResponse> stream(List<Msg> msgs, List<ToolSchema> tools,
                                         GenerateOptions options) {
            if (round.incrementAndGet() == 1) {
                return Flux.just(ChatResponse.builder()
                        .id("c1")
                        .content(List.of(ToolUseBlock.builder()
                                .id("call_1")
                                .name("run_query")
                                .input(Map.of("sql", SQL))
                                // agentscope 校验的是流式累积的**原始 content**，
                                // 不是解析后的 input。只填 input 会被判成"缺少参数"
                                .content("{\"sql\":\"" + SQL + "\"}")
                                .build()))
                        .usage(new ChatUsage(1, 1, 0.0))
                        .finishReason("tool_calls")
                        .build());
            }
            return Flux.just(text("c2", "共有 3 个语义域。"));
        }

        @Override
        public String getModelName() {
            return "tool-calling";
        }
    }

    /** 什么都没给：没有工具调用，也没有文字。 */
    private static final class SilentModel implements Model {
        @Override
        public Flux<ChatResponse> stream(List<Msg> msgs, List<ToolSchema> tools,
                                         GenerateOptions options) {
            return Flux.just(ChatResponse.builder()
                    .id("c1")
                    .content(List.of())
                    .usage(new ChatUsage(1, 1, 0.0))
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "silent";
        }
    }

    // ── 用例 ──

    @Test
    @DisplayName("★ 能力问题：不查库、把模型的文字原样返回，而不是逼它写 SQL")
    void capabilityQuestionReturnsTextAnswer() {
        AskResult result = service(new TextOnlyModel())
                .askStreaming(asAnalyst("整体看下这个域能分析的数据指标有哪些"), e -> {
                });

        assertThat(result.answer()).contains("ask_domain");
        assertThat(result.isTextOnly()).isTrue();
        assertThat(result.sqlText()).isNull();
        assertThat(result.result().rowCount()).isZero();
        assertThat(result.mode()).isEqualTo(Domain.Mode.RAW);
    }

    @Test
    @DisplayName("数据问题：模型写的 SQL 与实际结果一并通过 AskResult 返回")
    void dataQuestionReturnsModelSqlAndRows() {
        AskResult result = service(new ToolCallingModel())
                .askStreaming(asAnalyst("有哪些语义域"), e -> {
                });

        assertThat(result.modelSql()).isEqualTo(ToolCallingModel.SQL);
        assertThat(result.result().columns()).containsExactly("id", "name");
        assertThat(result.result().rowCount()).isEqualTo(3);
        assertThat(result.answer()).isNull();
        assertThat(result.mode()).isEqualTo(Domain.Mode.RAW);
    }

    @Test
    @DisplayName("模型什么都没给 → 「没听懂」，不是「处理失败」")
    void silentModelBecomesNotUnderstood() {
        assertThatThrownBy(() -> service(new SilentModel())
                .askStreaming(asAnalyst("随便看看"), e -> {
                }))
                .isInstanceOf(AskService.NotUnderstoodException.class)
                .hasMessageContaining("换一种说法");
    }

    @Test
    @DisplayName("无域权限时不消耗模型调用")
    void deniedUserNeverCallsModel() {
        AtomicInteger calls = new AtomicInteger();
        Model counting = new TextOnlyModel() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> msgs, List<ToolSchema> tools,
                                             GenerateOptions options) {
                calls.incrementAndGet();
                return super.stream(msgs, tools, options);
            }
        };

        AskRequest stranger = new AskRequest("有哪些数据", "u2", "u2",
                Set.of("路人"), "raw-test", null);

        assertThatThrownBy(() -> service(counting).askStreaming(stranger, e -> {
        }))
                .isInstanceOf(AskDeniedException.class);
        assertThat(calls).hasValue(0);
    }
}

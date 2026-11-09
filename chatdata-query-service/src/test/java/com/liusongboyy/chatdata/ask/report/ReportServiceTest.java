package com.liusongboyy.chatdata.ask.report;

import com.liusongboyy.chatdata.ask.conversation.ConversationRow;
import com.liusongboyy.chatdata.ask.conversation.MessageRow;
import com.liusongboyy.chatdata.ask.conversation.mapper.ConversationMapper;
import com.liusongboyy.chatdata.ask.report.mapper.ReportMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报告的生成与存取。
 *
 * <h2>这里验的是「接线」不是「合并逻辑」</h2>
 *
 * <p>合并逻辑在 {@code ReportBuilderTest} 里测透了（纯函数）。
 * 这个类只关心三件事：
 *
 * <ol>
 *   <li><b>越权隔离</b>——别人的会话、别人的报告一律 404</li>
 *   <li><b>取数正确</b>——ASSISTANT 消息要配上它前面那条 USER 的问题</li>
 *   <li><b>坏数据跳过</b>——一条解析不了不该让整份报告失败</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    private ReportMapper reports;
    @Mock
    private ConversationMapper conversations;

    private ReportService service;

    private static final String CONV = "conv-1";
    private static final String ME = "zhangsan";

    @BeforeEach
    void setUp() {
        service = new ReportService(reports, conversations);
    }

    private static ConversationRow conv(String owner) {
        return new ConversationRow(CONV, owner, null, "标题", null, 2,
                LocalDateTime.now(), LocalDateTime.now());
    }

    /** 一条成功的结果 JSON。 */
    private static final String OK_JSON = """
            {"columns":["区域","保费"],"rows":[["华东",1000]],
             "rowCount":1,
             "understanding":{"metrics":["保费"],"dimensions":["区域"],"time":null,"filters":[]},
             "chart":{"form":"BAR","categoryField":"区域","valueFields":["保费"],"reason":"x"}}""";

    private static MessageRow user(long id, int seq, String q) {
        return new MessageRow(id, CONV, seq, "USER", "question", q, null,
                "SUCCESS", null, null, LocalDateTime.now());
    }

    private static MessageRow assistant(long id, int seq, String json, String status) {
        return new MessageRow(id, CONV, seq, "ASSISTANT", "question",
                "查询返回 1 行", json, status, null, null, LocalDateTime.now());
    }

    @Nested
    @DisplayName("越权隔离")
    class Ownership {

        @Test
        @DisplayName("★ 别人的会话 → 404，不泄露存在性")
        void otherUsersConversationIs404() {
            when(conversations.selectById(CONV)).thenReturn(conv("lisi"));

            assertThatThrownBy(() -> service.candidates(CONV, ME))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("404");

            // **不能继续查消息**：那会读到别人的数据
            verify(conversations, never()).selectMessages(anyString());
        }

        @Test
        @DisplayName("★ 别人的报告 → 404")
        void otherUsersReportIs404() {
            when(reports.selectById("r1")).thenReturn(new ReportRow(
                    "r1", CONV, "lisi", "标题", "意图", "{}", "", null, LocalDateTime.now()));

            assertThatThrownBy(() -> service.get("r1", ME))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("404");
        }

        @Test
        @DisplayName("★ 删别人的报告 → 404（软删除的 SQL 带 username）")
        void cannotDeleteOthersReport() {
            // mapper 的 update 返回 0 表示"没有匹配的行"，可能是别人的也可能不存在——
            // 两种都回 404，不区分
            when(reports.softDelete("r1", ME)).thenReturn(0);

            assertThatThrownBy(() -> service.delete("r1", ME))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("404");
        }
    }

    @Nested
    @DisplayName("取数")
    class Fetch {

        @Test
        @DisplayName("★ ASSISTANT 消息配上它前面那条 USER 的问题")
        void pairsQuestionWithAssistant() {
            when(conversations.selectById(CONV)).thenReturn(conv(ME));
            when(conversations.selectMessages(CONV)).thenReturn(List.of(
                    user(1, 1, "按客户等级看保费"),
                    assistant(2, 2, OK_JSON, "SUCCESS")));

            var candidates = service.candidates(CONV, ME);

            assertThat(candidates).singleElement().satisfies(c -> {
                assertThat(c.question()).isEqualTo("按客户等级看保费");
                assertThat(c.selectable()).isTrue();
                assertThat(c.hasAnalysis()).as("单查询没有 analysis").isFalse();
            });
        }

        @Test
        @DisplayName("★ 失败的消息不可勾选")
        void failedMessageNotSelectable() {
            when(conversations.selectById(CONV)).thenReturn(conv(ME));
            when(conversations.selectMessages(CONV)).thenReturn(List.of(
                    user(1, 1, "你好"),
                    assistant(2, 2, null, "FAILED")));

            var candidates = service.candidates(CONV, ME);

            assertThat(candidates).singleElement()
                    .satisfies(c -> assertThat(c.selectable()).isFalse());
        }

        @Test
        @DisplayName("生成时跳过失败与不可解析的消息")
        void skipsFailedAndUnparseable() {
            when(conversations.selectById(CONV)).thenReturn(conv(ME));
            when(conversations.selectMessages(CONV)).thenReturn(List.of(
                    user(1, 1, "问题一"),
                    assistant(2, 2, OK_JSON, "SUCCESS"),
                    user(3, 3, "你好"),
                    assistant(4, 4, null, "FAILED"),
                    user(5, 5, "问题三"),
                    assistant(6, 6, "{坏 json", "SUCCESS")));

            var report = service.create(CONV, ME, List.of());

            // 只有 msg 2 可用
            assertThat(report.sourceMessageIds()).containsExactly("2");
        }

        @Test
        @DisplayName("★ 选中的消息里没有可用数据 → 400，不生成空报告")
        void noUsableSourceIsError() {
            when(conversations.selectById(CONV)).thenReturn(conv(ME));
            when(conversations.selectMessages(CONV)).thenReturn(List.of(
                    user(1, 1, "你好"),
                    assistant(2, 2, null, "FAILED")));

            assertThatThrownBy(() -> service.create(CONV, ME, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("没有可用");
        }

        @Test
        @DisplayName("只勾选指定的消息")
        void respectsSelectedIds() {
            when(conversations.selectById(CONV)).thenReturn(conv(ME));
            when(conversations.selectMessages(CONV)).thenReturn(List.of(
                    user(1, 1, "问题一"),
                    assistant(2, 2, OK_JSON, "SUCCESS"),
                    user(3, 3, "问题二"),
                    assistant(4, 4, OK_JSON, "SUCCESS")));

            var report = service.create(CONV, ME, List.of("4"));

            assertThat(report.sourceMessageIds()).containsExactly("4");
        }
    }

    @Nested
    @DisplayName("落库")
    class Persist {

        @Test
        @DisplayName("★ 生成后写库，正文是 JSON")
        void persistsBody() {
            when(conversations.selectById(CONV)).thenReturn(conv(ME));
            when(conversations.selectMessages(CONV)).thenReturn(List.of(
                    user(1, 1, "各区域保费"),
                    assistant(2, 2, OK_JSON, "SUCCESS")));

            service.create(CONV, ME, List.of());

            ArgumentCaptor<ReportRow> cap = ArgumentCaptor.forClass(ReportRow.class);
            verify(reports).insert(cap.capture());

            ReportRow row = cap.getValue();
            assertThat(row.username()).isEqualTo(ME);
            assertThat(row.conversationId()).isEqualTo(CONV);
            assertThat(row.bodyJson()).contains("\"sections\"");
            assertThat(row.sourceMessageIds()).isEqualTo("2");
            assertThat(row.modelKey()).as("阶段 1 还没接模型").isNull();
        }

        @Test
        @DisplayName("★ 写库失败 → 明确报错，不假装成功")
        void persistFailureIsLoud() {
            when(conversations.selectById(CONV)).thenReturn(conv(ME));
            when(conversations.selectMessages(CONV)).thenReturn(List.of(
                    user(1, 1, "各区域保费"),
                    assistant(2, 2, OK_JSON, "SUCCESS")));
            when(reports.insert(any())).thenThrow(new RuntimeException("表不存在"));

            assertThatThrownBy(() -> service.create(CONV, ME, List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("保存失败");
        }
    }

    @Nested
    @DisplayName("列表")
    class Listing {

        @Test
        @DisplayName("列表不带正文——带上会让响应大几十倍")
        void listOmitsBody() {
            when(reports.selectByUser(anyString(), anyInt(), anyInt())).thenReturn(List.of(
                    new ReportRow("r1", CONV, ME, "标题", "意图",
                            "{\"很大的正文\":true}", "1,2,3", null, LocalDateTime.now())));
            when(reports.countByUser(ME)).thenReturn(1);

            var page = service.list(ME, 1, 20);

            assertThat(page.items()).singleElement().satisfies(s -> {
                assertThat(s.title()).isEqualTo("标题");
                assertThat(s.sourceCount()).isEqualTo(3);
            });
        }
    }
}

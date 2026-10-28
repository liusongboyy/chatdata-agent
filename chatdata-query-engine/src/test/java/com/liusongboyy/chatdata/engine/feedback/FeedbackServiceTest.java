package com.liusongboyy.chatdata.engine.feedback;

import com.liusongboyy.chatdata.engine.admin.AdminSqlExampleService;
import com.liusongboyy.chatdata.engine.admin.SqlExampleInput;
import com.liusongboyy.chatdata.engine.feedback.mapper.FeedbackMapper;
import com.liusongboyy.chatdata.engine.semantic.DomainRow;
import com.liusongboyy.chatdata.engine.semantic.DomainTableRow;
import com.liusongboyy.chatdata.engine.semantic.mapper.SemanticConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 反馈闭环的**状态机与闸门**。
 *
 * <p>这些用例挡的是最危险的一种错误：待审反馈被当成范例直接喂给模型。
 * 所以重点不是"能不能提交"，而是"什么条件下才会写进范例库"。
 */
class FeedbackServiceTest {

    private FeedbackMapper mapper;
    private SemanticConfigMapper configMapper;
    private AdminSqlExampleService examples;
    private FeedbackService service;

    @BeforeEach
    void setUp() {
        mapper = mock(FeedbackMapper.class);
        configMapper = mock(SemanticConfigMapper.class);
        examples = mock(AdminSqlExampleService.class);
        service = new FeedbackService(mapper, configMapper, examples);
        when(configMapper.selectDomainById(1372L)).thenReturn(domainRow());
    }

    private static DomainRow domainRow() {
        return new DomainRow(1372L, "智能工单域", 17L, "enabled", "RAW", null, "{}");
    }

    private static DomainTableRow table(String name) {
        return new DomainTableRow(1L, 1372L, name, name, "TABLE", null, "[]", true);
    }

    private static FeedbackRow pending(String type, String correctedSql) {
        return new FeedbackRow(1L, 1372L, null, "u1", "工单总数是多少",
                type, "SELECT COUNT(*) FROM ticket", correctedSql, null,
                "PENDING", null, null, null, LocalDateTime.now());
    }

    private static FeedbackInput correction(String sql) {
        return new FeedbackInput("1372", null, "工单总数是多少",
                "SELECT COUNT(*) FROM ticket", sql, null, FeedbackType.SQL_CORRECTION);
    }

    // ── 提交 ──

    @Test
    @DisplayName("提交只落 PENDING，绝不碰范例库")
    void submitOnlyInsertsPending() {
        doAnswer(inv -> {
            ((Map<String, Object>) inv.getArgument(0)).put("id", 7L);
            return 1;
        }).when(mapper).insertFeedback(any());

        long id = service.submit(correction("SELECT COUNT(*) FROM ticket WHERE deleted = 0"), "u1");

        assertThat(id).isEqualTo(7L);
        verify(mapper).insertFeedback(any());
        // 提交阶段没有任何写范例的路径
        verify(examples, never()).createFromFeedback(anyLong(), any(), any());
    }

    @Test
    @DisplayName("提交修正 SQL 时先挡只读——DELETE 在入口就被拒")
    void submitRejectsNonReadOnlySql() {
        assertThatThrownBy(() -> service.submit(correction("DELETE FROM ticket"), "u1"))
                .isInstanceOf(FeedbackException.class)
                .hasMessageContaining("不合规");
        verify(mapper, never()).insertFeedback(any());
    }

    @Test
    @DisplayName("域不存在直接拒绝")
    void submitRejectsUnknownDomain() {
        when(configMapper.selectDomainById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.submit(
                new FeedbackInput("999", null, "问题", null, null, null, FeedbackType.WRONG_ANSWER), "u1"))
                .isInstanceOf(FeedbackException.class)
                .hasMessageContaining("数据域不存在");
    }

    // ── 审核 ──

    @Test
    @DisplayName("★ 通过修正反馈：写入范例，并把 example_id 记回反馈")
    void approveCorrectionWritesExample() {
        when(mapper.selectById(1L)).thenReturn(pending("SQL_CORRECTION",
                "SELECT COUNT(*) FROM ticket WHERE deleted = 0"));
        when(configMapper.selectTablesByDomain(1372L)).thenReturn(List.of(table("ticket")));
        when(examples.createFromFeedback(eq(1372L), any(SqlExampleInput.class), eq("reviewer")))
                .thenReturn(42L);
        when(mapper.markReviewed(1L, "APPROVED", 42L, "reviewer")).thenReturn(1);

        Long exampleId = service.approve(1L, "reviewer");

        assertThat(exampleId).isEqualTo(42L);
        verify(examples).createFromFeedback(eq(1372L), any(SqlExampleInput.class), eq("reviewer"));
        verify(mapper).markReviewed(1L, "APPROVED", 42L, "reviewer");
    }

    @Test
    @DisplayName("修正 SQL 引用了域外表 → 拒绝，不写范例")
    void approveRejectsTableOutsideDomain() {
        when(mapper.selectById(1L)).thenReturn(pending("SQL_CORRECTION",
                "SELECT COUNT(*) FROM sys_user"));
        when(configMapper.selectTablesByDomain(1372L)).thenReturn(List.of(table("ticket")));

        assertThatThrownBy(() -> service.approve(1L, "reviewer"))
                .isInstanceOf(FeedbackException.class)
                .hasMessageContaining("超出该域");

        verify(examples, never()).createFromFeedback(anyLong(), any(), any());
        verify(mapper, never()).markReviewed(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("只点「答错了」没有修正 SQL：审核通过但不写范例")
    void approveWrongAnswerDoesNotWriteExample() {
        when(mapper.selectById(1L)).thenReturn(pending("WRONG_ANSWER", null));
        when(mapper.markReviewed(1L, "APPROVED", null, "reviewer")).thenReturn(1);

        Long exampleId = service.approve(1L, "reviewer");

        assertThat(exampleId).isNull();
        verify(examples, never()).createFromFeedback(anyLong(), any(), any());
        verify(mapper).markReviewed(1L, "APPROVED", null, "reviewer");
    }

    @Test
    @DisplayName("已处理过的反馈不能再审——不能静默覆盖上一个人的结论")
    void cannotReviewTwice() {
        FeedbackRow done = new FeedbackRow(1L, 1372L, null, "u1", "q", "WRONG_ANSWER",
                null, null, null, "APPROVED", null, "other", LocalDateTime.now(), LocalDateTime.now());
        when(mapper.selectById(1L)).thenReturn(done);

        assertThatThrownBy(() -> service.approve(1L, "reviewer"))
                .isInstanceOf(FeedbackException.class)
                .hasMessageContaining("已处理");
        verify(mapper, never()).markReviewed(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("并发审核：带条件的 update 影响 0 行时要报错，而不是当成功")
    void concurrentReviewDetected() {
        when(mapper.selectById(1L)).thenReturn(pending("WRONG_ANSWER", null));
        when(mapper.markReviewed(1L, "APPROVED", null, "reviewer")).thenReturn(0);

        assertThatThrownBy(() -> service.approve(1L, "reviewer"))
                .isInstanceOf(FeedbackException.class)
                .hasMessageContaining("已被他人处理");
    }

    @Test
    @DisplayName("拒绝：状态置 REJECTED，不写范例")
    void rejectDoesNotWriteExample() {
        when(mapper.selectById(1L)).thenReturn(pending("SQL_CORRECTION",
                "SELECT COUNT(*) FROM ticket"));
        when(mapper.markReviewed(eq(1L), eq("REJECTED"), isNull(), eq("reviewer"))).thenReturn(1);

        service.reject(1L, "reviewer");

        verify(examples, never()).createFromFeedback(anyLong(), any(), any());
    }

    @Test
    @DisplayName("列表：状态校验交给枚举，非法状态是 400 级错误")
    void listRejectsUnknownStatus() {
        assertThatThrownBy(() -> service.list("NOT_A_STATUS", 10))
                .isInstanceOf(FeedbackException.class)
                .hasMessageContaining("未知的状态");
    }
}

package com.liusongboyy.chatdata.engine.admin;

import com.liusongboyy.chatdata.engine.admin.mapper.SemanticAdminMapper;
import com.liusongboyy.chatdata.engine.raw.SqlExample;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 反馈审核写入范例的**冲突策略**。
 *
 * <p>核心约束：人工确认过的范例不能被一条用户反馈静默覆盖。
 */
class AdminSqlExampleFeedbackTest {

    private SemanticAdminMapper mapper;
    private AdminSqlExampleService service;
    private final SqlExampleInput input = new SqlExampleInput(
            "工单总数是多少", "SELECT COUNT(*) FROM ticket WHERE deleted = 0", "排除已删除");

    @BeforeEach
    void setUp() {
        mapper = mock(SemanticAdminMapper.class);
        service = new AdminSqlExampleService(mapper);
    }

    @Test
    @DisplayName("没有同问法 → 以 FEEDBACK 来源新增并直接启用")
    void insertsNewFeedbackExample() {
        SqlExample inserted = new SqlExample(9L, 1372L, "工单总数是多少",
                "SELECT COUNT(*) FROM ticket WHERE deleted = 0", "排除已删除", null, "FEEDBACK", true);
        when(mapper.selectSqlExampleByQuestion(1372L, "工单总数是多少"))
                .thenReturn(null, inserted);
        when(mapper.insertSqlExample(eq(1372L), anyString(), anyString(), anyString(),
                eq(null), eq("FEEDBACK"), eq(true), anyInt(), eq("reviewer"))).thenReturn(1);

        long id = service.createFromFeedback(1372L, input, "reviewer");

        assertThat(id).isEqualTo(9L);
        verify(mapper).insertSqlExample(eq(1372L), anyString(), anyString(), anyString(),
                eq(null), eq("FEEDBACK"), eq(true), anyInt(), eq("reviewer"));
    }

    @Test
    @DisplayName("★ 同问法已有手工范例 → 拒绝覆盖，让人工口径保持唯一")
    void refusesToOverwriteManualExample() {
        when(mapper.selectSqlExampleByQuestion(1372L, "工单总数是多少"))
                .thenReturn(new SqlExample(3L, 1372L, "工单总数是多少",
                        "SELECT COUNT(*) FROM ticket", "手工口径", null, "MANUAL", true));

        assertThatThrownBy(() -> service.createFromFeedback(1372L, input, "reviewer"))
                .isInstanceOf(AdminException.class)
                .hasMessageContaining("手工范例");

        verify(mapper, never()).insertSqlExample(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyBoolean(), anyInt(), anyString());
        verify(mapper, never()).updateSqlExample(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyBoolean(), anyInt(), anyString());
    }

    @Test
    @DisplayName("同问法已有反馈范例 → 更新它，而不是插第二条")
    void updatesPreviousFeedbackExample() {
        when(mapper.selectSqlExampleByQuestion(1372L, "工单总数是多少"))
                .thenReturn(new SqlExample(5L, 1372L, "工单总数是多少",
                        "SELECT 1", "旧口径", null, "FEEDBACK", true));

        long id = service.createFromFeedback(1372L, input, "reviewer");

        assertThat(id).isEqualTo(5L);
        verify(mapper).updateSqlExample(eq(5L), anyString(), anyString(), anyString(),
                eq(null), eq(true), anyInt(), eq("reviewer"));
        verify(mapper, never()).insertSqlExample(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyBoolean(), anyInt(), anyString());
    }
}

package com.liusongboyy.chatdata.ask.policy;

import com.liusongboyy.chatdata.ask.policy.mapper.PolicyAuditMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicyAuditRecorderTest {

    @Test
    @DisplayName("摘要与缩进/换行无关：内容没变不会记成一次变更")
    void digestIgnoresWhitespace() {
        String compact = "{\"grantRoles\":[\"analyst\"]}";
        String pretty = "{\n  \"grantRoles\": [\"analyst\"]\n}";
        assertThat(PolicyAuditRecorder.digest(compact))
                .isEqualTo(PolicyAuditRecorder.digest(pretty));
        assertThat(PolicyAuditRecorder.digest(null))
                .isEqualTo(PolicyAuditRecorder.digest(""));
        assertThat(PolicyAuditRecorder.digest("{\"v\":\"华东 一区\"}"))
                .isNotEqualTo(PolicyAuditRecorder.digest("{\"v\":\"华东一区\"}"));
    }

    @Test
    @DisplayName("审计写失败只记日志，不把异常抛给保存流程")
    void auditFailureDoesNotBreakSave() {
        PolicyAuditMapper mapper = mock(PolicyAuditMapper.class);
        when(mapper.insert(anyString(), anyLong(), any(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> new PolicyAuditRecorder(mapper)
                .recordUpdate("DOMAIN", 1L, "销售", "admin", "{}"))
                .doesNotThrowAnyException();
        verify(mapper).insert(anyString(), anyLong(), any(), anyString(), anyString(), anyString(), any());
    }
}

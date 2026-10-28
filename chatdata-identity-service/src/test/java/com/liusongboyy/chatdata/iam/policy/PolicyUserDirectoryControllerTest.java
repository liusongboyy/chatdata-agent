package com.liusongboyy.chatdata.iam.policy;

import com.liusongboyy.chatdata.iam.config.IamProperties;
import com.liusongboyy.chatdata.iam.user.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicyUserDirectoryControllerTest {

    private final UserMapper users = mock(UserMapper.class);
    private final PolicyUserDirectoryController controller =
            new PolicyUserDirectoryController(users, new IamProperties());

    @Test
    @DisplayName("带 usernames 时走逐字符批量查询，不做模糊匹配")
    void batchUsesExactLookup() {
        when(users.selectByUsernames(anyString(), any()))
                .thenReturn(List.of(new UserMapper.UserOptionRow("zhangsan", "张三", "ACTIVE")));

        Map<String, Object> body = controller.users("x", 20, List.of("zhangsan", "zhangsan", " "));

        verify(users).selectByUsernames("t_default", List.of("zhangsan"));
        verify(users, never()).selectOptions(anyString(), any(), anyInt());
        assertThat((List<?>) body.get("items")).hasSize(1);
    }

    @Test
    @DisplayName("usernames 全是空白时退回关键词搜索，不拼出 IN ()")
    void blankUsernamesFallBackToSearch() {
        when(users.selectOptions(eq("t_default"), any(), anyInt())).thenReturn(List.of());

        controller.users("zhang", 20, List.of("", " "));

        verify(users).selectOptions("t_default", "%zhang%", 20);
        verify(users, never()).selectByUsernames(anyString(), any());
    }
}

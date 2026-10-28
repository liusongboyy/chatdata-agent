package com.liusongboyy.chatdata.iam.admin;

import com.liusongboyy.chatdata.common.core.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

class AdminExceptionHandlerTest {

    private final AdminExceptionHandler handler = new AdminExceptionHandler();

    /**
     * 这条守的是"链接写错 ≠ 服务挂了"。
     *
     * <p>本 advice 是全局的，兜底处理器会把任何异常变成 500。曾经在 IAM 的域名下
     * 点 {@code /app/ask}（那是 ask 的路径）得到 500 "操作失败，请稍后重试"，
     * 而真实原因只是"这个路径不归我管"。
     */
    @Test
    @DisplayName("静态资源不存在 → 404，不被兜底处理器吞成 500")
    void missingResourceMapsTo404() {
        ResponseEntity<Result<Void>> response = handler.handleNotFound(
                new NoResourceFoundException(HttpMethod.GET, "/app/ask", "app/ask"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("未预期异常仍然是 500 + 通用提示，不泄露细节")
    void unexpectedErrorStays500() {
        ResponseEntity<Result<Void>> response = handler.handleOther(new RuntimeException("表名不该出现"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).doesNotContain("表名");
    }
}

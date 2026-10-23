package com.liusongboyy.chatdata.iam.admin;

import com.liusongboyy.chatdata.common.core.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 管理面的异常映射。
 *
 * <p>{@link IamAdminException} 是写给管理员看的输入/状态问题 → 400 并透出消息；
 * 其余异常 → 500 + 通用提示，细节只进日志（不把表名、SQL、堆栈漏给前端）。
 */
@RestControllerAdvice
public class AdminExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminExceptionHandler.class);

    @ExceptionHandler(IamAdminException.class)
    public ResponseEntity<Result<Void>> handleAdminError(IamAdminException e) {
        return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
    }

    /**
     * 权限不足 → <strong>403</strong>。
     *
     * <p>这条不能少。{@code AuthorizationDeniedException} 是
     * {@code AccessDeniedException} 的子类，而它又是 {@code RuntimeException}——
     * 没有这个处理器时会被下面的 {@link #handleOther(Exception)} 吃掉，
     * 变成 **500 "操作失败，请稍后重试"**。用户看到的是"系统坏了"，
     * 而真实原因是"你没有这个权限"，两者该做的下一步完全不同。
     *
     * <p>同时把用户、路径、方法记进日志：排查"他为什么点不了"时需要这些。
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException e,
            jakarta.servlet.http.HttpServletRequest request) {
        String user = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication() == null
                ? "anonymous"
                : org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication().getName();
        log.warn("权限不足：user={} {} {} —— {}", user, request.getMethod(),
                request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                .body(Result.error(403, "您没有该操作权限"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Result<Void>> handleIllegalState(IllegalStateException e) {
        // 这类是"服务端状态不对"（如主键没回填），不是用户输入问题
        log.error("IAM 管理面状态异常", e);
        return ResponseEntity.internalServerError()
                .body(Result.error(500, "服务端状态异常，请联系管理员"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        log.error("IAM 管理面请求失败", e);
        return ResponseEntity.internalServerError()
                .body(Result.error(500, "操作失败，请稍后重试"));
    }
}

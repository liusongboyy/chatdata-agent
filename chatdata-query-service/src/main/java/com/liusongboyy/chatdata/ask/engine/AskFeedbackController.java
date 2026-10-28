package com.liusongboyy.chatdata.ask.engine;

import com.liusongboyy.chatdata.ask.identity.CurrentUser;
import com.liusongboyy.chatdata.engine.feedback.FeedbackException;
import com.liusongboyy.chatdata.engine.feedback.FeedbackInput;
import com.liusongboyy.chatdata.engine.feedback.FeedbackService;
import com.liusongboyy.chatdata.engine.feedback.FeedbackType;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户对某条问数结果的反馈。
 *
 * <h2>它只负责收，不负责判</h2>
 *
 * <p>提交后一律是 {@code PENDING}。是否把用户改的 SQL 变成范例，
 * 由管理台的角色在 {@code /api/engine/admin/feedback/{id}/approve} 决定——
 * 见 {@link FeedbackService} 的说明。
 *
 * <h2>鉴权</h2>
 *
 * <p>{@code /api/ask/**} 在 Spring Security 层放行（见 {@code AskSecurityConfig}），
 * 与问数端点一样由本控制器解析令牌。**反馈必须登录**：匿名提交无法追溯，
 * 也挡不住批量灌入待审队列。
 */
@RestController
@RequestMapping("/api/ask")
public class AskFeedbackController {

    private static final Logger log = LoggerFactory.getLogger(AskFeedbackController.class);

    private final FeedbackService feedbackService;
    private final CurrentUser currentUser;
    /** 功能权限判定（含回滚开关）。见 {@code PermissionChecker}。 */
    private final com.liusongboyy.chatdata.ask.config.PermissionChecker perm;

    public AskFeedbackController(FeedbackService feedbackService, CurrentUser currentUser,
                                 com.liusongboyy.chatdata.ask.config.PermissionChecker perm) {
        this.feedbackService = feedbackService;
        this.currentUser = currentUser;
        this.perm = perm;
    }

    /**
     * 请求体。字段名与前端 {@code submitFeedback} 一一对应。
     *
     * <p>刻意不用 Jackson 注解或枚举类型：Spring MVC 用 Jackson 3，而引擎里的
     * 反馈模型是纯 JDK 类型，跨 Jackson 版本不会出问题（问数端点的重算也出于同样的考虑）。
     */
    public record FeedbackBody(
            String domainId,
            String assistantId,
            String question,
            String generatedSql,
            String correctedSql,
            String note,
            String type
    ) {
    }

    @PostMapping("/feedback")
    @org.springframework.security.access.prepost.PreAuthorize("@perm.has('ask.ui.feedback')")
    public ResponseEntity<?> submit(@RequestBody(required = false) FeedbackBody body,
                                    HttpServletRequest request) {
        String username = currentUser.username().orElse(null);
        if (username == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "UNAUTHENTICATED", "message", "登录已失效，请重新登录后再反馈"));
        }
        if (body == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "BAD_REQUEST", "message", "请求体不能为空"));
        }

        try {
            FeedbackType type = FeedbackType.parse(body.type());
            // 提交「修正 SQL」比"点个有帮助"强得多：审核通过后它会进范例库，
            // 成为之后相似问题的 few-shot。所以单独一个码，不能靠反馈码蒙混过关
            if (type == FeedbackType.SQL_CORRECTION
                    && !perm.has("ask.ui.feedback.correct")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("code", "FORBIDDEN",
                                "message", "您没有提交修正 SQL 的权限，可以直接标记「答得不对」"));
            }

            long id = feedbackService.submit(new FeedbackInput(
                    body.domainId(), body.assistantId(), body.question(),
                    body.generatedSql(), body.correctedSql(), body.note(),
                    type), username);
            return ResponseEntity.ok(Map.of("id", id));
        } catch (FeedbackException e) {
            // 这些是写给用户看的输入问题，直接透出
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "BAD_REQUEST", "message", e.getMessage()));
        } catch (Exception e) {
            // 不把内部细节（SQL、表名、堆栈）透给前端
            log.error("提交反馈失败：user={}", username, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", "FEEDBACK_FAILED", "message", "反馈提交失败，请稍后重试"));
        }
    }
}

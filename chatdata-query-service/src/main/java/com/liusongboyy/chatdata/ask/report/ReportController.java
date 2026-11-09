package com.liusongboyy.chatdata.ask.report;

import com.liusongboyy.chatdata.ask.identity.CurrentUser;
import com.liusongboyy.chatdata.engine.report.Report;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 分析报告接口。
 *
 * <h2>为什么挂在 {@code /api/ask/reports}</h2>
 *
 * <p>它是**产品数据**，与问数入口（{@code /api/ask/engine}）同一层级，
 * 而不是管理面。判据只有"登录了，且这是你的"——报告是个人产物。
 *
 * <h2>不属于自己的一律 404</h2>
 *
 * <p>不是 403。403 会告诉对方"这个 id 存在，只是你不能看"——
 * 那是一条可以枚举的信息。报告 id 是随机串，泄露存在性没有直接危害，
 * 但"不泄露存在性"是一条该保持一致的原则。
 */
@RestController
@RequestMapping("/api/ask/reports")
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final ReportService service;
    private final CurrentUser currentUser;

    public ReportController(ReportService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    /**
     * 可勾选的轮次。
     *
     * <p>生成报告前，前端用它渲染勾选框。返回每轮的问题、能不能勾、
     * 有没有分析——前端据此标注「分析」/「数据」。
     */
    @GetMapping("/candidates/{conversationId}")
    public ResponseEntity<?> candidates(@PathVariable String conversationId) {
        String username = currentUser.username().orElse(null);
        if (username == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            return ResponseEntity.ok(service.candidates(conversationId, username));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of(
                    "code", "NOT_FOUND", "message", "会话不存在"));
        }
    }

    /**
     * 生成报告。
     *
     * <p><b>同步</b>：可能花几秒等模型写标题。不做异步任务——
     * 一份报告等 3 秒可以接受，而异步要多一套状态机与轮询。
     *
     * @param conversationId 会话 id
     * @param body           {@code {"messageIds": ["1","2"]}}；**空表示全选成功的那些**
     */
    @PostMapping("/conversations/{conversationId}")
    public ResponseEntity<?> create(@PathVariable String conversationId,
                                    @RequestBody(required = false) CreateBody body) {
        String username = currentUser.username().orElse(null);
        if (username == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            List<String> ids = body == null ? List.of() : body.messageIds();
            Report report = service.create(conversationId, username, ids);
            return ResponseEntity.ok(report);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of(
                    "code", "NOT_FOUND", "message", "会话不存在"));
        } catch (IllegalArgumentException e) {
            // "选中的问答里没有可用的数据" —— 输入问题，400 而不是 500
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "NO_USABLE_SOURCE", "message", e.getMessage()));
        } catch (RuntimeException e) {
            log.error("生成报告失败：conv={}", conversationId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", "REPORT_FAILED", "message", "报告生成失败，请稍后重试"));
        }
    }

    /** 读一份报告。 */
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        String username = currentUser.username().orElse(null);
        if (username == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            return ResponseEntity.ok(service.get(id, username));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of(
                    "code", "NOT_FOUND", "message", "报告不存在"));
        } catch (IllegalStateException e) {
            // 正文坏了（旧格式）。**明确说**，不返回空报告
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "code", "REPORT_UNREADABLE", "message", e.getMessage()));
        }
    }

    /** 我的报告列表。 */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        String username = currentUser.username().orElse(null);
        if (username == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(service.list(username, page, size));
    }

    /** 删除（软删除）。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        String username = currentUser.username().orElse(null);
        if (username == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            service.delete(id, username);
            return ResponseEntity.noContent().build();
        } catch (org.springframework.web.server.ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of(
                    "code", "NOT_FOUND", "message", "报告不存在"));
        }
    }

    /** 生成请求体。 */
    public record CreateBody(List<String> messageIds) {
    }
}

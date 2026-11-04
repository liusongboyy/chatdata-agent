package com.liusongboyy.chatdata.ask.health;

import com.liusongboyy.chatdata.common.core.result.Result;
import com.liusongboyy.chatdata.ask.identity.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康度概览。
 *
 * <h2>为什么挂在 {@code /api/engine/admin} 下</h2>
 *
 * <p>它读审计日志（运行数据）和域配置（管理数据），两者都是管理视角。
 * 放在 {@code /api/ask/**} 下的话，普通问数用户也能拿到
 * "哪些域成功率低"——那不该是他们的信息。
 *
 * <h2>权限与域管理同档</h2>
 *
 * <p>看健康度的人就是配域的人，不需要单独一个权限码。
 * 多一个码就多一次"谁能看"的讨论，而收益为零。
 */
@RestController
@RequestMapping("/api/engine/admin/health")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final HealthService health;
    private final CurrentUser currentUser;

    public HealthController(HealthService health, CurrentUser currentUser) {
        this.health = health;
        this.currentUser = currentUser;
    }

    /**
     * 一页健康度。
     *
     * @param days 时间范围，默认 7 天。**上限 90**——审计表只留 30 天
     *             （见 AskAuditMapper.deleteCreatedBefore），要更长就得改保留策略
     */
    @GetMapping("/overview")
    public ResponseEntity<Result<HealthService.Overview>> overview(
            @RequestParam(defaultValue = "7") int days,
            HttpServletRequest request) {
        if (currentUser.username().isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Result.error(401, "未登录"));
        }
        try {
            return ResponseEntity.ok(Result.success(health.overview(days)));
        } catch (RuntimeException e) {
            // 审计表读不到（库不可用）不该让管理台整页白屏——
            // 它是"看一眼状态"的页面，不是关键路径
            log.error("读取健康度失败：{}", e.getMessage(), e);
            return ResponseEntity.ok(Result.success(null));
        }
    }
}

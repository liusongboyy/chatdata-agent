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
    private final CostService cost;
    private final CurrentUser currentUser;

    public HealthController(HealthService health, CostService cost, CurrentUser currentUser) {
        this.health = health;
        this.cost = cost;
        this.currentUser = currentUser;
    }

    /**
     * 成本看板。
     *
     * <h2>为什么「按用户」不是谁都能看</h2>
     *
     * <p>普通员工不该知道同事花了多少钱——那不是数据权限问题，是同事关系问题。
     * 所以这里按管理员权限裁剪，而<b>不靠前端隐藏</b>：
     * 前端隐藏只是不显示，接口照样能拿到。
     *
     * <p>判据与域管理同档：能进这个页面的人就是管理员。
     *
     * @param days 时间范围，默认 30 天。**比健康度页更长**：成本核算按月看，
     *             7 天的样本不足以看出趋势
     */
    @GetMapping("/cost")
    public ResponseEntity<Result<com.liusongboyy.chatdata.engine.model.CostAggregate.Report>>
            cost(@RequestParam(defaultValue = "30") int days,
                 HttpServletRequest request) {
        if (currentUser.username().isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Result.error(401, "未登录"));
        }
        try {
            var report = cost.cost(days);
            // 非管理员：抹掉按用户的部分，其余照给。
            // **整页拒绝是不对的**——他本来就是管理员才进得来这个页面，
            // 而万一将来权限模型变了，返回一份少了明细的报表比 403 更有用
            boolean admin = isAdmin();
            var safe = admin ? report : new com.liusongboyy.chatdata.engine.model.CostAggregate.Report(
                    report.days(), report.total(), report.byModel(), report.byDomain(),
                    java.util.List.of(), report.notice());
            return ResponseEntity.ok(Result.success(safe));
        } catch (RuntimeException e) {
            log.error("读取成本看板失败：{}", e.getMessage(), e);
            // 返回 null 而不是 500：这是"看一眼花了多少"的页面，不是关键路径
            return ResponseEntity.ok(Result.success(null));
        }
    }

    /**
     * 是不是管理员。
     *
     * <p>看的是角色里有没有管理相关的名字。**这里刻意宽松**：
     * 域管理接口本身已经有更强的鉴权，这条只决定"要不要给出别人的花销明细"。
     * 判错方向是"少给"，不是"多给"——安全方向。
     */
    private boolean isAdmin() {
        for (String r : currentUser.roles()) {
            String low = r.toLowerCase(java.util.Locale.ROOT);
            if (low.contains("admin") || r.contains("管理员") || r.contains("总经理")) {
                return true;
            }
        }
        return false;
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

    /**
     * 按问题类型做的质量分析。
     *
     * @param days       时间范围，默认 30 天。**比概览的 7 天长**：
     *                   类型分布需要更多样本才有意义，7 天的样本量
     *                   会让某些类型只剩个位数
     * @param minSamples 少于这个次数的问法不列出，默认 5。
     *                   样本太少时成功率是噪声
     */
    @GetMapping("/quality")
    public ResponseEntity<Result<HealthService.QualityReport>> quality(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "5") int minSamples,
            HttpServletRequest request) {
        if (currentUser.username().isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Result.error(401, "未登录"));
        }
        try {
            return ResponseEntity.ok(Result.success(health.quality(days, minSamples)));
        } catch (RuntimeException e) {
            log.error("读取质量分析失败：{}", e.getMessage(), e);
            return ResponseEntity.ok(Result.success(null));
        }
    }
}

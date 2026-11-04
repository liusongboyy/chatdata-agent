package com.liusongboyy.chatdata.ask.health;

import com.liusongboyy.chatdata.ask.identity.CurrentUser;
import com.liusongboyy.chatdata.engine.model.CostAggregate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 成本端点的契约：状态码与「按用户」的裁剪。
 *
 * <h2>这里守的是什么</h2>
 *
 * <p>成本看板的<b>金额口径</b>由 {@code CostServiceTest} 覆盖，这里只守两件
 * 与 HTTP 有关的事：
 *
 * <ol>
 *   <li>未登录 401（不能让匿名请求看到全公司的花销）</li>
 *   <li>非管理员拿到的报表里 {@code byUser} 是空的</li>
 * </ol>
 *
 * <p>第二条尤其要测：<b>前端隐藏不算数</b>——接口照样会把数据发出去。
 * 越权过滤必须在服务端，而"以为前端藏了就安全"是这个项目里已经吃过一次的亏。
 */
@ExtendWith(MockitoExtension.class)
class HealthControllerCostTest {

    @Mock
    private HealthService health;
    @Mock
    private CostService cost;

    private HealthController controller;

    private void login(String... roles) {
        var authorities = java.util.Arrays.stream(roles)
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u1", "n/a", authorities));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static CostAggregate.Report report() {
        var total = new CostAggregate.Totals(10, 10, new BigDecimal("1.5"),
                new BigDecimal("0.15"), 1000, 800, 200, 80.0);
        var row = new CostAggregate.Row("fast", "快速", 10, 10, new BigDecimal("1.5"),
                1000, 800, 200, 80.0, null);
        var user = new CostAggregate.UserRow("zhangsan", 10, 10, new BigDecimal("1.5"), "fast");
        return new CostAggregate.Report(30, total, List.of(row), List.of(row), List.of(user), null);
    }

    @Test
    @DisplayName("★ 未登录 → 401")
    void unauthenticatedIs401() {
        controller = new HealthController(health, cost, new CurrentUser());

        var response = controller.cost(30, mock(jakarta.servlet.http.HttpServletRequest.class));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("★ 非管理员：字段都在，但「按用户」为空")
    void nonAdminLosesPerUserBreakdown() {
        login("员工");
        controller = new HealthController(health, cost, new CurrentUser());
        when(cost.cost(30)).thenReturn(report());

        var body = controller.cost(30, mock(jakarta.servlet.http.HttpServletRequest.class))
                .getBody().getData();

        assertThat(body.byUser()).as("别人花多少钱不该让普通员工看到").isEmpty();
        // 其余部分照给——整页拒绝没有意义，他本来就进得了这个页面
        assertThat(body.total().amount()).isEqualByComparingTo("1.5");
        assertThat(body.byModel()).hasSize(1);
    }

    @Test
    @DisplayName("★ 管理员：看到「按用户」")
    void adminSeesPerUserBreakdown() {
        login("admin");
        controller = new HealthController(health, cost, new CurrentUser());
        when(cost.cost(30)).thenReturn(report());

        var body = controller.cost(30, mock(jakarta.servlet.http.HttpServletRequest.class))
                .getBody().getData();

        assertThat(body.byUser()).singleElement()
                .satisfies(u -> assertThat(u.username()).isEqualTo("zhangsan"));
    }

    @Test
    @DisplayName("聚合抛异常 → 200 + null，不让管理台整页白屏")
    void failureReturnsNullNot500() {
        login("admin");
        controller = new HealthController(health, cost, new CurrentUser());
        when(cost.cost(30)).thenThrow(new IllegalStateException("库挂了"));

        var response = controller.cost(30, mock(jakarta.servlet.http.HttpServletRequest.class));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().getData()).isNull();
    }
}

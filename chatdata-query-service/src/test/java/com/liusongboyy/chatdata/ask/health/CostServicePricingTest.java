package com.liusongboyy.chatdata.ask.health;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.liusongboyy.chatdata.ask.audit.AskAuditLog;
import com.liusongboyy.chatdata.ask.audit.mapper.AskAuditMapper;
import com.liusongboyy.chatdata.engine.model.ModelDef;
import com.liusongboyy.chatdata.engine.model.ModelService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 成本页在**有真实数据**时的表现。
 *
 * <h2>为什么要单独一组</h2>
 *
 * <p>{@code CostServiceTest} 用的是手工构造的小样本，验的是分类口径。
 * 这里补的是另一类问题：**页面打开时到底有没有东西可看**。
 *
 * <p>一个页面"接口通、数据空、全是破折号"在技术上不算 bug，
 * 但对管理员来说与"页面坏了"没有区别。所以这里刻意造一批
 * 有真实量级的数据，断言它渲染出来是有内容的。
 */
class CostServicePricingTest {

    private static AskAuditLog log(String model, Integer in, Integer out, Integer cached) {
        AskAuditLog l = new AskAuditLog();
        l.setModelKey(model);
        l.setDomainId("1");
        l.setUsername("admin");
        l.setOutcome("SUCCESS");
        l.setInputTokens(in);
        l.setOutputTokens(out);
        l.setCachedTokens(cached);
        return l;
    }

    private static ModelService models(ModelDef... defs) {
        return ModelService.forTest(List.of(defs), null);
    }

    private static CostService service(List<AskAuditLog> rows, ModelService ms) {
        AskAuditMapper mapper = mock(AskAuditMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>(rows));
        return new CostService(mapper, ms, null);
    }

    @Test
    @DisplayName("★ 有数据时总额、均价、缓存率都不是 null")
    void populatedReportHasNumbers() {
        var def = new ModelDef("fast", "快速", null, "u", "m", List.of(),
                new BigDecimal("0.5"), new BigDecimal("0.05"), new BigDecimal("2"),
                null, true, true);
        // 三条：每条 10000 输入（其中 8000 命中）、2000 输出
        var rows = List.of(log("fast", 10000, 2000, 8000),
                log("fast", 10000, 2000, 8000),
                log("fast", 10000, 2000, 8000));

        var report = service(rows, models(def)).cost(30);

        assertThat(report.total().asks()).isEqualTo(3);
        assertThat(report.total().amount()).as("配了单价就必须算得出钱").isNotNull();
        assertThat(report.total().amount()).isPositive();
        assertThat(report.total().avgPerAsk()).isNotNull();
        assertThat(report.total().cacheHitRate()).isEqualTo(80.0);
        // 输入 30000 其中 24000 命中
        assertThat(report.total().inputTokens()).isEqualTo(30000);
        assertThat(report.total().cachedTokens()).isEqualTo(24000);
    }

    @Test
    @DisplayName("★ 单批 0.0xxx 元不能被舍成 0——那看起来像免费")
    void smallAmountsAreNotRoundedToZero() {
        // 1000 输入、0 输出，单价 0.5 元/MTok → 0.0005 元
        var def = new ModelDef("cheap", "便宜", null, "u", "m", List.of(),
                new BigDecimal("0.5"), null, new BigDecimal("2"), null, false, true);

        var report = service(List.of(log("cheap", 1000, 0, 0)), models(def)).cost(30);

        assertThat(report.total().amount())
                .as("按 2 位舍入会得到 0.00，而 0 元意思是免费")
                .isEqualByComparingTo(new BigDecimal("0.0005"));
    }

    @Test
    @DisplayName("★ 按域的标签不是裸 id")
    void domainRowsHaveLabels() {
        var def = new ModelDef("fast", "快速", null, "u", "m", List.of(),
                new BigDecimal("1"), null, new BigDecimal("1"), null, false, true);

        var report = service(List.of(log("fast", 100, 10, 0)), models(def)).cost(30);

        // domainService 传 null 时读不到名字，会退回显示 id——**不能是空串**，
        // 空串在表格里是一行看不出来源的空白
        assertThat(report.byDomain()).singleElement()
                .satisfies(r -> assertThat(r.label()).isNotBlank());
    }
}

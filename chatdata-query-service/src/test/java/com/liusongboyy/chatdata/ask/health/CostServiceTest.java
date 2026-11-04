package com.liusongboyy.chatdata.ask.health;

import com.liusongboyy.chatdata.ask.audit.AskAuditLog;
import com.liusongboyy.chatdata.ask.audit.mapper.AskAuditMapper;
import com.liusongboyy.chatdata.engine.model.ModelDef;
import com.liusongboyy.chatdata.engine.model.ModelService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
 * 成本聚合的口径。
 *
 * <h2>这里最该守住的是什么</h2>
 *
 * <p>不是"算得对不对"——那由 {@code CostTest} 覆盖。这里守的是<b>分类</b>：
 * 「没配单价」「没采到 token」「零提问」三者都必须显示成"—"，
 * 任何一个被折成 0 都会让管理员得出"成本很低"的结论，而那个结论是假的。
 *
 * <p>另一个容易搞错的是<b>单次均价的分母</b>：必须除以"能算出钱的"次数，
 * 而不是总提问数。后者会被未计价的记录拉低，看起来像成本在下降。
 */
class CostServiceTest {

    private static AskAuditLog log(String modelKey, String domainId, String user,
                                   Integer in, Integer out, Integer cached) {
        AskAuditLog l = new AskAuditLog();
        l.setModelKey(modelKey);
        l.setDomainId(domainId);
        l.setUsername(user);
        l.setOutcome("SUCCESS");
        l.setInputTokens(in);
        l.setOutputTokens(out);
        l.setCachedTokens(cached);
        return l;
    }

    private static ModelDef model(String key, String input, String cached, String output) {
        return new ModelDef(key, "显示名-" + key, null, "u", "m", List.of(),
                dec(input), dec(cached), dec(output), null, false, true);
    }

    private static BigDecimal dec(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    private static CostService service(List<AskAuditLog> rows, List<ModelDef> models) {
        AskAuditMapper mapper = mock(AskAuditMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>(rows));
        ModelService modelService = ModelService.forTest(models, null);
        // domainService 传 null：CostService 里读域清单失败会退回显示 id，
        // 那正是这条路由该走的降级分支——不需要为此造一个假的 AdminDomainService
        return new CostService(mapper, modelService, null);
    }

    @Test
    @DisplayName("★ 正常计价：cached 是 input 的子集，金额按未缓存部分全价算")
    void pricesCachedSeparately() {
        // 1000 输入其中 800 命中，200 输出。单价 2 / 0.2 / 8 元每百万
        var svc = service(
                List.of(log("fast", "1", "u1", 1000, 200, 800)),
                List.of(model("fast", "2", "0.2", "8")));

        var report = svc.cost(30);

        assertThat(report.total().asks()).isEqualTo(1);
        assertThat(report.total().pricedAsks()).isEqualTo(1);
        // 200*2/1e6 + 800*0.2/1e6 + 200*8/1e6 = 0.0004 + 0.00016 + 0.0016
        assertThat(report.total().amount()).isEqualByComparingTo(new BigDecimal("0.0022"));
        assertThat(report.total().cacheHitRate()).isEqualTo(80.0);
    }

    @Test
    @DisplayName("★ 没配单价 → 金额 null，但 token 照常统计")
    void unpricedModelKeepsTokenCounts() {
        var svc = service(
                List.of(log("free", "1", "u1", 500, 100, 0)),
                List.of(model("free", null, null, null)));

        var row = svc.cost(30).byModel().get(0);

        assertThat(row.amount()).as("0 元是「免费」，而这里该说「不知道」").isNull();
        assertThat(row.unpriced()).isTrue();
        assertThat(row.inputTokens()).isEqualTo(500);
    }

    @Test
    @DisplayName("★ 没采到 token → 也算不出钱，且不计入 pricedAsks")
    void missingTokensNotCountedAsFree() {
        var svc = service(
                List.of(log("fast", "1", "u1", null, null, null)),
                List.of(model("fast", "2", "0.2", "8")));

        var row = svc.cost(30).byModel().get(0);

        assertThat(row.amount()).isNull();
        assertThat(row.pricedAsks()).as("这条算不进均价的分母").isZero();
        assertThat(row.asks()).isEqualTo(1);
        assertThat(row.partial()).as("有提问但没计价——前端要标「覆盖 0/1 次」").isTrue();
    }

    @Test
    @DisplayName("★ 配了没人用的模型照样列出，金额为 null 且不算 partial")
    void unusedModelStillListed() {
        var svc = service(List.of(), List.of(model("fast", "2", "0.2", "8")));

        var rows = svc.cost(30).byModel();

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.key()).isEqualTo("fast");
            assertThat(r.asks()).isZero();
            assertThat(r.amount()).isNull();
            // 零提问不算 "部分未计价"——那是"还没用过"，不是"算不出来"
            assertThat(r.partial()).isFalse();
        });
    }

    @Test
    @DisplayName("★ 单次均价的分母是「能算出钱的次数」，不是总提问数")
    void averageUsesPricedDenominator() {
        var svc = service(
                List.of(
                        log("fast", "1", "u1", 1000, 0, 0),
                        // 这一条没采到 token：它会让"总额/总提问数"算出个更小的数
                        log("fast", "1", "u2", null, null, null)),
                List.of(model("fast", "1", null, "1")));

        var total = svc.cost(30).total();

        assertThat(total.asks()).isEqualTo(2);
        assertThat(total.pricedAsks()).isEqualTo(1);
        // 1000 × 1元/MTok = 0.001；除以 1 次（不是 2 次）
        assertThat(total.avgPerAsk()).isEqualByComparingTo(new BigDecimal("0.001"));
    }

    @Test
    @DisplayName("模型键缺失（老数据）归到「未记录」，不混进任何真实模型")
    void missingModelKeyIsOwnBucket() {
        var svc = service(
                List.of(log(null, "1", "u1", 100, 10, 0)),
                List.of(model("fast", "2", "0.2", "8")));

        var rows = svc.cost(30).byModel();

        // 真实模型 fast 零提问仍然列出；老数据单独一行
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r.key())
                .containsExactlyInAnyOrder("fast", "（未记录）");
    }
}

package com.liusongboyy.chatdata.channel.render;

import com.liusongboyy.chatdata.channel.ask.AskResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 卡片渲染。
 *
 * <p>这里不能断言"飞书会接受"——那只有真实发送才知道（{@code note} 组件就是这么
 * 翻的车：schema 2.0 废弃了它，单测全绿、真机 400）。能守住的是<b>内容正确</b>：
 * 该翻页时给翻页按钮、不该给时不给，行数对不对。
 */
class FeishuCardRendererTest {

    private final FeishuCardRenderer renderer = new FeishuCardRenderer();

    private static AskResult withRows(int total) {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(total, 1000); i++) {
            rows.add(List.of("门店" + i, "¥" + (i * 100)));
        }
        return new AskResult(List.of("门店", "金额"), rows, total, "SELECT 1",
                List.of(), "conv-1", 100, "2300", null, null);
    }

    @Test
    @DisplayName("★ 结果超过一页 → 只放第一页 + 下一页按钮")
    void paginatesWhenTooManyRows() {
        String card = renderer.renderResult("问题", withRows(47), 1L, 0);

        assertThat(card).contains("门店0").contains("门店9");
        // 第 10 行属于第二页，不该出现在第一页里
        assertThat(card).doesNotContain("门店10");
        assertThat(card).contains("下一页").doesNotContain("上一页");
        assertThat(card).contains("第 1/5 页");
    }

    @Test
    @DisplayName("★ 最后一页没有下一页（点了没反应的按钮比没有更困惑）")
    void lastPageHasNoNext() {
        String card = renderer.renderResult("问题", withRows(47), 1L, 4);

        assertThat(card).contains("上一页").doesNotContain("下一页");
        assertThat(card).contains("第 5/5 页");
    }

    @Test
    @DisplayName("一页放得下 → 不出现翻页按钮")
    void singlePageHasNoPager() {
        String card = renderer.renderResult("问题", withRows(5), 1L, 0);

        assertThat(card).doesNotContain("下一页").doesNotContain("上一页");
    }

    @Test
    @DisplayName("★ 没存下答案（answerId=null）时不翻页也不反馈")
    void noButtonsWithoutAnswerId() {
        String card = renderer.renderResult("问题", withRows(47), null, 0);

        // 没有 answerId 就没法关联上下文，按了也是错的
        assertThat(card).doesNotContain("下一页")
                .doesNotContain("有用")
                .doesNotContain("答错了");
    }

    @Test
    @DisplayName("有 answerId 时给出反馈按钮")
    void feedbackButtonsWhenStored() {
        String card = renderer.renderResult("问题", withRows(5), 1L, 0);

        assertThat(card).contains("有用").contains("答错了");
    }

    @Test
    @DisplayName("★ 单值结果用大字，不塞表格")
    void singleValueUsesHeading() {
        AskResult r = new AskResult(List.of("订单金额"), List.of(List.of("¥16,200")),
                1, "SELECT 1", List.of(), null, 0, "2300", null, null);

        String card = renderer.renderResult("合计多少", r, 1L, 0);

        assertThat(card).contains("## ¥16,200");
        assertThat(card).doesNotContain("| --- |");
    }

    @Test
    @DisplayName("空结果明说，不发空表格")
    void emptyResultSaysSo() {
        AskResult r = new AskResult(List.of("门店"), List.of(), 0, null, List.of(), null, 0, null, null, null);

        String card = renderer.renderResult("查不到的东西", r, null, 0);

        assertThat(card).contains("没有符合条件的数据");
        assertThat(card).doesNotContain("| --- |");
    }

    @Test
    @DisplayName("★ 单元格里的竖线被转义，不会把一列撑成两列")
    void pipeInCellEscaped() {
        AskResult r = new AskResult(List.of("名称", "值"),
                List.of(List.of("A|B", "1")), 1, null, List.of(), null, 0, null, null, null);

        String card = renderer.renderResult("含竖线的数据", r, 1L, 0);

        // 转义后是 \|，而不是裸的 |
        assertThat(card).contains("\\\\|");
    }

    @Test
    @DisplayName("★ 文字答复优先于「空结果」判断（直连模式只有文字，没有表格）")
    void textAnswerNotTreatedAsEmpty() {
        AskResult r = new AskResult(List.of(), List.of(), 0, null, List.of(), null, 0,
                "1372", "工单库里没有「交易金额」这个字段，可问的指标有：工单数、处理时长。", null);

        String card = renderer.renderResult("交易金额合计是多少", r, 1L, 0);

        // 曾经只判 rows.isEmpty()，于是这段答案被显示成"没有符合条件的数据"
        assertThat(r.empty()).isFalse();
        assertThat(card).contains("工单库里没有");
        assertThat(card).doesNotContain("没有符合条件的数据");
    }

    @Test
    @DisplayName("★ 深入分析：给结论与发现，不搬步骤明细")
    void analysisRendersConclusion() {
        AskResult.Analysis a = new AskResult.Analysis(
                "华东销量下降主要来自上海门店，环比降 18%。",
                List.of("上海门店1 降 22%", "上海门店2 降 14%"),
                3, null,
                List.of(
                        new AskResult.Step("按组织看交易金额", "定位是哪个组织在降",
                                List.of("所属组织", "交易金额"), List.of(List.of("上海门店1", "100")),
                                9, "SELECT 1", null),
                        new AskResult.Step("按月份看趋势", "确认是否持续下降",
                                List.of("月份", "交易金额"), List.of(List.of("2026-09", "100")),
                                1, "SELECT 2", null),
                        new AskResult.Step("按组织看订单数", "排除是订单量还是单价的问题",
                                List.of("所属组织", "订单数"), List.of(List.of("上海门店1", "5")),
                                4, "SELECT 3", null)));
        AskResult r = new AskResult(List.of(), List.of(), 0, "SELECT 1", List.of(), null, 0,
                "2300", null, a);

        String card = renderer.renderResult("为什么华东销量下降", r, 1L, 0);

        assertThat(card).contains("华东销量下降主要来自上海门店");
        assertThat(card).contains("上海门店1 降 22%");
        assertThat(card).contains("执行了 3 次查询");
    }

    @Test
    @DisplayName("★ 「执行了 N 次查询」必须能看到是哪 N 次（步骤明细在折叠面板里）")
    void analysisStepsAreVisible() {
        AskResult.Analysis a = new AskResult.Analysis("结论", List.of(), 3, null,
                List.of(
                        new AskResult.Step("按组织看交易金额", "定位是哪个组织在降",
                                List.of("所属组织", "交易金额"), List.of(List.of("上海门店1", "100")),
                                9, "SELECT org", null),
                        new AskResult.Step("按月份看趋势", "确认是否持续下降",
                                List.of("月份", "交易金额"), List.of(List.of("2026-09", "100")),
                                1, "SELECT month", null),
                        new AskResult.Step("按组织看订单数", "排除是订单量的问题",
                                List.of("所属组织", "订单数"), List.of(List.of("上海门店1", "5")),
                                4, "SELECT cnt", null)));
        AskResult r = new AskResult(List.of(), List.of(), 0, null, List.of(), null, 0,
                "2200", null, a);

        String card = renderer.renderResult("这个结果是好还是差", r, 1L, 0);

        // 光报次数不给内容是没用的——那数字没有证据
        assertThat(card).contains("查看这 3 次查询");
        assertThat(card).contains("按组织看交易金额");
        assertThat(card).contains("按月份看趋势");
        assertThat(card).contains("9 行");
        // 步骤 SQL 也要能看到，否则没法判断系统查得对不对
        assertThat(card).contains("SELECT org");
    }

    @Test
    @DisplayName("步骤面板默认收起（主信息是结论，过程按需展开）")
    void stepsPanelCollapsed() {
        AskResult.Analysis a = new AskResult.Analysis("结论", List.of(), 1, null,
                List.of(new AskResult.Step("步骤一", "", List.of("a"), List.of(List.of("1")),
                        1, "SELECT 1", null)));
        AskResult r = new AskResult(List.of(), List.of(), 0, null, List.of(), null, 0,
                "2200", null, a);

        String card = renderer.renderResult("为什么", r, 1L, 0);

        // 输出是 pretty-print，键与值之间有空格；用驼峰判断而不是精确字符串
        assertThat(card).containsPattern("\"expanded\"\\s*:\\s*false");
    }

    @Test
    @DisplayName("某一步失败时，卡片要看得出是第几步失败了")
    void failedStepShowsError() {
        AskResult.Analysis a = new AskResult.Analysis("结论", List.of(), 2, null,
                List.of(
                        new AskResult.Step("成功的一步", "", List.of("a"), List.of(List.of("1")),
                                1, "SELECT 1", null),
                        new AskResult.Step("失败的一步", "", List.of(), List.of(), 0,
                                "SELECT 2", "表不存在")));
        AskResult r = new AskResult(List.of(), List.of(), 0, null, List.of(), null, 0,
                "2200", null, a);

        String card = renderer.renderResult("为什么", r, 1L, 0);

        assertThat(card).contains("失败的一步").contains("表不存在");
    }

    @Test
    @DisplayName("分析降级时要把 notice 显出来（用户得知道这是退化的结果）")
    void analysisNoticeShown() {
        AskResult.Analysis a = new AskResult.Analysis("结论", List.of(), 1,
                "分析计划失败，已回退为单次查询", List.of());
        AskResult r = new AskResult(List.of(), List.of(), 0, null, List.of(), null, 0,
                "2300", null, a);

        String card = renderer.renderResult("为什么", r, 1L, 0);

        assertThat(card).contains("已回退为单次查询");
    }

    @Test
    @DisplayName("脱敏列要标注（否则用户会拿脱敏值去对账）")
    void maskedColumnsNoted() {
        AskResult r = new AskResult(List.of("手机号"), List.of(List.of("138****")),
                1, null, List.of("手机号"), null, 0, null, null, null);

        String card = renderer.renderResult("客户手机", r, 1L, 0);

        assertThat(card).contains("已脱敏列");
    }
}

package com.liusongboyy.chatdata.engine.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 解析 {@code ask_message.result_json}。
 *
 * <h2>这里用的 JSON 是从真实库里抄出来的</h2>
 *
 * <p>不是手工构造的样例——手工样例只能证明"我理解的格式能被解析"，
 * 而真实数据能证明"线上存的格式能被解析"。两者不是一回事，
 * 而错在后者时，报告会静默地少掉内容。
 *
 * <p>两段样本分别来自：
 * <ul>
 *   <li>{@code id=216}：深入分析，4 步 + 2 条 findings + narrative</li>
 *   <li>{@code id=482}：单查询，1 张表 + 1 个柱状图</li>
 * </ul>
 */
class ResultJsonReaderTest {

    /** 深入分析的真实结果（已截短 rows，保留结构）。 */
    private static final String ANALYSIS = """
            {"type":"result","domainId":"1",
             "columns":["生效日期","保费","保单数"],
             "rows":[["2026-01",51873.67,6]],
             "rowCount":1,"maskedColumns":[],
             "chart":{"form":"LINE","categoryField":"生效日期","valueFields":["保费"],"seriesField":null,"reason":"看趋势"},
             "sql":"SELECT ?","sqlDisplay":"SELECT 1",
             "mode":"MODELED",
             "understanding":{"metrics":["保费"],"dimensions":["生效日期"],"time":{"kind":"今年","start":null,"end":null},"filters":[],"compare":null},
             "analysis":{
               "intent":"保费自 3 月起逐月下降，需要定位下降的原因",
               "steps":[
                 {"id":"s1","label":"今年各月保费与保单数走势","purpose":"先确认「3 月起逐月下降」是否属实",
                  "status":"OK","columns":["生效日期","保费"],"rows":[["2026-01",51873.67],["2026-05",27634.11]],
                  "rowCount":2,"durationMs":14,"sqlDisplay":"SELECT 1",
                  "dsl":{"metrics":["保费","保单数"],"dimensions":["生效日期"],"timeRange":{"kind":"今年"},"filters":[],"granularity":"MONTH"},
                  "chart":{"form":"LINE","categoryField":"生效日期","valueFields":["保费"],"seriesField":null,"reason":"看趋势"}},
                 {"id":"s2","label":"失败的步骤","purpose":"这步挂了","status":"FAILED","error":"boom"}
               ],
               "findings":[
                 {"kind":"TREND","text":"「2026-01」到「2026-05」，保费从 51873.67 降到 27634.11（-46.7%）",
                  "stepId":"s1","evidence":{"changePct":-46.728061,"firstValue":51873.67}}
               ],
               "narrative":"保费自 1 月到 5 月下滑 46.7%",
               "truncated":false,"queryCount":2}}""";

    /** 单查询的真实结果。 */
    private static final String SINGLE = """
            {"type":"result","domainId":"2200",
             "columns":["所属组织","交易金额"],
             "rows":[["上海门店1",1000.00],["上海门店2",2600.00]],
             "rowCount":2,"maskedColumns":[],
             "chart":{"form":"BAR","categoryField":"所属组织","valueFields":["交易金额"],"seriesField":null,"reason":"比较量级"},
             "sql":"SELECT ?","sqlDisplay":"SELECT `org_name`, SUM(`amount`) ...",
             "mode":"MODELED",
             "understanding":{"metrics":["交易金额"],"dimensions":["所属组织"],"time":null,"filters":[],"compare":null},
             "answer":null,"analysis":null,"notice":null}""";

    @Test
    @DisplayName("★ 深入分析：步骤、事实、结论、口径全解析出来")
    void readsAnalysis() {
        ReportSource src = ResultJsonReader.read("216", "保费为什么下降", ANALYSIS);

        assertThat(src).isNotNull();
        assertThat(src.hasAnalysis()).isTrue();
        assertThat(src.analysis().intent()).contains("逐月下降");
        assertThat(src.analysis().narrative()).contains("46.7%");
        assertThat(src.analysis().findings()).hasSize(1);
        assertThat(src.analysis().findings().get(0).kind()).isEqualTo("TREND");
        assertThat(src.analysis().findings().get(0).evidence())
                .containsEntry("firstValue", 51873.67);
    }

    @Test
    @DisplayName("★ 失败的步骤被跳过——它没有数据，进报告只会让读者困惑")
    void skipsFailedSteps() {
        ReportSource src = ResultJsonReader.read("216", "q", ANALYSIS);

        assertThat(src.analysis().steps())
                .as("原始 2 步，其中 s2 是 FAILED")
                .hasSize(1);
        assertThat(src.analysis().steps().get(0).label()).contains("走势");
    }

    @Test
    @DisplayName("★ 分析步骤的口径从 dsl.timeRange 读，不是 understanding")
    void readsStepScopeFromDsl() {
        ReportSource src = ResultJsonReader.read("216", "q", ANALYSIS);

        StepSnapshot step = src.analysis().steps().get(0);
        assertThat(step.scope().time()).isEqualTo("今年");
        assertThat(step.scope().metrics()).containsExactly("保费", "保单数");
    }

    @Test
    @DisplayName("★ 单查询：没有 analysis，但有 columns/rows/chart/口径")
    void readsSingleQuery() {
        ReportSource src = ResultJsonReader.read("482", "各区域交易金额", SINGLE);

        assertThat(src).isNotNull();
        assertThat(src.hasAnalysis()).as("单查询的 analysis 是 null").isFalse();
        assertThat(src.columns()).containsExactly("所属组织", "交易金额");
        assertThat(src.rows()).hasSize(2);
        assertThat(src.chart().form())
                .isEqualTo(com.liusongboyy.chatdata.engine.chat.ChartSpec.Form.BAR);
        assertThat(src.sqlDisplay()).contains("SUM");
        assertThat(src.scope().metrics()).containsExactly("交易金额");
        assertThat(src.scope().time()).as("单查询没时间范围").isNull();
    }

    @Test
    @DisplayName("★ 口径从 understanding 读（单查询路径）")
    void readsScopeFromUnderstanding() {
        ReportSource src = ResultJsonReader.read("482", "q", SINGLE);

        assertThat(src.scope().dimensions()).containsExactly("所属组织");
        assertThat(src.scope().describe()).isEqualTo("全部范围 · 不限时间");
    }

    @Test
    @DisplayName("坏数据返回 null，不抛——一份历史坏了不该让整份报告生成失败")
    void badJsonReturnsNull() {
        assertThat(ResultJsonReader.read("1", "q", "{不是 json")).isNull();
        assertThat(ResultJsonReader.read("1", "q", "[]")).isNull();
        assertThat(ResultJsonReader.read("1", "q", "")).isNull();
        assertThat(ResultJsonReader.read("1", "q", null)).isNull();
    }

    @Test
    @DisplayName("认不出的图表形态退成表格，不让整轮失败")
    void unknownChartFallsBackToTable() {
        String json = SINGLE.replace("\"BAR\"", "\"RADAR\"");

        ReportSource src = ResultJsonReader.read("1", "q", json);

        assertThat(src).isNotNull();
        assertThat(src.chart().form())
                .isEqualTo(com.liusongboyy.chatdata.engine.chat.ChartSpec.Form.TABLE);
    }

    @Test
    @DisplayName("★ 老数据没有 chart 字段 → 按列推断，不全是表格")
    void infersChartWhenMissing() {
        // 实测：2026-10-26 存的 steps 里没有 chart（那时还没加这个字段）。
        // 直接给 TABLE 会让报告里所有分析步骤都是表格，读者看不出趋势。
        //
        // 这里直接构造一段"没有 chart"的 JSON，而不是从上面的常量里删字段——
        // 字符串替换很容易把 JSON 弄坏，而坏 JSON 只会走到"跳过坏数据"分支，
        // 测试就变成了在验证别的东西
        String noChart = """
                {"type":"result",
                 "columns":["月份","保费"],
                 "rows":[["2026-01",100]],
                 "rowCount":1,
                 "understanding":{"metrics":["保费"],"dimensions":["月份"],"time":null,"filters":[]},
                 "analysis":{
                   "intent":"趋势如何",
                   "steps":[
                     {"id":"s1","label":"各月保费","purpose":"看趋势","status":"OK",
                      "columns":["月份","保费"],"rows":[["2026-01",100],["2026-02",200]],
                      "rowCount":2,
                      "dsl":{"metrics":["保费"],"dimensions":["月份"],"timeRange":null,"filters":[]}}
                   ],
                   "findings":[],
                   "narrative":"在涨"}}""";

        ReportSource src = ResultJsonReader.read("216", "q", noChart);

        assertThat(src).isNotNull();
        StepSnapshot step = src.analysis().steps().get(0);
        assertThat(step.columns()).hasSize(2);
        assertThat(step.chart().form())
                .as("老数据全退成表格的话，报告里看不出任何趋势")
                .isEqualTo(com.liusongboyy.chatdata.engine.chat.ChartSpec.Form.BAR);
        assertThat(step.chart().reason()).contains("推断");
    }

    @Test
    @DisplayName("★ 端到端：真实结果 → 报告")
    void buildsReportFromRealJson() {
        ReportSource a = ResultJsonReader.read("216", "保费为什么下降", ANALYSIS);
        ReportSource s = ResultJsonReader.read("482", "各区域交易金额", SINGLE);

        var report = ReportBuilder.build("conv-1", "admin", java.util.List.of(a, s));

        assertThat(report.body().sections()).as("一条分析").hasSize(1);
        assertThat(report.body().dataSections()).as("一条单查询").hasSize(1);
        assertThat(report.body().conclusion()).contains("46.7%");
        assertThat(report.body().scopeConflict())
                .as("一条有「今年」、一条不限时间")
                .isTrue();
    }
}

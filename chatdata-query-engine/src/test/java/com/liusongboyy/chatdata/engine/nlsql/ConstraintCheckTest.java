package com.liusongboyy.chatdata.engine.nlsql;

import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;
import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import com.liusongboyy.chatdata.engine.semantic.Term;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语义完整性校验。
 *
 * <p>这一层挡的是本项目记录过多次的那类失败：**DSL 合法、无异常，但问题里的约束消失**。
 * 实测撞到过两次——「华东」「有效」被丢（spike），「上个月」被丢（2026-10-18 真实链路）。
 * 两者都退化成全量查询，答案错误且全程静默。
 *
 * <p>所以这里既有"该报的要报"，也有**"不该报的不能报"**——误报的代价是一次多余的
 * 模型调用，还可能把本来正确的 DSL 改坏。
 */
class ConstraintCheckTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 18);

    private static SemanticModel model() {
        RegisteredTable policy = new RegisteredTable("1", "ins_policy", "保单表",
                RegisteredTable.Mode.TABLE, null, true, List.of(
                new ColumnDef("premium", "decimal", Aggregation.SUM, "保费", true),
                new ColumnDef("region", "varchar", Aggregation.NONE, "区域", true),
                new ColumnDef("status", "varchar", Aggregation.NONE, "保单状态", true),
                new ColumnDef("effect_date", "date", Aggregation.NONE, "生效日期", true)));

        Term valid = new Term("有效", List.of("生效", "有效保单"),
                "保单状态为有效，即 status='有效'", Term.ApplyKind.NONE);

        Domain domain = new Domain("d1", "保险分析域", "1", "MYSQL5.7", true,
                List.of(policy), List.of(), DomainPermission.from(null),
                List.of(valid), List.of());
        return new SemanticModel(domain);
    }

    private static AskDsl dsl(AskDsl.TimeRange time, List<AskDsl.Filter> filters) {
        return new AskDsl(List.of("保费"), List.of(), time, filters);
    }

    private static List<String> missing(String question, AskDsl dsl) {
        return ConstraintCheck.findMissing(question, dsl, model(), TODAY);
    }

    @Nested
    @DisplayName("时间口径 —— 今天真实撞到的那一类")
    class Time {

        @Test
        @DisplayName("★ 问题说了「上个月」而 timeRange 为空 → 必须报")
        void mentionedButMissing() {
            assertThat(missing("上个月华东的保费合计是多少", dsl(null, List.of())))
                    .singleElement()
                    .satisfies(m -> assertThat(m).contains("上个月").contains("timeRange"));
        }

        @Test
        @DisplayName("★ 都对上了就不报")
        void presentAndCorrect() {
            assertThat(missing("上个月华东的保费合计是多少",
                    dsl(new AskDsl.TimeRange("上个月", null, null), List.of()))).isEmpty();
        }

        @Test
        @DisplayName("★ 口径写错也要报 —— 结构完整但时间不对，只有比日期区间才发现")
        void wrongKind() {
            assertThat(missing("上个月华东的保费合计是多少",
                    dsl(new AskDsl.TimeRange("本月", null, null), List.of())))
                    .singleElement()
                    .satisfies(m -> assertThat(m).contains("上个月").contains("本月"));
        }

        @Test
        @DisplayName("模型给出等价的显式日期区间 → 不报（那是对的写法）")
        void equivalentExplicitDates() {
            // 「上个月」在 2026-10-18 解析为 [2026-09-01, 2026-10-01)
            assertThat(missing("上个月华东的保费合计是多少",
                    dsl(new AskDsl.TimeRange(null, "2026-09-01", "2026-09-30"), List.of()))).isEmpty();
        }

        @Test
        @DisplayName("★ 问题里没提时间 → **不能报**（本来就该查全时段）")
        void noTimeMentionedIsFine() {
            assertThat(missing("华东的保费合计是多少", dsl(null, List.of()))).isEmpty();
        }

        @Test
        @DisplayName("「近 N 天」这类模式也要认出来")
        void recentDays() {
            assertThat(missing("近7天的保费是多少", dsl(null, List.of())))
                    .singleElement()
                    .satisfies(m -> assertThat(m).contains("近7天"));
        }

        @Test
        @DisplayName("「近 N 个月」这类模式也要认出来")
        void recentMonths() {
            assertThat(missing("近三个月的保费是多少", dsl(null, List.of())))
                    .singleElement()
                    .satisfies(m -> assertThat(m).contains("近三个月"));
        }

        @Test
        @DisplayName("同义口径（「上月」vs「上个月」）区间相同 → 不报")
        void synonymKind() {
            assertThat(missing("上月的保费合计是多少",
                    dsl(new AskDsl.TimeRange("上个月", null, null), List.of()))).isEmpty();
        }
    }

    @Nested
    @DisplayName("业务术语")
    class Terms {

        @Test
        @DisplayName("★ 问题用了术语的说法而 DSL 里完全没有 → 报")
        void termDropped() {
            assertThat(missing("上个月有效的保费合计是多少",
                    dsl(new AskDsl.TimeRange("上个月", null, null), List.of())))
                    .singleElement()
                    .satisfies(m -> assertThat(m).contains("有效"));
        }

        @Test
        @DisplayName("术语作为筛选**取值**出现也算体现 → 不报")
        void termSatisfiedByFilterValue() {
            assertThat(missing("上个月有效的保费合计是多少",
                    dsl(new AskDsl.TimeRange("上个月", null, null),
                            List.of(new AskDsl.Filter("保单状态", "=", "有效"))))).isEmpty();
        }

        @Test
        @DisplayName("近义词也算 —— 「生效」与「有效」是同一个术语")
        void synonym() {
            assertThat(missing("上个月生效的保费合计是多少",
                    dsl(new AskDsl.TimeRange("上个月", null, null), List.of())))
                    .singleElement()
                    .satisfies(m -> assertThat(m).contains("生效"));
        }

        @Test
        @DisplayName("同一个术语的多个说法只报一次 —— 否则纠正提示会重复啰嗦")
        void reportedOnce() {
            assertThat(missing("有效的生效的保单，保费是多少",
                    dsl(null, List.of())))
                    .as("两个说法命中同一个术语，只应报一条")
                    .hasSize(1);
        }
    }

    @Test
    @DisplayName("★ 口径词表与解析器不漂移 —— 表里每个词都必须真能解析")
    void kindWordsAreAllResolvable() {
        assertThat(TimeRangeResolver.KIND_WORDS)
                .as("往 switch 里加了口径却忘了加进 KIND_WORDS（或反之），"
                        + "会让校验漏报或误报，而两者都不报错")
                .isNotEmpty()
                .allSatisfy(word -> assertThat(TimeRangeResolver.isSupportedKindWord(word))
                        .as("「%s」在词表里却解析不了", word)
                        .isTrue());
    }
}

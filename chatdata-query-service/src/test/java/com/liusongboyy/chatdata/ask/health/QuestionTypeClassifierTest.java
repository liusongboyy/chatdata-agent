package com.liusongboyy.chatdata.ask.health;

import com.liusongboyy.chatdata.ask.health.QuestionTypeClassifier.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 问题类型分类。
 *
 * <p>每条断言的注释里标了它在真实数据里的分布，规则是从那批数据提炼的。
 */
class QuestionTypeClassifierTest {

    // ── DSL 结构 ──

    @Test
    @DisplayName("★ 有 dimension → 分组统计（2620 条，最大的一类）")
    void dimensionMeansGrouped() {
        String dsl = "{\"metric\":\"保费合计\",\"dimension\":\"大区\",\"time\":\"全部\",\"filter\":\"\"}";
        assertThat(QuestionTypeClassifier.classify(dsl, "按大区统计保费"))
                .isEqualTo(Type.GROUPED);
    }

    @Test
    @DisplayName("★ 空 dimension 不能算分组——这是踩过的坑")
    void emptyDimensionIsNotGrouped() {
        // 用 LIKE '%"dimension":"%' 判断的话，空串 "dimension":"" 也会命中，
        // 于是 4878 条全被误归成「分组统计」。必须取字段值比。
        String dsl = "{\"metric\":\"保费合计\",\"dimension\":\"\",\"time\":\"全部\",\"filter\":\"\"}";
        assertThat(QuestionTypeClassifier.classify(dsl, "保费合计是多少"))
                .as("空维度是单值汇总，不是分组")
                .isEqualTo(Type.SCALAR);
    }

    @Test
    @DisplayName("完整 DSL 的 dimensions 数组也认")
    void fullDslDimensionsArray() {
        String dsl = "{\"metrics\":[\"保费合计\"],\"dimensions\":[\"大区\",\"险种\"]}";
        assertThat(QuestionTypeClassifier.classify(dsl, "按大区和险种"))
                .isEqualTo(Type.GROUPED);
    }

    @Test
    @DisplayName("★ 有 filter 无 dimension → 带筛选单值")
    void filterMeansFiltered() {
        String dsl = "{\"metric\":\"保费合计\",\"dimension\":\"\",\"filter\":\"区域 = 华东\"}";
        assertThat(QuestionTypeClassifier.classify(dsl, "华东的保费是多少"))
                .isEqualTo(Type.FILTERED);
    }

    @Test
    @DisplayName("既无维度也无筛选 → 单值汇总（2267 条）")
    void neitherMeansScalar() {
        String dsl = "{\"metric\":\"客户总数\",\"dimension\":\"\",\"filter\":\"\"}";
        assertThat(QuestionTypeClassifier.classify(dsl, "客户总数是多少"))
                .isEqualTo(Type.SCALAR);
    }

    @Test
    @DisplayName("★ 分组优先于筛选：既有筛选又有分组时算分组")
    void groupedWinsOverFiltered() {
        // 「华东各门店的保费」既有筛选又有分组，但它的问题是
        // "能不能按门店分组"，与纯筛选的问法不同
        String dsl = "{\"metric\":\"保费合计\",\"dimension\":\"门店\",\"filter\":\"区域 = 华东\"}";
        assertThat(QuestionTypeClassifier.classify(dsl, "华东各门店的保费"))
                .isEqualTo(Type.GROUPED);
    }

    // ── 文本兜底 ──

    @Test
    @DisplayName("★ 没有 DSL 时按文本兜底")
    void fallsBackToText() {
        // 没 DSL 的多是失败，丢掉就看不到"哪类问题连 DSL 都生成不出来"
        assertThat(QuestionTypeClassifier.classify(null, "为什么保费下降了"))
                .isEqualTo(Type.ATTRIBUTION);
        assertThat(QuestionTypeClassifier.classify("", "各月保费走势如何"))
                .isEqualTo(Type.TREND);
        assertThat(QuestionTypeClassifier.classify(null, "今年比去年同比怎么样"))
                .isEqualTo(Type.COMPARE);
        assertThat(QuestionTypeClassifier.classify(null, "按门店统计数量"))
                .isEqualTo(Type.GROUPED);
    }

    @Test
    @DisplayName("★ 认不出的归 UNKNOWN，不是乱猜")
    void unrecognizedIsUnknown() {
        // UNKNOWN 是信号不是垃圾桶：1203 条里有 200 条失败，
        // 占全部失败的六成。这个桶变大就该提炼新规则
        assertThat(QuestionTypeClassifier.classify(null, "你好"))
                .isEqualTo(Type.UNKNOWN);
        assertThat(QuestionTypeClassifier.classify(null, null))
                .isEqualTo(Type.UNKNOWN);
        assertThat(QuestionTypeClassifier.classify("不是 json", ""))
                .isEqualTo(Type.UNKNOWN);
    }

    @Test
    @DisplayName("非法 DSL 回退到文本，不崩")
    void invalidDslFallsBack() {
        // 半个 JSON、数组、纯字符串——都不该抛
        assertThat(QuestionTypeClassifier.classify("{broken", "各门店销量"))
                .isEqualTo(Type.GROUPED);
        assertThat(QuestionTypeClassifier.classify("[1,2,3]", "为什么"))
                .isEqualTo(Type.ATTRIBUTION);
    }

    @Test
    @DisplayName("每个类型都有稳定的 key 与 label")
    void typesAreComplete() {
        for (Type t : Type.values()) {
            assertThat(t.key()).isNotBlank();
            assertThat(t.label()).isNotBlank();
        }
    }
}

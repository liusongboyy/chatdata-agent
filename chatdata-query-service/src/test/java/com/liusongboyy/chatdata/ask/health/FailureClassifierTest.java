package com.liusongboyy.chatdata.ask.health;

import com.liusongboyy.chatdata.ask.health.FailureClassifier.Classification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败原因归类。
 *
 * <p>每条断言后面标注的数字是它在 2026-11-04 那 267 条真实失败里的条数——
 * 规则是从真实数据里提炼的，测试锁住它别漂。
 */
class FailureClassifierTest {

    @Test
    @DisplayName("★ 枚举值原样保留：NOT_UNDERSTOOD 不允许被关键词覆盖")
    void enumValuesAreNotOverridden() {
        // 79 条，是最大的桶
        assertThat(FailureClassifier.classify("NOT_UNDERSTOOD").key())
                .isEqualTo("NOT_UNDERSTOOD");
        assertThat(FailureClassifier.classify("DSL_FAILED").key()).isEqualTo("DSL_FAILED");
        assertThat(FailureClassifier.classify("PERMISSION_DENIED").key())
                .isEqualTo("PERMISSION_DENIED");
    }

    @Test
    @DisplayName("★ 建模缺口：字段角色冲突（52 条）")
    void roleConflict() {
        String msg = "「评论数」配置了聚合方式（COUNT），不能作为维度。"
                + "若确需按它分组，请在语义建模中另加一条不聚合的列定义";
        Classification c = FailureClassifier.classify(msg);

        assertThat(c.key()).isEqualTo("ROLE_CONFLICT");
        assertThat(c.fixable()).as("这是能靠补双登记解决的").isTrue();
    }

    @Test
    @DisplayName("★ 建模缺口：缺时间口径（26 条）")
    void noTimeColumn() {
        String msg = "问数里带了时间条件（2025年），但本域内找不到可用的日期列。"
                + "请在语义建模中把日期列的别名设为「生效日期」「统计日期」等";
        Classification c = FailureClassifier.classify(msg);

        assertThat(c.key()).isEqualTo("NO_TIME_COLUMN");
        assertThat(c.fixable()).isTrue();
    }

    @Test
    @DisplayName("★ 建模缺口：模型编了不存在的列（16 条）")
    void unknownColumn() {
        assertThat(FailureClassifier.classify(
                "查询执行失败：Unknown column 't1.effect_date' in 'where clause'").key())
                .isEqualTo("UNKNOWN_COLUMN");
        assertThat(FailureClassifier.classify(
                "查询执行失败：You have an error in your SQL syntax; check the manual").key())
                .isEqualTo("UNKNOWN_COLUMN");
    }

    @Test
    @DisplayName("★ 环境问题必须单列，不能混进建模缺口")
    void environmentIsSeparate() {
        // 混进"建模缺口"会让管理员去改配置，而真正该做的是重启服务或补密钥。
        // **归类错了比不归类更糟——它会把排查方向引偏。**
        Classification conn = FailureClassifier.classify(
                "查询执行失败：ask-ds-120 - Connection is not available, request timed out");
        assertThat(conn.key()).isEqualTo("ENVIRONMENT");
        assertThat(conn.fixable()).as("改建模配置解决不了连接问题").isFalse();

        Classification key = FailureClassifier.classify(
                "问数引擎的模型未配置成功：缺少配置 chatdata.engine.model.api-key");
        assertThat(key.key()).isEqualTo("ENVIRONMENT");
        assertThat(key.fixable()).isFalse();
    }

    @Test
    @DisplayName("模型填错取值不属于建模问题（30 条）")
    void badValueTypeIsNotFixable() {
        Classification c = FailureClassifier.classify(
                "筛选条件「关闭时间」的取值「SLA截止时间」不是合法日期（应为 yyyy-MM-dd）");

        assertThat(c.key()).isEqualTo("BAD_VALUE_TYPE");
        assertThat(c.fixable()).as("这是模型填错的，不是配置缺失").isFalse();
    }

    @Test
    @DisplayName("★ 空消息不返回 null，归到 OTHER")
    void emptyMessageIsNotCrash() {
        assertThat(FailureClassifier.classify(null).key()).isEqualTo("OTHER");
        assertThat(FailureClassifier.classify("").key()).isEqualTo("OTHER");
        assertThat(FailureClassifier.classify("   ").key()).isEqualTo("OTHER");
    }

    @Test
    @DisplayName("每次归类都必须有 key 和 label")
    void everyClassificationIsComplete() {
        String[] samples = {
                "NOT_UNDERSTOOD",
                "「评论数」配置了聚合方式（COUNT），不能作为维度",
                "找不到可用的日期列",
                "没有可渲染的输出列",
                "Unknown column 'x'",
                "不是合法日期",
                "无法解析的时间口径：「上周」",
                "Connection is not available",
                "完全没见过的错误",
        };
        for (String s : samples) {
            Classification c = FailureClassifier.classify(s);
            assertThat(c.key()).as("归类必须有 key：%s", s).isNotBlank();
            assertThat(c.label()).as("归类必须有 label：%s", s).isNotBlank();
        }
    }

    @Test
    @DisplayName("★ 真实数据回放：267 条落在 9 个桶内")
    void realDataFallsIntoKnownBuckets() {
        // 这九个是 2026-11-04 实测的分布。新增一种失败模式时，
        // 应该能立刻看出它落在了哪个桶，而不是默默掉进 OTHER
        var known = FailureClassifier.allKeys();
        String[] realSamples = {
                "NOT_UNDERSTOOD",
                "「评论数」配置了聚合方式（COUNT），不能作为维度。若确需按它分组…",
                "筛选条件「关闭时间」的取值「SLA截止时间」不是合法日期（应为 yyyy-MM-dd）",
                "DSL 没有可渲染的输出列",
                "问数里带了时间条件（2025年），但本域内找不到可用的日期列",
                "「平均保障年限」配置了聚合方式（AVG），不能作为维度",
                "查询执行失败：You have an error in your SQL syntax; check the manual",
                "查询执行失败：Unknown column 't1.effect_date' in 'where clause'",
                "查询执行失败：ask-ds-120 - Connection is not available, request timed out",
                "无法解析的时间口径：「4月」。已支持：今天/昨天…",
                "同环比需要明确的时间范围（如「今年」），当前问题没有给出",
        };
        for (String s : realSamples) {
            assertThat(FailureClassifier.classify(s).key())
                    .as("未归入已知桶：%s", s)
                    .matches(known);
        }
    }
}

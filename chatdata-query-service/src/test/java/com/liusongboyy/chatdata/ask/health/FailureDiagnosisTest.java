package com.liusongboyy.chatdata.ask.health;

import com.liusongboyy.chatdata.ask.health.FailureDiagnosis.Confidence;
import com.liusongboyy.chatdata.ask.health.FailureDiagnosis.Diagnosis;
import com.liusongboyy.chatdata.ask.health.FailureDiagnosis.Field;
import com.liusongboyy.chatdata.ask.health.FailureDiagnosis.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败诊断。
 *
 * <p>三条主用例都是 2026-11-04 从真实审计表里捞出来的，条数是实际发生次数。
 */
class FailureDiagnosisTest {

    /** 工单域的真实字段（截取）。 */
    private static List<Field> ticketFields() {
        return List.of(
                new Field("工单表", "工单数"),
                new Field("工单表", "优先级"),
                new Field("工单表", "平均优先级"),
                new Field("工单表", "状态"),
                new Field("工单表", "解决时间"));
    }

    /** 保险域的真实字段（截取）。没有门店相关字段——这是关键。 */
    private static List<Field> insuranceFields() {
        return List.of(
                new Field("保单表", "保费"),
                new Field("保单表", "客户数"),
                new Field("保单表", "区域"),
                new Field("理赔表", "理赔件数"));
    }

    @Test
    @DisplayName("★ 配置已有：22 次「平均优先级」失败但配置是对的")
    void configPresentButModelFailed() {
        // 实测：这个问题 22 次全失败，但域里有「平均优先级 AVG(int)」——
        // 配置完全正确，是模型没能选到它
        Diagnosis d = FailureDiagnosis.diagnose(
                "NOT_UNDERSTOOD", "平均优先级是多少？", ticketFields());

        assertThat(d.kind()).isEqualTo(Kind.CONFIG_PRESENT);
        assertThat(d.evidence()).as("必须给出证据，证明真的看过配置")
                .contains("平均优先级");
        assertThat(d.summary()).contains("模型");
    }

    @Test
    @DisplayName("★ 问错了域：12 次门店问题落到保险域")
    void wrongDomain() {
        // 实测：这句在保险域 12 次全失败，因为该域没有任何门店字段
        Diagnosis d = FailureDiagnosis.diagnose(
                "NOT_UNDERSTOOD", "各门店的订单金额合计", insuranceFields());

        assertThat(d.kind()).isEqualTo(Kind.WRONG_DOMAIN);
        assertThat(d.summary()).contains("没有");
        assertThat(d.confidence()).isEqualTo(Confidence.NONE);
    }

    @Test
    @DisplayName("★ 部分匹配：客户总数 vs 客户数（差一个字）")
    void partialMatch() {
        // 精确匹配对不上，但「客户数」是「客户总数」的子串关系
        Diagnosis d = FailureDiagnosis.diagnose(
                "NOT_UNDERSTOOD", "客户总数是多少？", insuranceFields());

        assertThat(d.kind()).isEqualTo(Kind.CONFIG_PRESENT);
        assertThat(d.confidence()).as("差一个字，只能算部分匹配").isEqualTo(Confidence.PARTIAL);
        assertThat(d.evidence()).contains("客户数");
    }

    @Test
    @DisplayName("★ 能修的：字段角色冲突给定位")
    void roleConflictIsActionable() {
        Diagnosis d = FailureDiagnosis.diagnose(
                "「评论数」配置了聚合方式（COUNT），不能作为维度", "按评论数分组", ticketFields());

        assertThat(d.kind()).isEqualTo(Kind.MISSING_CONFIG);
        assertThat(d.kind().actionable()).isTrue();
        assertThat(d.evidence()).contains("维度").contains("指标");
    }

    @Test
    @DisplayName("★ 环境问题不归给建模——会把人引去改配置")
    void environmentIsSeparate() {
        Diagnosis d = FailureDiagnosis.diagnose(
                "ask-ds-120 - Connection is not available, request timed out", "保费多少", insuranceFields());

        assertThat(d.kind()).isEqualTo(Kind.ENVIRONMENT);
        assertThat(d.kind().actionable()).as("改建模配置解决不了连接问题").isFalse();
    }

    @Test
    @DisplayName("★ 匹配不上时不假装有答案")
    void noMatchDoesNotPretend() {
        Diagnosis d = FailureDiagnosis.diagnose(
                "NOT_UNDERSTOOD", "今天天气怎么样", insuranceFields());

        // 一个字段都对不上 → 归到"问错了域"，而不是硬给一个"可修"的建议
        assertThat(d.kind()).isEqualTo(Kind.WRONG_DOMAIN);
        assertThat(d.fixTarget()).isNull();
    }

    @Test
    @DisplayName("空输入不崩")
    void emptyInputs() {
        assertThat(FailureDiagnosis.diagnose(null, null, List.of()).kind())
                .isNotNull();
        assertThat(FailureDiagnosis.diagnose("NOT_UNDERSTOOD", "任意问题", List.of()).kind())
                .isNotNull();
    }

    @Test
    @DisplayName("定位只给确实匹配上的列，不瞎猜")
    void locateOnlyWhenMatched() {
        Diagnosis d = FailureDiagnosis.diagnose(
                "「评论数」配置了聚合方式（COUNT），不能作为维度", "按评论数分组", ticketFields());

        // 问题里"评论数"对不上任何字段 → 不给定位，而不是随便挑一个
        assertThat(d.fixTarget()).isNull();
    }

    @Test
    @DisplayName("每个 Kind 都有 label，actionable 的才给跳转")
    void kindsAreComplete() {
        for (Kind k : Kind.values()) {
            assertThat(k.label()).isNotBlank();
        }
        assertThat(Kind.MISSING_CONFIG.actionable()).isTrue();
        assertThat(Kind.CONFIG_PRESENT.actionable()).isFalse();
        assertThat(Kind.WRONG_DOMAIN.actionable()).isFalse();
        assertThat(Kind.ENVIRONMENT.actionable()).isFalse();
        assertThat(Kind.UNKNOWN.actionable()).isFalse();
    }
}

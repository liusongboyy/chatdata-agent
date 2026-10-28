package com.liusongboyy.chatdata.engine.agent;

import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;
import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import com.liusongboyy.chatdata.engine.semantic.Term;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提示词装配。
 *
 * <p><strong>这里验的是"规则有没有告诉模型"，不是"模型答得对不对"。</strong>
 * 后者只能靠评测集回答。但规则没写进 prompt 时，模型不可能照做——
 * 那是确定的、可单测的缺陷。
 *
 * <p>背景：{@code granularity}（时间粒度）曾经只写在 JSON Schema 的字段说明里，
 * 业务规则那一段完全没提。实测「按年份统计」的题全部退化成按日分组，
 * 268 行原始日期直接当成"年份"返回。
 */
class PromptAssemblerTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static SemanticModel model() {
        RegisteredTable policy = new RegisteredTable("tbl-1", "ins_policy", "保单表",
                RegisteredTable.Mode.TABLE, null, true, List.of(
                new ColumnDef("premium", "decimal", Aggregation.SUM, "保费", true),
                new ColumnDef("effect_date", "date", Aggregation.NONE, "生效日期", true)));

        DomainPermission perm;
        try {
            perm = DomainPermission.from(M.readTree("""
                    {"v":1,"grantRoles":["分析师"],"unrestrictedRoles":["分析师"]}"""));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        Domain domain = new Domain("d1", "保险分析域", "ds1", "MYSQL5.7", true,
                List.of(policy), null, perm, List.of(), List.of());
        return new SemanticModel(domain);
    }

    @Test
    @DisplayName("★ 规则里必须提到时间粒度（曾只写在 schema，模型从不填）")
    void rulesMentionGranularity() {
        String prompt = PromptAssembler.assemble(model());

        assertThat(prompt)
                .as("用户说「按年」「按月」时必须产出 granularity，否则退化成按日分组")
                .contains("granularity")
                .contains("month")
                .contains("year");
    }

    @Test
    @DisplayName("指标与维度分组列出，且名字不带聚合说明")
    void separatesMetricsFromDimensions() {
        String prompt = PromptAssembler.assemble(model());

        assertThat(prompt).contains("只能作为指标的字段：保费");
        assertThat(prompt).contains("只能作为维度的字段：生效日期");
        // 名字里混进聚合说明会让模型把它当成字段名（见 appendTable 的注释）
        assertThat(prompt).doesNotContain("保费（SUM）");
    }

    @Test
    @DisplayName("没有术语时不输出空标题")
    void noTermsNoHeading() {
        String prompt = PromptAssembler.assemble(model());
        assertThat(prompt).doesNotContain("业务术语（用户可能这样说");
    }

    @Test
    @DisplayName("有术语时列出近义词与释义")
    void rendersTerms() {
        String text = PromptAssembler.businessTerms(List.of(
                new Term("保费", List.of("保费合计", "保费金额"), "保单的年缴保费", Term.ApplyKind.NONE)));

        assertThat(text).contains("保费").contains("保费合计").contains("保单的年缴保费");
    }
}

package com.liusongboyy.chatdata.engine.agent;

import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;
import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 列描述与取值样例进 prompt。
 *
 * <h2>为什么它值得单独测</h2>
 *
 * <p>这是提升准确率最高性价比的一环（BIRD 实测 +7.3 个百分点），但它的效果**完全依赖
 * prompt 里真的带上了这些信息**。描述存进库却没进 prompt，是那种"配置了但没生效"的
 * 静默失效——而它不会报错，只是准确率没变。
 */
class PromptFieldNotesTest {

    private static SemanticModel model(ColumnDef... cols) {
        RegisteredTable t = new RegisteredTable("t1", "ticket", "工单表",
                RegisteredTable.Mode.TABLE, null, true, List.of(cols));
        Domain d = new Domain("d1", "工单域", "ds1", "MYSQL5.7", true, List.of(t),
                List.of(), DomainPermission.from(null), List.of(), List.of());
        return new SemanticModel(d);
    }

    @Test
    @DisplayName("★ 有描述与样例的字段，两者都进 prompt")
    void descriptionAndSamplesAppear() {
        String prompt = PromptAssembler.assemble(model(new ColumnDef(
                "status", "varchar", Aggregation.NONE, "状态", true, null, null,
                "工单状态", List.of("OPEN", "CLOSED", "RESOLVED"))));

        assertThat(prompt).contains("字段说明").contains("状态");
        assertThat(prompt).contains("工单状态");
        assertThat(prompt).contains("OPEN").contains("CLOSED").contains("RESOLVED");
    }

    @Test
    @DisplayName("★ 没有描述的字段**不编造说明**——只列名字，和以前一样")
    void noDescriptionNoFabrication() {
        String prompt = PromptAssembler.assemble(model(new ColumnDef(
                "amount", "decimal", Aggregation.SUM, "金额", true)));

        assertThat(prompt).contains("只能作为指标的字段").contains("金额");
        assertThat(prompt).as("没画像就不该有字段说明块").doesNotContain("字段说明");
    }

    @Test
    @DisplayName("关闭的列即使有描述也不进 prompt")
    void disabledColumnExcluded() {
        String prompt = PromptAssembler.assemble(model(new ColumnDef(
                "secret", "varchar", Aggregation.NONE, "密钥", false, null, null,
                "不该出现", List.of("x"))));

        assertThat(prompt).doesNotContain("不该出现").doesNotContain("密钥");
    }

    @Test
    @DisplayName("只有描述没有样例，或只有样例没有描述，都正常")
    void partialInfoStillWorks() {
        String onlyDesc = PromptAssembler.assemble(model(new ColumnDef(
                "a", "int", Aggregation.SUM, "甲", true, null, null, "甲的含义", List.of())));
        assertThat(onlyDesc).contains("甲的含义");

        String onlySamples = PromptAssembler.assemble(model(new ColumnDef(
                "b", "varchar", Aggregation.NONE, "乙", true, null, null, null,
                List.of("X", "Y"))));
        assertThat(onlySamples).contains("取值").contains("X").contains("Y");
    }
}

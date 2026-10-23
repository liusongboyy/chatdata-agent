package com.liusongboyy.chatdata.engine.raw;

import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;
import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.Term;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 直连模式的提示词。
 *
 * <p>这里测的不是"文案好不好看"，而是**业务口径有没有真的进到 prompt 里**——
 * 直连模式没有语义层、没有下游兜底，模型写 SQL 时唯一能依靠的就是这段文字。
 */
class RawPromptAssemblerTest {

    private static final String SCHEMA = "\n### 表 ins_policy\n  - premium decimal\n";

    private static RegisteredTable table(List<ColumnDef> columns) {
        return new RegisteredTable("1", "ins_policy", "保单表",
                RegisteredTable.Mode.TABLE, null, true, columns);
    }

    private static Domain domain(List<Term> terms, List<ColumnDef> columns) {
        return new Domain("d1", "直连测试域", "1", "MYSQL5.7", true,
                List.of(table(columns)), List.of(), DomainPermission.from(null),
                terms, List.of(), Domain.Mode.RAW);
    }

    private static final Term VALID = new Term("有效", List.of("生效", "在保"),
            "保单状态为有效，即 status='有效'", Term.ApplyKind.NONE);

    @Test
    @DisplayName("★ 术语进 prompt —— 名字、近义词、释义都要在")
    void termsAreIncluded() {
        String p = RawPromptAssembler.assemble(
                domain(List.of(VALID), List.of()), List.of("ins_policy"), SCHEMA);

        assertThat(p)
                .contains("业务术语")
                .contains("有效")
                .contains("生效")
                .contains("在保")
                .contains("status='有效'");
    }

    @Test
    @DisplayName("没有术语时不留空标题")
    void noTermsNoHeading() {
        String p = RawPromptAssembler.assemble(
                domain(List.of(), List.of()), List.of("ins_policy"), SCHEMA);

        assertThat(p).doesNotContain("业务术语");
    }

    @Test
    @DisplayName("★ 列别名对照进 prompt —— 用户说「保费」，物理列是 premium")
    void aliasMappingIsIncluded() {
        List<ColumnDef> columns = List.of(
                new ColumnDef("premium", "decimal", Aggregation.SUM, "保费", true),
                new ColumnDef("region", "varchar", Aggregation.NONE, "区域", true));

        String p = RawPromptAssembler.assemble(
                domain(List.of(), columns), List.of("ins_policy"), SCHEMA);

        assertThat(p)
                .contains("业务名与物理列")
                .contains("保费=premium")
                .contains("区域=region");
    }

    @Test
    @DisplayName("别名与物理列同名时不列对照 —— 那只是噪音")
    void identicalAliasIsNotRepeated() {
        List<ColumnDef> columns = List.of(
                new ColumnDef("premium", "decimal", Aggregation.SUM, "premium", true));

        String p = RawPromptAssembler.assemble(
                domain(List.of(), columns), List.of("ins_policy"), SCHEMA);

        assertThat(p).doesNotContain("业务名与物理列");
    }

    @Test
    @DisplayName("★ 表级授权的规则与白名单必须在 —— 这是 prompt 侧的第一道说明")
    void allowlistAndRulesAreStated() {
        String p = RawPromptAssembler.assemble(
                domain(List.of(), List.of()), List.of("ins_policy", "ins_customer"), SCHEMA);

        assertThat(p)
                .contains("可访问的表")
                .contains("ins_policy、ins_customer")
                .contains("只能查这些")
                .contains("不要加库名前缀")
                .contains("只能写一条 SELECT");
    }

    @Test
    @DisplayName("表结构与采样值原样带上")
    void schemaIsAppended() {
        String p = RawPromptAssembler.assemble(
                domain(List.of(), List.of()), List.of("ins_policy"), SCHEMA);

        assertThat(p).contains(SCHEMA.trim());
    }

    @Test
    @DisplayName("★ 范例进 prompt，且必须点明「照结构、别照抄取值」")
    void examplesAreIncludedWithWarning() {
        SqlExample example = new SqlExample(1, 1, "各区域的保费合计是多少",
                "SELECT region, SUM(premium) FROM ins_policy GROUP BY region",
                "区域来自 region 列", "MANUAL", true);

        String p = RawPromptAssembler.assemble(
                domain(List.of(), List.of()), List.of("ins_policy"), SCHEMA, List.of(example));

        assertThat(p)
                .contains("相似问题的正确写法")
                .contains("各区域的保费合计是多少")
                .contains("GROUP BY region")
                .contains("区域来自 region 列")
                // 不写这句，模型会把范例里的具体日期/地区原样搬过来
                .contains("不要照抄 SQL 里的具体取值");
    }

    @Test
    @DisplayName("没有范例时不留空标题")
    void noExamplesNoHeading() {
        String p = RawPromptAssembler.assemble(
                domain(List.of(), List.of()), List.of("ins_policy"), SCHEMA, List.of());

        assertThat(p).doesNotContain("相似问题的正确写法");
    }
}

package com.liusongboyy.chatdata.engine.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 行规则编译器的测试。
 *
 * <p>重点不是"正常配置能编译"，而是**绕过手法被挡住**——
 * 字段名注入、运算符注入、LIKE 通配符逃逸、动态变量取不到时的行为。
 */
class RowRuleCompilerTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static DomainPermission perm(String rowRulesJson) throws Exception {
        JsonNode rules = M.readTree(rowRulesJson);
        JsonNode permJson = M.createObjectNode().set("rowRules", rules);
        return DomainPermission.from(permJson);
    }

    private static PermissionContext user(String... roles) {
        return PermissionContext.of("u1", Set.of(roles));
    }

    @Nested
    @DisplayName("字段名白名单 —— 防注入")
    class FieldWhitelist {

        @Test
        @DisplayName("普通字段名放行")
        void allowsPlainIdentifier() throws Exception {
            RowScope scope = RowRuleCompiler.compile(
                    perm("[{\"id\":1,\"role\":\"R\",\"field\":\"region\",\"op\":\"=\",\"value\":\"华东\"}]"),
                    user("R"));

            assertThat(scope).isInstanceOf(RowScope.Restricted.class);
            assertThat(((RowScope.Restricted) scope).predicate().sql()).isEqualTo("{col:region} = ?");
        }

        @Test
        @DisplayName("表名.列名 形式放行")
        void allowsQualifiedIdentifier() throws Exception {
            RowScope scope = RowRuleCompiler.compile(
                    perm("[{\"id\":1,\"role\":\"R\",\"field\":\"p.region\",\"op\":\"=\",\"value\":\"华东\"}]"),
                    user("R"));

            assertThat(((RowScope.Restricted) scope).predicate().sql()).isEqualTo("{col:p.region} = ?");
        }

        @Test
        @DisplayName("字段名里夹带 SQL 一律拒绝")
        void rejectsInjectionViaField() {
            List<String> attacks = List.of(
                    "region; DROP TABLE ins_policy --",
                    "region OR 1=1",
                    "1=1",
                    "region)",
                    "(SELECT 1)",
                    "region'",
                    "region--",
                    "*");

            for (String attack : attacks) {
                assertThatThrownBy(() -> RowRuleCompiler.compile(
                        perm("[{\"id\":1,\"role\":\"R\",\"field\":\"%s\",\"op\":\"=\",\"value\":\"x\"}]".formatted(attack)),
                        user("R")))
                        .as("字段名 %s 应当被拒绝", attack)
                        .isInstanceOf(RowRuleCompiler.RuleNotEvaluableException.class);
            }
        }
    }

    @Nested
    @DisplayName("运算符白名单")
    class OperatorWhitelist {

        @Test
        @DisplayName("中文与 SQL 两种写法都接受")
        void acceptsBothNotations() throws Exception {
            assertThat(sql("[{\"id\":1,\"role\":\"R\",\"field\":\"c\",\"op\":\"≠\",\"value\":\"x\"}]"))
                    .isEqualTo("{col:c} <> ?");
            assertThat(sql("[{\"id\":1,\"role\":\"R\",\"field\":\"c\",\"op\":\"!=\",\"value\":\"x\"}]"))
                    .isEqualTo("{col:c} <> ?");
            assertThat(sql("[{\"id\":1,\"role\":\"R\",\"field\":\"c\",\"op\":\"≥\",\"value\":\"1\"}]"))
                    .isEqualTo("{col:c} >= ?");
        }

        @Test
        @DisplayName("白名单外的运算符拒绝")
        void rejectsUnknownOperator() {
            assertThatThrownBy(() -> RowRuleCompiler.compile(
                    perm("[{\"id\":1,\"role\":\"R\",\"field\":\"c\",\"op\":\"; DROP TABLE t --\",\"value\":\"x\"}]"),
                    user("R")))
                    .isInstanceOf(RowRuleCompiler.RuleNotEvaluableException.class);

            assertThatThrownBy(() -> RowRuleCompiler.compile(
                    perm("[{\"id\":1,\"role\":\"R\",\"field\":\"c\",\"op\":\"REGEXP\",\"value\":\"x\"}]"),
                    user("R")))
                    .isInstanceOf(RowRuleCompiler.RuleNotEvaluableException.class);
        }

        private String sql(String json) throws Exception {
            RowScope s = RowRuleCompiler.compile(perm(json), user("R"));
            return ((RowScope.Restricted) s).predicate().sql();
        }
    }

    @Nested
    @DisplayName("取值参数化 —— 值绝不出现在 SQL 文本里")
    class ValueParameterization {

        @Test
        @DisplayName("静态值走参数，不进 SQL 文本")
        void valueGoesToParams() throws Exception {
            SqlPredicate p = predicate("[{\"id\":1,\"role\":\"R\",\"field\":\"region\",\"op\":\"=\",\"value\":\"华东\"}]");

            assertThat(p.sql()).doesNotContain("华东");
            assertThat(p.params()).containsExactly("华东");
        }

        @Test
        @DisplayName("恶意取值只影响参数，不改变 SQL 结构")
        void maliciousValueStaysInParams() throws Exception {
            String evil = "华东' OR '1'='1";
            SqlPredicate p = predicate(
                    "[{\"id\":1,\"role\":\"R\",\"field\":\"region\",\"op\":\"=\",\"value\":\"%s\"}]".formatted(evil));

            assertThat(p.sql()).isEqualTo("{col:region} = ?");
            assertThat(p.params()).containsExactly(evil);
        }

        @Test
        @DisplayName("IN 按逗号拆分，每项一个占位符")
        void inSplitsValues() throws Exception {
            SqlPredicate p = predicate(
                    "[{\"id\":1,\"role\":\"R\",\"field\":\"region\",\"op\":\"IN\",\"value\":\"华东, 华北 ,华南\"}]");

            assertThat(p.sql()).isEqualTo("{col:region} IN (?, ?, ?)");
            assertThat(p.params()).containsExactly("华东", "华北", "华南");
        }

        @Test
        @DisplayName("LIKE 的取值里带 % 会被转义，不能变成匹配全部")
        void likeEscapesWildcards() throws Exception {
            SqlPredicate p = predicate(
                    "[{\"id\":1,\"role\":\"R\",\"field\":\"name\",\"op\":\"包含\",\"value\":\"%\"}]");

            assertThat(p.sql()).contains("ESCAPE");
            // 取值里的 % 必须被转义成 \%，否则 "%" 会匹配全部行
            assertThat(String.valueOf(p.params().get(0))).isEqualTo("%\\%%");
        }

        private SqlPredicate predicate(String json) throws Exception {
            RowScope s = RowRuleCompiler.compile(perm(json), user("R"));
            return ((RowScope.Restricted) s).predicate();
        }
    }

    @Nested
    @DisplayName("动态变量取不到时：拒绝，不跳过（厂商此处是跳过并放行）")
    class DynamicVariables {

        @Test
        @DisplayName("取得到值时正常编译")
        void resolvesWhenPresent() throws Exception {
            PermissionContext ctx = new PermissionContext(
                    "u1", Set.of("R"), Map.of("orgCode", "华东"));

            RowScope scope = RowRuleCompiler.compile(
                    perm("[{\"id\":1,\"role\":\"R\",\"field\":\"org_code\",\"op\":\"=\",\"sysVar\":\"orgCode\",\"dynamic\":true}]"),
                    ctx);

            assertThat(((RowScope.Restricted) scope).predicate().params()).containsExactly("华东");
        }

        @Test
        @DisplayName("取不到值时抛异常，不得降级为「不过滤」")
        void failsWhenMissing() {
            assertThatThrownBy(() -> RowRuleCompiler.compile(
                    perm("[{\"id\":1,\"role\":\"R\",\"field\":\"org_code\",\"op\":\"=\",\"sysVar\":\"orgCode\",\"dynamic\":true}]"),
                    user("R")))
                    .isInstanceOf(RowRuleCompiler.RuleNotEvaluableException.class)
                    .hasMessageContaining("orgCode");
        }

        @Test
        @DisplayName("变量值为空白串等同于取不到")
        void blankValueCountsAsMissing() {
            PermissionContext ctx = new PermissionContext("u1", Set.of("R"), Map.of("orgCode", "   "));

            assertThatThrownBy(() -> RowRuleCompiler.compile(
                    perm("[{\"id\":1,\"role\":\"R\",\"field\":\"org_code\",\"op\":\"=\",\"sysVar\":\"orgCode\",\"dynamic\":true}]"),
                    ctx))
                    .isInstanceOf(RowRuleCompiler.RuleNotEvaluableException.class);
        }
    }

    @Nested
    @DisplayName("多角色合并")
    class MultiRole {

        @Test
        @DisplayName("多条规则按 OR 合并 —— 可见范围取并集")
        void mergesWithOr() throws Exception {
            RowScope scope = RowRuleCompiler.compile(
                    perm("""
                            [{"id":1,"role":"A","field":"region","op":"=","value":"华东"},
                             {"id":2,"role":"B","field":"region","op":"=","value":"华北"}]"""),
                    user("A", "B"));

            SqlPredicate p = ((RowScope.Restricted) scope).predicate();
            assertThat(p.sql()).isEqualTo("({col:region} = ?) OR ({col:region} = ?)");
            assertThat(p.params()).containsExactly("华东", "华北");
        }

        @Test
        @DisplayName("只取适用角色的规则，无关角色的规则不参与")
        void ignoresUnrelatedRoles() throws Exception {
            RowScope scope = RowRuleCompiler.compile(
                    perm("""
                            [{"id":1,"role":"A","field":"region","op":"=","value":"华东"},
                             {"id":2,"role":"OTHER","field":"region","op":"=","value":"华南"}]"""),
                    user("A"));

            SqlPredicate p = ((RowScope.Restricted) scope).predicate();
            assertThat(p.sql()).isEqualTo("{col:region} = ?");
            assertThat(p.params()).containsExactly("华东");
        }
    }

    @Nested
    @DisplayName("/** 组织树前缀匹配 */")
    class HierarchyPrefix {

        private PermissionContext ctxWith(String... paths) {
            return new PermissionContext("u1", Set.of("R"), Map.of("orgPath", List.of(paths)));
        }

        @Test
        @DisplayName("★ starts_with 只在右侧加通配符（层级前缀，不是包含匹配）")
        void startsWithIsPrefixNotContains() throws Exception {
            RowScope scope = RowRuleCompiler.compile(
                    perm("[{\"id\":1,\"role\":\"R\",\"field\":\"org_path\",\"op\":\"starts_with\",\"sysVar\":\"orgPath\",\"dynamic\":true}]"),
                    ctxWith("/1/10/"));

            SqlPredicate p = ((RowScope.Restricted) scope).predicate();

            assertThat(p.sql()).contains("LIKE").doesNotContain("'%");
            // 参数是「值 + %」，不是「% + 值 + %」
            assertThat(p.params()).containsExactly("/1/10/%");
        }

        @Test
        @DisplayName("★ 包含匹配会越权：/1/10% 不能写成 %/1/10%")
        void containsOperatorWouldLeakSiblings() throws Exception {
            // /1/100/ 与 /1/10/ 是不同子树。用「包含」时 '/1/10/' 不含在 '/1/100/' 里，
            // 但反过来 '/1/100' 会被 '%/1/10%' 匹配——这就是不能用 LIKE 的原因
            RowScope prefix = RowRuleCompiler.compile(
                    perm("[{\"id\":1,\"role\":\"R\",\"field\":\"org_path\",\"op\":\"starts_with\",\"sysVar\":\"orgPath\",\"dynamic\":true}]"),
                    ctxWith("/1/10/"));

            assertThat(((RowScope.Restricted) prefix).predicate().params())
                    .as("前缀匹配的参数以值开头，只对右侧加通配")
                    .containsExactly("/1/10/%");
        }

        @Test
        @DisplayName("多组织归属：多个前缀按 OR 合并")
        void multiplePathsMergeWithOr() throws Exception {
            RowScope scope = RowRuleCompiler.compile(
                    perm("[{\"id\":1,\"role\":\"R\",\"field\":\"org_path\",\"op\":\"starts_with\",\"sysVar\":\"orgPath\",\"dynamic\":true}]"),
                    ctxWith("/1/10/", "/1/12/"));

            SqlPredicate p = ((RowScope.Restricted) scope).predicate();

            assertThat(p.sql()).contains(" OR ");
            assertThat(p.params()).containsExactly("/1/10/%", "/1/12/%");
        }

        @Test
        @DisplayName("通配符必须转义：路径里的下划线不能放大范围")
        void escapesWildcardsInPath() throws Exception {
            RowScope scope = RowRuleCompiler.compile(
                    perm("[{\"id\":1,\"role\":\"R\",\"field\":\"org_path\",\"op\":\"starts_with\",\"sysVar\":\"orgPath\",\"dynamic\":true}]"),
                    ctxWith("/1/A_B/"));

            assertThat(((RowScope.Restricted) scope).predicate().params())
                    .as("未转义的 _ 在 LIKE 里匹配任意单字符，会跨到 /1/AXB/ 去")
                    .containsExactly("/1/A\\_B/%");
        }

        @Test
        @DisplayName("路径为空或空白时拒绝，不得放行")
        void rejectsBlankPaths() {
            assertThatThrownBy(() -> RowRuleCompiler.compile(
                    perm("[{\"id\":1,\"role\":\"R\",\"field\":\"org_path\",\"op\":\"starts_with\",\"sysVar\":\"orgPath\",\"dynamic\":true}]"),
                    ctxWith("/1/10/", "  ")))
                    .isInstanceOf(RowRuleCompiler.RuleNotEvaluableException.class);
        }

        @Test
        @DisplayName("单值字符串（老格式）仍可作为前缀使用")
        void acceptsSingleStringValue() throws Exception {
            PermissionContext ctx = new PermissionContext(
                    "u1", Set.of("R"), Map.of("orgPath", "/1/10/"));

            RowScope scope = RowRuleCompiler.compile(
                    perm("[{\"id\":1,\"role\":\"R\",\"field\":\"org_path\",\"op\":\"starts_with\",\"sysVar\":\"orgPath\",\"dynamic\":true}]"),
                    ctx);

            assertThat(((RowScope.Restricted) scope).predicate().params()).containsExactly("/1/10/%");
        }
    }
}

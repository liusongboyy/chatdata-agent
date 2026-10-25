package com.liusongboyy.chatdata.engine.startup;

import com.liusongboyy.chatdata.engine.datasource.DatasourceDef;
import com.liusongboyy.chatdata.engine.permission.AssistantPermission;
import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;
import com.liusongboyy.chatdata.engine.semantic.Assistant;
import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.TableJoin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动校验规则的测试。**纯逻辑**，不启 Spring、不连库。
 */
class EngineConfigValidatorTest {

    private static DatasourceDef ds(long id, String dbType) {
        return new DatasourceDef(id, "库" + id, dbType, "com.mysql.cj.jdbc.Driver",
                "jdbc:mysql://127.0.0.1:3306/x", "root", true);
    }

    private static RegisteredTable policy(boolean enabled) {
        return new RegisteredTable("1", "ins_policy", "保单表", RegisteredTable.Mode.TABLE, null, enabled,
                List.of(new ColumnDef("premium", "decimal", Aggregation.SUM, "保费", true),
                        new ColumnDef("region", "varchar", Aggregation.NONE, "区域", true)));
    }

    private static RegisteredTable emptyColumns() {
        return new RegisteredTable("2", "ins_empty", "空表", RegisteredTable.Mode.TABLE, null, true,
                List.of(new ColumnDef("c", "int", Aggregation.SUM, "指标", false)));
    }

    /** 一个各项都合规的域，供各测试在此基础上改坏某一项。 */
    private static Domain goodDomain(DomainPermission perm, List<RegisteredTable> tables, List<TableJoin> joins) {
        return new Domain("1", "保险分析域", "1", "MYSQL5.7", true, tables, joins, perm, List.of(), List.of());
    }

    private static DomainPermission granted() {
        return permOf("{\"v\":1,\"grantRoles\":[\"区域经理\"],\"unrestrictedRoles\":[\"区域经理\"]}");
    }

    /** 从 JSON 构造域权限，把受检异常收在一处。 */
    private static DomainPermission permOf(String json) {
        try {
            return DomainPermission.from(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException("测试夹具 JSON 写错了：" + json, e);
        }
    }

    private static DomainPermission noGrant() {
        return DomainPermission.from(null);
    }

    private static final Map<Long, DatasourceDef> DATASOURCES = Map.of(1L, ds(1L, "MYSQL5.7"));

    @Nested
    @DisplayName("合规配置")
    class Valid {

        @Test
        @DisplayName("各项都合规则无违规")
        void fullyValid() {
            Domain d = goodDomain(granted(), List.of(policy(true)), List.of());

            assertThat(EngineConfigValidator.validate(d, DATASOURCES)).isEmpty();
        }
    }

    @Nested
    @DisplayName("授权 —— ADR-0002 的 R1")
    class Grants {

        @Test
        @DisplayName("★ 未设授权 → 违规（厂商此处对所有已认证用户开放）")
        void noGrantIsViolation() {
            Domain d = goodDomain(noGrant(), List.of(policy(true)), List.of());

            List<EngineConfigViolation> v = EngineConfigValidator.validate(d, DATASOURCES);

            assertThat(v).hasSize(1);
            assertThat(v.get(0).category()).isEqualTo(EngineConfigViolation.Category.NO_GRANT);
            assertThat(v.get(0).message()).contains("unrestrictedRoles");
        }

        @Test
        @DisplayName("isPublic=true 视为已授权 —— 公开是显式声明的")
        void publicCounts() {
            DomainPermission pub = permOf("{\"isPublic\":true}");
            Domain d = goodDomain(pub, List.of(policy(true)), List.of());

            assertThat(EngineConfigValidator.validate(d, DATASOURCES)).isEmpty();
        }

        @Test
        @DisplayName("只配 grantUsers 也算已授权")
        void grantUsersCounts() {
            Domain d = goodDomain(permOf("{\"grantUsers\":[\"u1\"]}"),
                    List.of(policy(true)), List.of());

            assertThat(EngineConfigValidator.validate(d, DATASOURCES)).isEmpty();
        }
    }

    @Nested
    @DisplayName("数据源")
    class Datasource {

        @Test
        @DisplayName("绑定的数据源不存在 → 违规")
        void missingDatasource() {
            Domain d = new Domain("1", "域", "999", null, true,
                    List.of(policy(true)), List.of(), granted(), List.of(), List.of());

            List<EngineConfigViolation> v = EngineConfigValidator.validate(d, DATASOURCES);

            assertThat(v).extracting(EngineConfigViolation::category)
                    .contains(EngineConfigViolation.Category.DATASOURCE_UNUSABLE);
        }

        @Test
        @DisplayName("★ 数据源类型没有方言 → 违规（否则要等到渲染时才失败）")
        void unsupportedDialect() {
            // DatasourceDef 构造器刻意不校验 dbType —— 见该 record 的说明。
            // 若它抛异常，MyBatis 的整条列表查询会失败，一行坏数据就炸掉整份启动报告
            DatasourceDef hive = ds(2L, "HIVE");

            List<EngineConfigViolation> v = EngineConfigValidator.validateDatasource(hive);

            assertThat(v).extracting(EngineConfigViolation::category)
                    .contains(EngineConfigViolation.Category.DATASOURCE_UNUSABLE);
            assertThat(v.get(0).message()).contains("HIVE");
        }

        @Test
        @DisplayName("★ 引用一个类型不支持的数据源时，域层也报违规")
        void domainReferencingUnsupportedDatasource() {
            Map<Long, DatasourceDef> dss = Map.of(2L, ds(2L, "HIVE"));
            Domain d = new Domain("1", "域", "2", "HIVE", true,
                    List.of(policy(true)), List.of(), granted(), List.of(), List.of());

            List<EngineConfigViolation> v = EngineConfigValidator.validate(d, dss);

            assertThat(v).extracting(EngineConfigViolation::category)
                    .contains(EngineConfigViolation.Category.DATASOURCE_UNUSABLE);
        }

        @Test
        @DisplayName("数据源缺名称或缺 URL → 违规")
        void datasourceMissingFields() {
            DatasourceDef bad = new DatasourceDef(3L, "  ", "MYSQL5.7", "d", "", "u", true);

            List<EngineConfigViolation> v = EngineConfigValidator.validateDatasource(bad);

            assertThat(v).extracting(EngineConfigViolation::category)
                    .containsOnly(EngineConfigViolation.Category.DATASOURCE_UNUSABLE);
            assertThat(v).hasSize(2);   // 缺名称 + 缺 URL，两条都要报出来
        }

        @Test
        @DisplayName("未绑数据源（用应用默认库）不算违规")
        void nullDatasourceAllowed() {
            Domain d = new Domain("1", "域", null, null, true,
                    List.of(policy(true)), List.of(), granted(), List.of(), List.of());

            assertThat(EngineConfigValidator.validate(d, DATASOURCES)).isEmpty();
        }
    }

    @Nested
    @DisplayName("表与字段")
    class TablesAndFields {

        @Test
        @DisplayName("没有可见表 → 违规")
        void noVisibleTable() {
            Domain d = goodDomain(granted(), List.of(policy(false)), List.of());

            List<EngineConfigViolation> v = EngineConfigValidator.validate(d, DATASOURCES);

            assertThat(v).hasSize(1);
            assertThat(v.get(0).category()).isEqualTo(EngineConfigViolation.Category.NO_VISIBLE_TABLE);
        }

        @Test
        @DisplayName("所有列都被关掉 → 违规")
        void noEnabledField() {
            Domain d = goodDomain(granted(), List.of(emptyColumns()), List.of());

            List<EngineConfigViolation> v = EngineConfigValidator.validate(d, DATASOURCES);

            assertThat(v).extracting(EngineConfigViolation::category)
                    .contains(EngineConfigViolation.Category.NO_FIELD);
        }

        @Test
        @DisplayName("★ 没有可见表时不再报「没有字段」 —— 避免同一根因刷两条")
        void noVisibleTableShortCircuits() {
            Domain d = goodDomain(granted(), List.of(policy(false)), List.of());

            List<EngineConfigViolation> v = EngineConfigValidator.validate(d, DATASOURCES);

            assertThat(v).extracting(EngineConfigViolation::category)
                    .doesNotContain(EngineConfigViolation.Category.NO_FIELD);
        }
    }

    @Nested
    @DisplayName("表关联")
    class Joins {

        @Test
        @DisplayName("★ 关联引用不存在的注册表 → 违规")
        void brokenJoinRef() {
            // 关联引用了 id=9，但域内只有 id=1 的表。
            // 这种配置的失败形态很刁钻：单表问题照常可用，多表问题才失败——
            // **部分可用比全不可用更难发现**
            TableJoin broken = new TableJoin(1L, "1", "9", "a", "b", TableJoin.JoinType.INNER);
            Domain d = goodDomain(granted(), List.of(policy(true)), List.of(broken));

            List<EngineConfigViolation> v = EngineConfigValidator.validate(d, DATASOURCES);

            assertThat(v).hasSize(1);
            assertThat(v.get(0).category()).isEqualTo(EngineConfigViolation.Category.BROKEN_JOIN_REF);
            assertThat(v.get(0).message()).contains("left=1").contains("right=9");
        }

        @Test
        @DisplayName("关联两端都在域内则合规")
        void validJoin() {
            RegisteredTable customer = new RegisteredTable("2", "ins_customer", "客户表",
                    RegisteredTable.Mode.TABLE, null, true,
                    List.of(new ColumnDef("id", "bigint", Aggregation.NONE, "客户ID", true)));
            TableJoin ok = new TableJoin(1L, "1", "2", "cust_id", "id", TableJoin.JoinType.INNER);
            Domain d = goodDomain(granted(), List.of(policy(true), customer), List.of(ok));

            assertThat(EngineConfigValidator.validate(d, DATASOURCES)).isEmpty();
        }
    }

    /**
     * 助理能不能真正被用起来。
     *
     * <p>这一组来自一次真实踩坑：`工单问数助手` 的授权里有角色 `管理员`，
     * 而它绑定的 `智能工单域` 只授权 `区域经理`/`分析师`。于是管理员**看得见助理、
     * 点进去被拒**，提示还是"您没有该数据域的访问权限"——完全指不到配置上。
     */
    @Nested
    @DisplayName("助理可达性 —— 看得见却用不了")
    class AssistantReachability {

        private static Assistant assistant(String domainId, String... grantRoles) {
            return new Assistant("2", "工单问数助手", "📊", null, "你好", domainId, List.of(),
                    true, Assistant.Status.ONLINE, List.of(),
                    new AssistantPermission(true, Set.of(grantRoles), Set.of()));
        }

        private static Domain domainGranting(String... roles) {
            return new Domain("1040", "智能工单域", "1", "MYSQL5.7", true,
                    List.of(policy(true)), List.of(),
                    DomainPermission.from(json("""
                            {"v":1,"grantRoles":%s}""".formatted(
                            "[" + java.util.Arrays.stream(roles)
                                    .map(r -> "\"" + r + "\"")
                                    .collect(java.util.stream.Collectors.joining(",")) + "]"))),
                    List.of(), List.of());
        }

        private static com.fasterxml.jackson.databind.JsonNode json(String s) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private static List<EngineConfigViolation> check(Assistant a, Domain... domains) {
            Map<String, Domain> byId = new java.util.LinkedHashMap<>();
            for (Domain d : domains) {
                byId.put(d.id(), d);
            }
            return EngineConfigValidator.validateAssistantReachability(a, byId);
        }

        @Test
        @DisplayName("★ 助理授权了域没授权的角色 → WARNING，并说清「授予助理不等于授予域」")
        void roleGrantedOnAssistantButNotOnDomain() {
            List<EngineConfigViolation> vs = check(
                    assistant("1040", "区域经理", "管理员"),
                    domainGranting("区域经理", "分析师"));

            assertThat(vs).singleElement().satisfies(v -> {
                assertThat(v.category()).isEqualTo(EngineConfigViolation.Category.ASSISTANT_DEAD_END);
                assertThat(v.category().isFatal())
                        .as("配置不一致，不泄露数据，不该拦住启动")
                        .isFalse();
                assertThat(v.message())
                        .contains("管理员")
                        .contains("智能工单域")
                        .contains("授予助理不等于授予域");
            });
        }

        @Test
        @DisplayName("角色都被域覆盖 → 零告警（这正是修好之后的样子）")
        void coveredRolesProduceNothing() {
            assertThat(check(assistant("1040", "区域经理", "管理员"),
                    domainGranting("区域经理", "分析师", "管理员"))).isEmpty();
        }

        @Test
        @DisplayName("域对所有人开放时怎么授权都到得了，不报")
        void publicDomainIsAlwaysReachable() {
            Domain open = new Domain("1040", "智能工单域", "1", "MYSQL5.7", true,
                    List.of(policy(true)), List.of(),
                    DomainPermission.from(json("{\"v\":1,\"isPublic\":true}")),
                    List.of(), List.of());

            assertThat(check(assistant("1040", "随便什么角色"), open)).isEmpty();
        }

        @Test
        @DisplayName("没绑域 → 报，任何人经它提问都会失败")
        void noDomain() {
            assertThat(check(assistant(null, "区域经理")))
                    .singleElement()
                    .satisfies(v -> assertThat(v.message()).contains("没有绑定数据域"));
        }

        @Test
        @DisplayName("绑的域不存在或未启用 → 报")
        void missingDomain() {
            assertThat(check(assistant("999", "区域经理"), domainGranting("区域经理")))
                    .singleElement()
                    .satisfies(v -> assertThat(v.message()).contains("999").contains("不存在或未启用"));
        }

        @Test
        @DisplayName("★ 开着访问控制却一个人都没授权 → 谁都用不了，助理在界面上根本不会出现")
        void enabledButNobodyGranted() {
            Assistant orphan = new Assistant("2", "孤儿助理", null, null, null, "1040", List.of(),
                    false, Assistant.Status.ONLINE, List.of(),
                    new AssistantPermission(true, Set.of(), Set.of()));

            assertThat(check(orphan, domainGranting("区域经理")))
                    .singleElement()
                    .satisfies(v -> assertThat(v.message()).contains("任何人都看不到"));
        }

        @Test
        @DisplayName("未上线的助理不报 —— 草稿写成什么样都不该打扰人")
        void offlineAssistantIsSkipped() {
            Assistant draft = new Assistant("2", "草稿助理", null, null, null, null, List.of(),
                    false, Assistant.Status.DRAFT, List.of(),
                    new AssistantPermission(true, Set.of("不存在"), Set.of()));

            assertThat(check(draft, domainGranting("区域经理"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("派生指标")
    class Derived {

        private RegisteredTable claimTable(ColumnDef derived) {
            return new RegisteredTable("2", "ins_claim", "理赔表", RegisteredTable.Mode.TABLE, null, true,
                    List.of(new ColumnDef("settle_amount", "decimal", Aggregation.SUM, "赔付金额", true),
                            new ColumnDef("apply_amount", "decimal", Aggregation.SUM, "申请金额", true),
                            derived));
        }

        private List<EngineConfigViolation> check(ColumnDef derived) {
            Domain domain = goodDomain(granted(), List.of(claimTable(derived)), List.of());
            return EngineConfigValidator.validate(domain, DATASOURCES);
        }

        @Test
        @DisplayName("配置正确的比值指标 → 不报")
        void validRatioPasses() {
            assertThat(check(new ColumnDef(null, "decimal", Aggregation.NONE, "赔付率", true,
                    new com.liusongboyy.chatdata.engine.semantic.DerivedMetric(
                            com.liusongboyy.chatdata.engine.semantic.DerivedMetric.Kind.RATIO,
                            "赔付金额", "申请金额", null, null, null))))
                    .isEmpty();
        }

        @Test
        @DisplayName("★ 输入名写错 → 在启动时报，而不是等用户提问")
        void brokenInputReported() {
            assertThat(check(new ColumnDef(null, "decimal", Aggregation.NONE, "赔付率", true,
                    new com.liusongboyy.chatdata.engine.semantic.DerivedMetric(
                            com.liusongboyy.chatdata.engine.semantic.DerivedMetric.Kind.RATIO,
                            "赔付金额", "不存在的列", null, null, null))))
                    .singleElement()
                    .satisfies(v -> assertThat(v.category())
                            .isEqualTo(EngineConfigViolation.Category.BROKEN_DERIVED_METRIC))
                    .satisfies(v -> assertThat(v.message())
                            .contains("赔付率").contains("不存在的列"));
        }

        @Test
        @DisplayName("列被关掉 → 不报：模型本来也问不到它")
        void disabledDerivedSkipped() {
            assertThat(check(new ColumnDef(null, "decimal", Aggregation.NONE, "赔付率", false,
                    new com.liusongboyy.chatdata.engine.semantic.DerivedMetric(
                            com.liusongboyy.chatdata.engine.semantic.DerivedMetric.Kind.RATIO,
                            "赔付金额", "不存在的列", null, null, null))))
                    .noneMatch(v -> v.category() == EngineConfigViolation.Category.BROKEN_DERIVED_METRIC);
        }
    }
}

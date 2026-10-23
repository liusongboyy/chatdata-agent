package com.liusongboyy.chatdata.engine.semantic;

import com.liusongboyy.chatdata.engine.permission.PermissionContext;
import com.liusongboyy.chatdata.engine.permission.PermissionDecision;
import com.liusongboyy.chatdata.engine.permission.PermissionGuard;
import com.liusongboyy.chatdata.engine.permission.RowScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 加载器的测试。**不连库**——直接喂行对象，验证解析与装配。
 *
 * <p>与真库的联调在 {@code SemanticConfigPersistenceTest} 里，那里验证的是
 * "Mapper 能不能正确映射"；这里验证的是"行对象变成 Domain 的过程对不对"。
 * 分开是因为前者的失败原因在 MyBatis，后者的失败原因在解析逻辑，混在一起难定位。
 */
class DomainLoaderTest {

    private static DomainRow domainRow(String permJson) {
        return new DomainRow(1L, "保险分析域", 1L, "enabled", null, permJson);
    }

    private static DomainTableRow policyRow(String columnsJson) {
        return new DomainTableRow(1L, 1L, "ins_policy", "保单表",
                "TABLE", null, columnsJson, true);
    }

    private static final String VALID_COLUMNS = """
            [{"col":"premium","type":"decimal","agg":"SUM","alias":"保费","enabled":true},
             {"col":"region","type":"varchar","agg":"","alias":"区域","enabled":true}]""";

    private static final String VALID_PERM = """
            {"v":1,"grantRoles":["区域经理"],"unrestrictedRoles":["区域经理"]}""";

    @Nested
    @DisplayName("正常装配")
    class HappyPath {

        @Test
        @DisplayName("列定义解析成 ColumnDef，聚合方式识别正确")
        void parsesColumns() {
            Domain d = DomainLoader.load(domainRow(VALID_PERM), List.of(policyRow(VALID_COLUMNS)), "MYSQL5.7");

            assertThat(d.tables()).hasSize(1);
            List<ColumnDef> cols = d.tables().get(0).columns();
            assertThat(cols).hasSize(2);
            assertThat(cols.get(0).col()).isEqualTo("premium");
            assertThat(cols.get(0).agg()).isEqualTo(Aggregation.SUM);
            assertThat(cols.get(0).isMetric()).isTrue();
            assertThat(cols.get(1).agg()).isEqualTo(Aggregation.NONE);
        }

        @Test
        @DisplayName("dbType 由调用方注入 —— 渲染器要用它取方言")
        void carriesDbType() {
            Domain d = DomainLoader.load(domainRow(VALID_PERM), List.of(policyRow(VALID_COLUMNS)), "POSTGRESQL");

            assertThat(d.dbType()).isEqualTo("POSTGRESQL");
        }

        @Test
        @DisplayName("加载出的 Domain 能直接构造 SemanticModel 并解析字段")
        void producesUsableModel() {
            Domain d = DomainLoader.load(domainRow(VALID_PERM), List.of(policyRow(VALID_COLUMNS)), "MYSQL5.7");
            SemanticModel m = new SemanticModel(d);

            assertThat(m.resolveMetric("保费").column()).isEqualTo("premium");
            assertThat(m.resolveDimension("区域").column()).isEqualTo("region");
        }

        @Test
        @DisplayName("空 columns_json 得到无列的注册表，不抛异常")
        void emptyColumns() {
            Domain d = DomainLoader.load(domainRow(VALID_PERM), List.of(policyRow(null)), "MYSQL5.7");

            assertThat(d.tables().get(0).columns()).isEmpty();
        }
    }

    @Nested
    @DisplayName("权限解析 —— 缺配置即拒绝")
    class PermissionParsing {

        @Test
        @DisplayName("正常解析授权与行规则")
        void parsesPermission() {
            String perm = """
                    {"v":1,"grantRoles":["区域经理"],
                     "rowRules":[{"id":1,"role":"区域经理","field":"region","op":"=","value":"华东"}]}""";

            Domain d = DomainLoader.load(domainRow(perm), List.of(policyRow(VALID_COLUMNS)), "MYSQL5.7");
            PermissionDecision decision = PermissionGuard.check(
                    null, d.permission(), PermissionContext.of("u1", Set.of("区域经理")));

            assertThat(decision).isInstanceOfSatisfying(PermissionDecision.Allowed.class,
                    x -> assertThat(x.rowScope()).isInstanceOf(RowScope.Restricted.class));
        }

        @Test
        @DisplayName("★ 没有 perm_json 时按拒绝处理 —— 厂商此处对所有已认证用户开放")
        void missingPermissionDenies() {
            Domain d = DomainLoader.load(domainRow(null), List.of(policyRow(VALID_COLUMNS)), "MYSQL5.7");

            PermissionDecision decision = PermissionGuard.check(
                    null, d.permission(), PermissionContext.of("u1", Set.of("任何人")));

            assertThat(decision.isDenied())
                    .as("没有权限配置 = 没有任何授权，必须失败关闭")
                    .isTrue();
        }

        @Test
        @DisplayName("perm_json 格式坏掉时抛异常并带域上下文")
        void malformedPermissionThrows() {
            assertThatThrownBy(() -> DomainLoader.load(
                    domainRow("{不是合法 JSON"), List.of(policyRow(VALID_COLUMNS)), "MYSQL5.7"))
                    .isInstanceOf(SemanticException.class)
                    .hasMessageContaining("保险分析域")
                    .hasMessageContaining("id=1");
        }
    }

    @Nested
    @DisplayName("列定义校验 —— 取值非法必须抛，不能静默降级")
    class ColumnValidation {

        @Test
        @DisplayName("★ 未知聚合方式抛异常 —— 回落 NONE 会把指标当维度渲染")
        void unknownAggregationThrows() {
            String cols = """
                    [{"col":"premium","type":"decimal","agg":"MEDIAN","alias":"保费"}]""";

            assertThatThrownBy(() -> DomainLoader.load(
                    domainRow(VALID_PERM), List.of(policyRow(cols)), "MYSQL5.7"))
                    .isInstanceOf(SemanticException.class)
                    .hasMessageContaining("MEDIAN");
        }

        @Test
        @DisplayName("★ 列缺 col 名抛异常并指出是哪个域哪张表")
        void missingColumnNameThrows() {
            String cols = """
                    [{"type":"decimal","agg":"SUM","alias":"保费"}]""";

            assertThatThrownBy(() -> DomainLoader.load(
                    domainRow(VALID_PERM), List.of(policyRow(cols)), "MYSQL5.7"))
                    .isInstanceOf(SemanticException.class)
                    .hasMessageContaining("保单表")
                    .hasMessageContaining("ins_policy");
        }

        @Test
        @DisplayName("enabled 缺省视为可见 —— 与助手权限的缺省方向相反")
        void enabledDefaultsToTrue() {
            String cols = """
                    [{"col":"premium","type":"decimal","agg":"SUM","alias":"保费"}]""";

            Domain d = DomainLoader.load(domainRow(VALID_PERM), List.of(policyRow(cols)), "MYSQL5.7");

            assertThat(d.tables().get(0).columns().get(0).enabled()).isTrue();
        }

        @Test
        @DisplayName("enabled=false 的列不可解析")
        void disabledColumnNotResolvable() {
            String cols = """
                    [{"col":"premium","type":"decimal","agg":"SUM","alias":"保费","enabled":false}]""";

            Domain d = DomainLoader.load(domainRow(VALID_PERM), List.of(policyRow(cols)), "MYSQL5.7");
            SemanticModel m = new SemanticModel(d);

            assertThatThrownBy(() -> m.resolveMetric("保费"))
                    .isInstanceOf(SemanticException.class);
        }
    }

    @Nested
    @DisplayName("注册表模式")
    class TableMode {

        @Test
        @DisplayName("mode=SQL 且没有 sql_text 时抛异常")
        void sqlModeRequiresSqlText() {
            DomainTableRow bad = new DomainTableRow(1L, 1L, "v1", "虚拟表",
                    "SQL", null, VALID_COLUMNS, true);

            assertThatThrownBy(() -> DomainLoader.load(domainRow(VALID_PERM), List.of(bad), "MYSQL5.7"))
                    .isInstanceOf(SemanticException.class)
                    .hasMessageContaining("SQL");
        }

        @Test
        @DisplayName("mode 取值非法时抛异常")
        void invalidModeThrows() {
            DomainTableRow bad = new DomainTableRow(1L, 1L, "t", "T",
                    "VIEW", null, VALID_COLUMNS, true);

            assertThatThrownBy(() -> DomainLoader.load(domainRow(VALID_PERM), List.of(bad), "MYSQL5.7"))
                    .isInstanceOf(SemanticException.class)
                    .hasMessageContaining("VIEW");
        }

        @Test
        @DisplayName("mode 缺省视为 TABLE")
        void modeDefaultsToTable() {
            DomainTableRow row = new DomainTableRow(1L, 1L, "ins_policy", "保单表",
                    null, null, VALID_COLUMNS, true);

            Domain d = DomainLoader.load(domainRow(VALID_PERM), List.of(row), "MYSQL5.7");

            assertThat(d.tables().get(0).mode()).isEqualTo(RegisteredTable.Mode.TABLE);
        }
    }

    @Nested
    @DisplayName("表关联解析")
    class JoinParsing {

        @Test
        @DisplayName("LEFT JOIN 保持方向")
        void parsesLeftJoin() {
            DomainRow row = new DomainRow(1L, "域", 1L, "enabled",
                    """
                    [{"id":1,"left":"t1","right":"t2","leftCol":"cust_id","rightCol":"id","type":"LEFT JOIN"}]""",
                    VALID_PERM);

            Domain d = DomainLoader.load(row, List.of(), "MYSQL5.7");

            assertThat(d.joins()).hasSize(1);
            TableJoin j = d.joins().get(0);
            assertThat(j.leftTableId()).isEqualTo("t1");
            assertThat(j.type()).isEqualTo(TableJoin.JoinType.LEFT);
        }

        @Test
        @DisplayName("★ RIGHT JOIN 归一化成交换操作数的 LEFT JOIN")
        void rightJoinNormalized() {
            DomainRow row = new DomainRow(1L, "域", 1L, "enabled",
                    """
                    [{"id":1,"left":"t1","right":"t2","leftCol":"a","rightCol":"b","type":"RIGHT JOIN"}]""",
                    VALID_PERM);

            Domain d = DomainLoader.load(row, List.of(), "MYSQL5.7");
            TableJoin j = d.joins().get(0);

            assertThat(j.leftTableId()).as("RIGHT JOIN t1 t2 ≡ LEFT JOIN t2 t1").isEqualTo("t2");
            assertThat(j.rightTableId()).isEqualTo("t1");
            assertThat(j.leftColumn()).isEqualTo("b");
            assertThat(j.rightColumn()).isEqualTo("a");
        }

        @Test
        @DisplayName("★ 未知关联类型抛异常 —— 静默当 INNER 会给出错误行数")
        void unknownJoinTypeThrows() {
            DomainRow row = new DomainRow(1L, "域", 1L, "enabled",
                    """
                    [{"id":1,"left":"t1","right":"t2","leftCol":"a","rightCol":"b","type":"FULL OUTER JOIN"}]""",
                    VALID_PERM);

            assertThatThrownBy(() -> DomainLoader.load(row, List.of(), "MYSQL5.7"))
                    .isInstanceOf(SemanticException.class)
                    .hasMessageContaining("FULL OUTER");
        }

        @Test
        @DisplayName("joins_json 为空或非数组时不报错")
        void emptyJoinsTolerated() {
            assertThat(DomainLoader.load(domainRow(VALID_PERM), List.of(), "MYSQL5.7").joins()).isEmpty();
            assertThat(DomainLoader.load(
                    new DomainRow(1L, "域", 1L, "enabled", "{}", VALID_PERM),
                    List.of(), "MYSQL5.7").joins()).isEmpty();
        }
    }
}

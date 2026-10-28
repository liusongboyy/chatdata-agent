package com.liusongboyy.chatdata.engine.nlsql;

import com.liusongboyy.chatdata.engine.datasource.dialect.SqlDialect;
import com.liusongboyy.chatdata.engine.datasource.dialect.SqlDialects;
import com.liusongboyy.chatdata.engine.permission.AssistantPermission;
import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import com.liusongboyy.chatdata.engine.permission.PermissionContext;
import com.liusongboyy.chatdata.engine.permission.RowScope;
import com.liusongboyy.chatdata.engine.permission.PermissionDecision;
import com.liusongboyy.chatdata.engine.permission.PermissionGuard;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;
import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.SemanticException;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import com.liusongboyy.chatdata.engine.semantic.TableJoin;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 渲染器的测试。
 *
 * <p>重点在**权限与脱敏是否真的落进了 SQL**——这是本类存在的首要理由。
 * 语法正确但权限没生效的 SQL，比渲染失败危险得多。
 */
class AskSqlRendererTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    // ── 夹具：贴近真实保险业务表 ──

    private static SemanticModel model(DomainPermission perm) {
        RegisteredTable policy = new RegisteredTable("tbl-policy", "ins_policy", "保单表",
                RegisteredTable.Mode.TABLE, null, true, List.of(
                new ColumnDef("premium", "decimal", Aggregation.SUM, "保费", true),
                new ColumnDef("policy_no", "varchar", Aggregation.COUNT, "保单数", true),
                new ColumnDef("region", "varchar", Aggregation.NONE, "区域", true),
                new ColumnDef("status", "varchar", Aggregation.NONE, "状态", true),
                new ColumnDef("effect_date", "date", Aggregation.NONE, "生效日期", true)));

        Domain domain = new Domain("d1", "保险分析域", "ds1", "MYSQL5.7", true,
                List.of(policy), null, perm, List.of(), List.of());
        return new SemanticModel(domain);
    }

    /** 无任何权限约束的域：给分析师的显式全量。 */
    private static DomainPermission openPermission() {
        return permOf("""
                {"v":1,"grantRoles":["分析师"],"unrestrictedRoles":["分析师"]}""");
    }

    /** 从 JSON 构造域权限。把受检异常收在一处，免得每个夹具都写 try。 */
    private static DomainPermission permOf(String json) {
        try {
            return DomainPermission.from(M.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException("测试夹具的 JSON 写错了：" + json, e);
        }
    }

    private static PermissionDecision allowed(DomainPermission perm, String... roles) {
        PermissionDecision d = PermissionGuard.check(
                null, perm, PermissionContext.of("u1", Set.of(roles)));
        assertThat(d.isAllowed()).as("夹具本身应当放行").isTrue();
        return d;
    }

    // ── DSL 夹具 ──

    private static AskDsl dsl(List<String> metrics, List<String> dims,
                              AskDsl.TimeRange time, List<AskDsl.Filter> filters) {
        return new AskDsl(metrics, dims, time, filters);
    }

    @Nested
    @DisplayName("基础渲染")
    class BasicRendering {

        @Test
        @DisplayName("单指标无维度 —— 全表聚合")
        void singleMetric() {
            DomainPermission perm = openPermission();
            SemanticModel m = model(perm);
            SqlDialect dialect = SqlDialects.of("MYSQL5.7");

            RenderedSql r = AskSqlRenderer.render(
                    dsl(List.of("保费"), List.of(), null, List.of()),
                    m, dialect, allowed(perm, "分析师"), TODAY);

            assertThat(r.sql()).isEqualTo(
                    "SELECT SUM(`t1`.`premium`) AS `保费` FROM `ins_policy` `t1` LIMIT 1000");
            assertThat(r.params()).isEmpty();
            assertThat(r.columnNames()).containsExactly("保费");
            assertThat(r.maskedColumnNames()).isEmpty();
        }

        @Test
        @DisplayName("指标 + 维度 —— 生成 GROUP BY，维度在 SELECT 与 GROUP BY 同时出现")
        void metricWithDimension() {
            DomainPermission perm = openPermission();
            RenderedSql r = AskSqlRenderer.render(
                    dsl(List.of("保费"), List.of("区域"), null, List.of()),
                    model(perm), SqlDialects.of("MYSQL5.7"), allowed(perm, "分析师"), TODAY);

            assertThat(r.sql())
                    .contains("`t1`.`region` AS `区域`")
                    .contains("SUM(`t1`.`premium`) AS `保费`")
                    .contains("GROUP BY `t1`.`region`");
            assertThat(r.columnNames()).containsExactly("区域", "保费");
        }

        @Test
        @DisplayName("筛选条件 —— 值走参数，不进 SQL 文本")
        void filterIsParameterized() {
            DomainPermission perm = openPermission();
            RenderedSql r = AskSqlRenderer.render(
                    dsl(List.of("保费"), List.of(), null,
                            List.of(new AskDsl.Filter("状态", "=", "生效"))),
                    model(perm), SqlDialects.of("MYSQL5.7"), allowed(perm, "分析师"), TODAY);

            assertThat(r.sql()).contains("`t1`.`status` = ?");
            assertThat(r.sql()).doesNotContain("生效");
            assertThat(r.params()).containsExactly("生效");
        }

        @Test
        @DisplayName("多个筛选条件按 AND 连接，参数顺序与占位符一致")
        void multipleFilters() {
            DomainPermission perm = openPermission();
            RenderedSql r = AskSqlRenderer.render(
                    dsl(List.of("保费"), List.of(), null, List.of(
                            new AskDsl.Filter("区域", "=", "华东"),
                            new AskDsl.Filter("状态", "=", "生效"))),
                    model(perm), SqlDialects.of("MYSQL5.7"), allowed(perm, "分析师"), TODAY);

            assertThat(r.params()).containsExactly("华东", "生效");
            assertThat(r.sql()).contains("`t1`.`region` = ? AND `t1`.`status` = ?");
        }
    }

    @Nested
    @DisplayName("时间条件 —— 左闭右开，不生成方言函数")
    class TimeHandling {

        @Test
        @DisplayName("上个月 → 具体日期区间，SQL 里只有 >= ? AND < ?")
        void relativeTimeResolvedInJava() {
            DomainPermission perm = openPermission();
            RenderedSql r = AskSqlRenderer.render(
                    dsl(List.of("保费"), List.of(), new AskDsl.TimeRange("上个月", null, null), List.of()),
                    model(perm), SqlDialects.of("MYSQL5.7"), allowed(perm, "分析师"), TODAY);

            // 参考日期 2026-10-15 → 上个月是 [2026-09-01, 2026-10-01)
            assertThat(r.params()).containsExactly(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1));
            assertThat(r.sql()).contains(">= ?").contains("< ?");
            assertThat(r.sql())
                    .as("绝不生成 CURDATE() 之类的方言函数——那是方言差异的来源")
                    .doesNotContain("CURDATE")
                    .doesNotContain("NOW()");
        }

        @Test
        @DisplayName("★ 区间右开，不用 BETWEEN —— BETWEEN 会漏掉 TIMESTAMP 列的当天数据")
        void rangeIsHalfOpenNotBetween() {
            DomainPermission perm = openPermission();
            RenderedSql r = AskSqlRenderer.render(
                    dsl(List.of("保费"), List.of(), new AskDsl.TimeRange("本月", null, null), List.of()),
                    model(perm), SqlDialects.of("MYSQL5.7"), allowed(perm, "分析师"), TODAY);

            assertThat(r.sql())
                    .as("BETWEEN '2026-10-01' AND '2026-10-31' 会漏掉 10-31 14:00 这样的 TIMESTAMP 行")
                    .doesNotContain("BETWEEN");
            assertThat(r.params()).containsExactly(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1));
        }

        @Test
        @DisplayName("★ 解析不了的时间口径抛异常 —— 绝不静默丢掉时间条件")
        void unparsableTimeThrows() {
            DomainPermission perm = openPermission();

            assertThatThrownBy(() -> AskSqlRenderer.render(
                    dsl(List.of("保费"), List.of(),
                            new AskDsl.TimeRange("上一个财政年度", null, null), List.of()),
                    model(perm), SqlDialects.of("MYSQL5.7"), allowed(perm, "分析师"), TODAY))
                    .as("丢掉时间条件会返回全时段数据，数字看着正常而用户无从察觉")
                    .isInstanceOf(TimeRangeResolver.TimeRangeException.class)
                    .hasMessageContaining("上一个财政年度");
        }

        @Test
        @DisplayName("★ 域内没有日期列时抛异常，同样不静默")
        void missingTimeColumnThrows() {
            RegisteredTable noDate = new RegisteredTable("tbl-x", "t", "T", RegisteredTable.Mode.TABLE, null, true,
                    List.of(new ColumnDef("premium", "decimal", Aggregation.SUM, "保费", true)));
            DomainPermission perm = openPermission();
            Domain domain = new Domain("d1", "域", "ds1", "MYSQL5.7", true, List.of(noDate), null, perm, List.of(), List.of());

            assertThatThrownBy(() -> AskSqlRenderer.render(
                    dsl(List.of("保费"), List.of(), new AskDsl.TimeRange("上个月", null, null), List.of()),
                    new SemanticModel(domain), SqlDialects.of("MYSQL5.7"), allowed(perm, "分析师"), TODAY))
                    .isInstanceOf(com.liusongboyy.chatdata.engine.semantic.SemanticException.class);
        }
    }

    @Nested
    @DisplayName("权限 —— 渲染器存在的主要理由")
    class PermissionEnforcement {

        /** 区域经理只能看华东。 */
        private DomainPermission restrictedPerm() {
            try {
                return DomainPermission.from(M.readTree("""
                        {"v":1,
                         "grantRoles":["区域经理"],
                         "rowRules":[{"id":1,"role":"区域经理","field":"region","op":"=","value":"华东"}]}"""));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("★ 行规则的谓词必须出现在 WHERE 里，且值走参数")
        void rowRulePredicateIsInjected() {
            DomainPermission perm = restrictedPerm();
            RenderedSql r = AskSqlRenderer.render(
                    dsl(List.of("保费"), List.of(), null, List.of()),
                    model(perm), SqlDialects.of("MYSQL5.7"), allowed(perm, "区域经理"), TODAY);

            assertThat(r.sql())
                    .as("行级权限没进 SQL 等于没有权限——这是本类最要紧的一条断言")
                    .contains("(`t1`.`region` = ?)");
            assertThat(r.sql()).doesNotContain("华东");
            assertThat(r.params()).containsExactly("华东");
        }

        @Test
        @DisplayName("★ 行规则谓词与业务筛选条件共存，参数顺序不能错位")
        void rowRuleCoexistsWithFilter() {
            DomainPermission perm = restrictedPerm();
            RenderedSql r = AskSqlRenderer.render(
                    dsl(List.of("保费"), List.of(), null,
                            List.of(new AskDsl.Filter("状态", "=", "生效"))),
                    model(perm), SqlDialects.of("MYSQL5.7"), allowed(perm, "区域经理"), TODAY);

            // 业务筛选在前，权限谓词在后；参数顺序必须与占位符一致
            assertThat(r.params()).containsExactly("生效", "华东");
            assertThat(r.sql()).contains("`t1`.`status` = ? AND (`t1`.`region` = ?)");
        }

        @Test
        @DisplayName("★ 权限谓词的列也走方言引用 —— 否则 PostgreSQL 上会被大小写折叠")
        void rowRulePredicateRespectsDialect() {
            DomainPermission perm = restrictedPerm();
            SemanticModel m = model(perm);
            AskDsl d = dsl(List.of("保费"), List.of(), null, List.of());
            PermissionDecision ok = allowed(perm, "区域经理");

            String mysql = AskSqlRenderer.render(d, m, SqlDialects.of("MYSQL5.7"), ok, TODAY).sql();
            String pg = AskSqlRenderer.render(d, m, SqlDialects.of("POSTGRESQL"), ok, TODAY).sql();
            String oracle = AskSqlRenderer.render(d, m, SqlDialects.of("ORACLE"), ok, TODAY).sql();

            assertThat(mysql).contains("(`t1`.`region` = ?)");
            assertThat(pg)
                    .as("权限谓词若不按方言引用，裸列名会被 PostgreSQL 折成小写而匹配不到")
                    .contains("(\"t1\".\"region\" = ?)");
            assertThat(oracle).contains("(\"t1\".\"region\" = ?)");

            for (String sql : List.of(mysql, pg, oracle)) {
                assertThat(sql)
                        .as("不该出现未限定、未引用的裸列名")
                        .doesNotContain("(region = ?)");
            }
        }

        @Test
        @DisplayName("★ 拒绝的判定不进入渲染 —— 抛异常而不是渲染一条不带权限的 SQL")
        void deniedDecisionIsRejected() {
            DomainPermission perm = restrictedPerm();
            // 「分析师」不在 grantRoles 里
            PermissionDecision denied = PermissionGuard.check(
                    null, perm, PermissionContext.of("u1", Set.of("分析师")));
            assertThat(denied.isDenied()).isTrue();

            assertThatThrownBy(() -> AskSqlRenderer.render(
                    dsl(List.of("保费"), List.of(), null, List.of()),
                    model(perm), SqlDialects.of("MYSQL5.7"), denied, TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("权限判定");
        }

        @Test
        @DisplayName("显式全量不附加谓词")
        void unrestrictedAddsNoPredicate() {
            DomainPermission perm = openPermission();
            RenderedSql r = AskSqlRenderer.render(
                    dsl(List.of("保费"), List.of(), null, List.of()),
                    model(perm), SqlDialects.of("MYSQL5.7"), allowed(perm, "分析师"), TODAY);

            assertThat(r.sql()).doesNotContain("WHERE");
        }
    }

    @Nested
    @DisplayName("列脱敏 —— 查询前处理，数据不出库")
    class Masking {

        private DomainPermission maskedPerm() {
            try {
                return DomainPermission.from(M.readTree("""
                        {"v":1,"grantRoles":["分析师"],"unrestrictedRoles":["分析师"],
                         "colRules":[{"id":1,"role":"分析师","field":"premium","action":"脱敏"}]}"""));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("★ 脱敏列渲染成常量，原列不进 SELECT —— 数据根本不出库")
        void maskedColumnBecomesConstant() {
            DomainPermission perm = maskedPerm();
            RenderedSql r = AskSqlRenderer.render(
                    dsl(List.of("保费"), List.of(), null, List.of()),
                    model(perm), SqlDialects.of("MYSQL5.7"), allowed(perm, "分析师"), TODAY);

            assertThat(r.sql())
                    .as("查询后再打码意味着敏感数据已经出库、经过网络与内存，审计上说不清")
                    .contains("'***' AS `保费`")
                    .doesNotContain("SUM(`t1`.`premium`)");
            assertThat(r.maskedColumnNames()).containsExactly("保费");
        }

        @Test
        @DisplayName("脱敏列在输出列里标记为 masked")
        void outputColumnFlagged() {
            DomainPermission perm = maskedPerm();
            RenderedSql r = AskSqlRenderer.render(
                    dsl(List.of("保费"), List.of(), null, List.of()),
                    model(perm), SqlDialects.of("MYSQL5.7"), allowed(perm, "分析师"), TODAY);

            RenderedSql.OutputColumn col = r.columns().get(0);
            assertThat(col.masked()).isTrue();
            assertThat(col.expression()).isEqualTo("'***'");
        }

        @Test
        @DisplayName("未配列规则的角色拿到明文")
        void unmaskedRoleSeesPlaintext() {
            try {
                DomainPermission perm = DomainPermission.from(M.readTree("""
                        {"v":1,"grantRoles":["管理员"],"unrestrictedRoles":["管理员"]}"""));
                RenderedSql r = AskSqlRenderer.render(
                        dsl(List.of("保费"), List.of(), null, List.of()),
                        model(perm), SqlDialects.of("MYSQL5.7"), allowed(perm, "管理员"), TODAY);

                assertThat(r.sql()).contains("SUM(`t1`.`premium`)");
                assertThat(r.maskedColumnNames()).isEmpty();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Nested
    @DisplayName("方言差异 —— 同一 DSL 生成不同 SQL")
    class Dialects {

        @Test
        @DisplayName("★ 同一 DSL 在五种方言下生成各自正确的 SQL")
        void sameDslDifferentDialects() {
            DomainPermission perm = openPermission();
            SemanticModel m = model(perm);
            AskDsl d = dsl(List.of("保费"), List.of("区域"), new AskDsl.TimeRange("上个月", null, null), List.of());
            PermissionDecision ok = allowed(perm, "分析师");

            String mysql = AskSqlRenderer.render(d, m, SqlDialects.of("MYSQL5.7"), ok, TODAY).sql();
            String pg = AskSqlRenderer.render(d, m, SqlDialects.of("POSTGRESQL"), ok, TODAY).sql();
            String oracle = AskSqlRenderer.render(d, m, SqlDialects.of("ORACLE"), ok, TODAY).sql();
            String sqlServer = AskSqlRenderer.render(d, m, SqlDialects.of("SQLSERVER"), ok, TODAY).sql();
            String doris = AskSqlRenderer.render(d, m, SqlDialects.of("Doris"), ok, TODAY).sql();

            // MySQL 系反引号，其余双引号
            assertThat(mysql).contains("`t1`.`region`");
            assertThat(doris).contains("`t1`.`region`");
            assertThat(pg).contains("\"t1\".\"region\"");
            assertThat(oracle).contains("\"t1\".\"region\"");
            assertThat(sqlServer).contains("\"t1\".\"region\"");

            // 行数限制：Oracle 用 FETCH FIRST，SQL Server 用前缀 TOP
            assertThat(mysql).endsWith("LIMIT 1000");
            assertThat(pg).endsWith("LIMIT 1000");
            assertThat(oracle).endsWith("FETCH FIRST 1000 ROWS ONLY");
            assertThat(sqlServer).contains("SELECT TOP 1000 ");
            assertThat(sqlServer).doesNotContain("LIMIT");
        }

        @Test
        @DisplayName("★ 时间条件在所有方言下都是 >= ? AND < ? —— 不生成方言函数")
        void timeConditionIsDialectNeutral() {
            DomainPermission perm = openPermission();
            SemanticModel m = model(perm);
            AskDsl d = dsl(List.of("保费"), List.of(), new AskDsl.TimeRange("本月", null, null), List.of());
            PermissionDecision ok = allowed(perm, "分析师");

            for (String t : new String[]{"MYSQL5.7", "Doris", "POSTGRESQL", "ORACLE", "SQLSERVER"}) {
                String sql = AskSqlRenderer.render(d, m, SqlDialects.of(t), ok, TODAY).sql();
                assertThat(sql)
                        .as("%s 的时间条件应当是方言中立的占位符形式", t)
                        .contains(">= ?")
                        .contains("< ?")
                        .doesNotContain("CURDATE");
            }
        }
    }

    @Nested
    @DisplayName("边界与拒绝")
    class Boundaries {

        @Test
        @DisplayName("空 DSL 拒绝")
        void emptyDslRejected() {
            DomainPermission perm = openPermission();
            assertThatThrownBy(() -> AskSqlRenderer.render(
                    dsl(List.of(), List.of(), null, List.of()),
                    model(perm), SqlDialects.of("MYSQL5.7"), allowed(perm, "分析师"), TODAY))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("★ 字段跨两张没配关联的表 → 拒绝，不猜一个 join 条件")
        void crossTableWithoutJoinRejected() {
            RegisteredTable a = new RegisteredTable("tbl-a", "ta", "A", RegisteredTable.Mode.TABLE, null, true,
                    List.of(new ColumnDef("premium", "decimal", Aggregation.SUM, "保费", true)));
            RegisteredTable b = new RegisteredTable("tbl-b", "tb", "B", RegisteredTable.Mode.TABLE, null, true,
                    List.of(new ColumnDef("cnt", "int", Aggregation.SUM, "数量", true)));
            DomainPermission perm = openPermission();
            // joins 为空 —— 两张表在域内连不起来
            Domain domain = new Domain("d1", "域", "ds1", "MYSQL5.7", true, List.of(a, b), List.of(), perm, List.of(), List.of());

            assertThatThrownBy(() -> AskSqlRenderer.render(
                    dsl(List.of("保费", "数量"), List.of(), null, List.of()),
                    new SemanticModel(domain), SqlDialects.of("MYSQL5.7"), allowed(perm, "分析师"), TODAY))
                    .as("猜一个 join 条件会返回看起来合理但错误的结果")
                    .isInstanceOf(com.liusongboyy.chatdata.engine.semantic.SemanticException.class)
                    .hasMessageContaining("连不起来");
        }

        @Test
        @DisplayName("找不到的字段拒绝")
        void unknownFieldRejected() {
            DomainPermission perm = openPermission();
            assertThatThrownBy(() -> AskSqlRenderer.render(
                    dsl(List.of("不存在的指标"), List.of(), null, List.of()),
                    model(perm), SqlDialects.of("MYSQL5.7"), allowed(perm, "分析师"), TODAY))
                    .isInstanceOf(com.liusongboyy.chatdata.engine.semantic.SemanticException.class);
        }

        @Test
        @DisplayName("渲染产物自检：占位符与参数数量必须一致")
        void placeholderParamCountMatches() {
            DomainPermission perm = openPermission();
            RenderedSql r = AskSqlRenderer.render(
                    dsl(List.of("保费"), List.of("区域"), new AskDsl.TimeRange("本月", null, null),
                            List.of(new AskDsl.Filter("状态", "=", "生效"))),
                    model(perm), SqlDialects.of("MYSQL5.7"), allowed(perm, "分析师"), TODAY);

            long placeholders = r.sql().chars().filter(c -> c == '?').count();
            assertThat(placeholders).isEqualTo(r.params().size());
        }
    }

    @Nested
    @DisplayName("多表 join")
    class MultiTableJoins {

        /** 保单表 + 客户表，通过 cust_id = id 关联。 */
        private SemanticModel joinModel(TableJoin.JoinType joinType, DomainPermission perm) {
            RegisteredTable policy = new RegisteredTable("tbl-policy", "ins_policy", "保单表",
                    RegisteredTable.Mode.TABLE, null, true, List.of(
                    new ColumnDef("premium", "decimal", Aggregation.SUM, "保费", true),
                    new ColumnDef("cust_id", "bigint", Aggregation.NONE, "客户号", true),
                    new ColumnDef("region", "varchar", Aggregation.NONE, "区域", true)));

            RegisteredTable customer = new RegisteredTable("tbl-customer", "ins_customer", "客户表",
                    RegisteredTable.Mode.TABLE, null, true, List.of(
                    new ColumnDef("id", "bigint", Aggregation.NONE, "客户ID", true),
                    new ColumnDef("cust_name", "varchar", Aggregation.NONE, "客户姓名", true)));

            TableJoin join = new TableJoin(1L, "tbl-policy", "tbl-customer",
                    "cust_id", "id", joinType);

            Domain domain = new Domain("d1", "保险分析域", "ds1", "MYSQL5.7", true,
                    List.of(policy, customer), List.of(join), perm, List.of(), List.of());
            return new SemanticModel(domain);
        }

        @Test
        @DisplayName("两张表通过 INNER JOIN 连起来")
        void innerJoinRendered() {
            DomainPermission perm = openPermission();
            RenderedSql r = AskSqlRenderer.render(
                    dsl(List.of("保费"), List.of("客户姓名"), null, List.of()),
                    joinModel(TableJoin.JoinType.INNER, perm),
                    SqlDialects.of("MYSQL5.7"), allowed(perm, "分析师"), TODAY);

            assertThat(r.sql())
                    .contains("FROM `ins_policy` `t2`")
                    .contains("INNER JOIN `ins_customer` `t1`")
                    .contains("ON `t2`.`cust_id` = `t1`.`id`");
            // 各列必须带自己的表限定
            assertThat(r.sql()).contains("SUM(`t2`.`premium`) AS `保费`");
            assertThat(r.sql()).contains("`t1`.`cust_name` AS `客户姓名`");
            assertThat(r.sql()).contains("GROUP BY `t1`.`cust_name`");
        }

        @Test
        @DisplayName("★ LEFT JOIN 方向不被改动")
        void leftJoinDirectionPreserved() {
            DomainPermission perm = openPermission();
            RenderedSql r = AskSqlRenderer.render(
                    dsl(List.of("保费"), List.of("客户姓名"), null, List.of()),
                    joinModel(TableJoin.JoinType.LEFT, perm),
                    SqlDialects.of("MYSQL5.7"), allowed(perm, "分析师"), TODAY);

            assertThat(r.sql())
                    .as("LEFT JOIN 保留左表全部行；改成 INNER 或反向都会改变行数")
                    .contains("LEFT JOIN `ins_customer` `t1`")
                    .doesNotContain("INNER JOIN")
                    .doesNotContain("RIGHT JOIN");
        }

        @Test
        @DisplayName("★ 多表下权限谓词必须注到正确的表上")
        void rowRuleQualifiedToCorrectTable() {
            // 行规则作用在 region 上，而 region 只在保单表里
            DomainPermission perm = permOf("""
                    {"v":1,"grantRoles":["区域经理"],
                     "rowRules":[{"id":1,"role":"区域经理","field":"region","op":"=","value":"华东"}]}""");

            RenderedSql r = AskSqlRenderer.render(
                    dsl(List.of("保费"), List.of("客户姓名"), null, List.of()),
                    joinModel(TableJoin.JoinType.INNER, perm),
                    SqlDialects.of("MYSQL5.7"), allowed(perm, "区域经理"), TODAY);

            assertThat(r.sql())
                    .as("region 在保单表上，谓词必须写成 `t1`.`region`；"
                            + "写成裸 region 在多表下会歧义，注错表则更危险")
                    .contains("(`t2`.`region` = ?)");
            assertThat(r.params()).containsExactly("华东");
        }

        @Test
        @DisplayName("★ 行规则字段在多张表上都有时拒绝 —— 不能猜注哪张表")
        void ambiguousRowRuleFieldRejected() {
            // 两张表都有 region
            RegisteredTable a = new RegisteredTable("tbl-a", "ta", "A", RegisteredTable.Mode.TABLE, null, true,
                    List.of(new ColumnDef("premium", "decimal", Aggregation.SUM, "保费", true),
                            new ColumnDef("region", "varchar", Aggregation.NONE, "区域", true),
                            new ColumnDef("aid", "bigint", Aggregation.NONE, "A编号", true)));
            RegisteredTable b = new RegisteredTable("tbl-b", "tb", "B", RegisteredTable.Mode.TABLE, null, true,
                    List.of(new ColumnDef("bid", "bigint", Aggregation.NONE, "B编号", true),
                            new ColumnDef("region", "varchar", Aggregation.NONE, "区域", true)));

            DomainPermission perm = permOf("""
                    {"v":1,"grantRoles":["区域经理"],
                     "rowRules":[{"id":1,"role":"区域经理","field":"region","op":"=","value":"华东"}]}""");

            Domain domain = new Domain("d1", "域", "ds1", "MYSQL5.7", true, List.of(a, b),
                    List.of(new TableJoin(1L, "tbl-a", "tbl-b", "aid", "bid", TableJoin.JoinType.INNER)),
                    perm, List.of(), List.of());

            assertThatThrownBy(() -> AskSqlRenderer.render(
                    dsl(List.of("保费"), List.of("B编号"), null, List.of()),
                    new SemanticModel(domain), SqlDialects.of("MYSQL5.7"), allowed(perm, "区域经理"), TODAY))
                    .as("注错表的权限比查询失败危险得多")
                    .isInstanceOf(com.liusongboyy.chatdata.engine.semantic.SemanticException.class)
                    .hasMessageContaining("区域");
        }

        @Test
        @DisplayName("三张表链式 join，每一步的 ON 都引用已出现的表")
        void threeTableChain() {
            RegisteredTable a = new RegisteredTable("t1", "ta", "A", RegisteredTable.Mode.TABLE, null, true,
                    List.of(new ColumnDef("id", "bigint", Aggregation.NONE, "A编号", true),
                            new ColumnDef("m", "decimal", Aggregation.SUM, "金额", true)));
            RegisteredTable b = new RegisteredTable("t2", "tb", "B", RegisteredTable.Mode.TABLE, null, true,
                    List.of(new ColumnDef("id", "bigint", Aggregation.NONE, "B编号", true),
                            new ColumnDef("aid", "bigint", Aggregation.NONE, "A外键", true),
                            new ColumnDef("cid", "bigint", Aggregation.NONE, "C外键", true)));
            RegisteredTable c = new RegisteredTable("t3", "tc", "C", RegisteredTable.Mode.TABLE, null, true,
                    List.of(new ColumnDef("id", "bigint", Aggregation.NONE, "C编号", true),
                            new ColumnDef("name", "varchar", Aggregation.NONE, "C名称", true)));

            DomainPermission perm = openPermission();
            Domain domain = new Domain("d1", "域", "ds1", "MYSQL5.7", true, List.of(a, b, c), List.of(
                    new TableJoin(1L, "t1", "t2", "id", "aid", TableJoin.JoinType.INNER),
                    new TableJoin(2L, "t2", "t3", "cid", "id", TableJoin.JoinType.INNER)),
                    perm, List.of(), List.of());

            RenderedSql r = AskSqlRenderer.render(
                    dsl(List.of("金额"), List.of("C名称"), null, List.of()),
                    new SemanticModel(domain), SqlDialects.of("MYSQL5.7"), allowed(perm, "分析师"), TODAY);

            // 别名按注册表 id 排序分配：t1→`t1`, t2→`t2`, t3→`t3`
            assertThat(r.sql())
                    .contains("FROM `ta` `t1`")
                    .contains("INNER JOIN `tb` `t2` ON `t1`.`id` = `t2`.`aid`")
                    .contains("INNER JOIN `tc` `t3` ON `t2`.`cid` = `t3`.`id`");
        }

        @Test
        @DisplayName("多表下方言引用仍然正确")
        void multiTableRespectsDialect() {
            DomainPermission perm = openPermission();
            SemanticModel m = joinModel(TableJoin.JoinType.INNER, perm);
            AskDsl d = dsl(List.of("保费"), List.of("客户姓名"), null, List.of());
            PermissionDecision ok = allowed(perm, "分析师");

            assertThat(AskSqlRenderer.render(d, m, SqlDialects.of("POSTGRESQL"), ok, TODAY).sql())
                    .contains("FROM \"ins_policy\" \"t2\"")
                    .contains("INNER JOIN \"ins_customer\" \"t1\"")
                    .contains("ON \"t2\".\"cust_id\" = \"t1\".\"id\"");
        }
    }

    /**
     * 时间粒度 —— DSL 里的 {@code granularity} 必须真的落到 SQL 上。
     *
     * <h2>这一组是补的，因为漏过一次</h2>
     *
     * <p>{@code AskDsl.Granularity} 与各方言的 {@code bucket()} 都写好了，
     * 但**渲染器从来没调过**：问「按月看保费趋势」得到的仍是
     * {@code GROUP BY 生效日期}（按天），返回几百个点。
     * 数字都对，只是不是用户要的粒度——正是本项目最忌讳的那类失败。
     *
     * <p>而当时方言侧的单测是**全绿**的：{@code SqlDialectTest} 逐个数了
     * {@code DATE_FORMAT} / {@code to_char} 的格式串。教训是
     * **"部件测过"不等于"接线测过"**，故这一组的断言都落在渲染产物上。
     */
    /**
     * 筛选运算符。
     *
     * <p>这一组来自一次真实失败：模型把 {@code op} 填成中文「在」（意为 {@code in}），
     * 而规范化只认 ASCII，于是抛 {@code IllegalArgumentException}——
     * 那个类型不在编排层的 catch 分支里，结果既是"处理失败，请稍后重试"（误导），
     * 又**没留下审计**。
     */
    /**
     * 作用在日期列上的筛选取值。
     *
     * <p>来自一次真实失败：模型把「上个月」写进 filters 的 value，
     * SQL 变成 {@code effect_date IN ('上个月')}，MySQL 报
     * <b>Incorrect DATE value: '上个月'</b>——用户看到的是"查询失败，请稍后重试"。
     */
    @Nested
    @DisplayName("日期列的筛选 —— 必须是具体日期")
    class TemporalFilterValues {

        private RenderedSql renderDateFilter(String op, String value) {
            DomainPermission perm = openPermission();
            return AskSqlRenderer.render(
                    dsl(List.of("保费"), List.of(), null,
                            List.of(new AskDsl.Filter("生效日期", op, value))),
                    model(perm), SqlDialects.of("MYSQL5.7"), allowed(perm, "分析师"), TODAY);
        }

        @Test
        @DisplayName("★ 相对时间塞进筛选 → SemanticException，且提示改用 timeRange")
        void relativeTimeInFilterIsRejected() {
            assertThatThrownBy(() -> renderDateFilter("在", "上个月"))
                    .as("必须在渲染阶段拦住，而不是让 MySQL 报 Incorrect DATE value")
                    .isInstanceOf(SemanticException.class)
                    .hasMessageContaining("上个月")
                    .hasMessageContaining("timeRange");
        }

        @Test
        @DisplayName("具体日期按 LocalDate 绑定，不按字符串绑")
        void isoDateIsBoundAsDate() {
            RenderedSql r = renderDateFilter("=", "2026-09-01");

            assertThat(r.params()).singleElement().isInstanceOf(java.time.LocalDate.class);
            assertThat(r.params()).containsExactly(java.time.LocalDate.of(2026, 9, 1));
        }

        @Test
        @DisplayName("IN 里的每一项也要是日期")
        void inValuesMustBeDates() {
            RenderedSql ok = renderDateFilter("在", "2026-09-01,2026-10-01");
            assertThat(ok.sql()).contains("IN (?, ?)");
            assertThat(ok.params()).allSatisfy(p -> assertThat(p).isInstanceOf(java.time.LocalDate.class));

            assertThatThrownBy(() -> renderDateFilter("在", "上个月,这个月"))
                    .isInstanceOf(SemanticException.class);
        }
    }

    @Nested
    @DisplayName("筛选运算符 —— 模型会填中文")
    class FilterOperators {

        private RenderedSql renderWithOp(String op) {
            DomainPermission perm = openPermission();
            return AskSqlRenderer.render(
                    dsl(List.of("保费"), List.of(), null,
                            List.of(new AskDsl.Filter("区域", op, "华东"))),
                    model(perm), SqlDialects.of("MYSQL5.7"), allowed(perm, "分析师"), TODAY);
        }

        @Test
        @DisplayName("★ 中文「在」→ IN，不报错")
        void chineseInBecomesIn() {
            RenderedSql r = renderWithOp("在");
            assertThat(r.sql())
                    .as("IN 后面必须是括号列表——`col IN ?` 是非法 SQL")
                    .contains("IN (?)")
                    .doesNotContain("IN ?");
            assertThat(r.params()).containsExactly("华东");
        }

        @Test
        @DisplayName("其它常见中文写法也认")
        void otherChineseForms() {
            assertThat(renderWithOp("等于").sql()).contains("`t1`.`region` = ?");
            assertThat(renderWithOp("不等于").sql()).contains("<> ?");
            assertThat(renderWithOp("大于").sql()).contains("> ?");
            assertThat(renderWithOp("大于等于").sql()).contains(">= ?");
            assertThat(renderWithOp("小于").sql()).contains("< ?");
            assertThat(renderWithOp("小于等于").sql()).contains("<= ?");
            assertThat(renderWithOp("属于").sql()).contains("IN (?)");
            assertThat(renderWithOp("包含").sql()).contains("LIKE ?");
        }

        @Test
        @DisplayName("★ 认不出来 → SemanticException（而不是 IllegalArgumentException）")
        void unknownOpIsSemantic() {
            assertThatThrownBy(() -> renderWithOp("大概等于"))
                    .as("必须是 SemanticException，否则不会触发带纠正提示的重试、也不会进审计")
                    .isInstanceOf(SemanticException.class)
                    .hasMessageContaining("大概等于")
                    .hasMessageContaining("不要用中文");
        }
    }

    @Nested
    @DisplayName("时间粒度 —— 按月/按年真的落到 SQL 上")
    class GranularityRendering {

        private AskDsl monthly() {
            return new AskDsl(List.of("保费"), List.of("生效日期"), null, List.of(),
                    AskDsl.Granularity.MONTH);
        }

        private RenderedSql render(AskDsl dsl, String dbType) {
            DomainPermission perm = openPermission();
            return AskSqlRenderer.render(dsl, model(perm), SqlDialects.of(dbType),
                    allowed(perm, "分析师"), TODAY);
        }

        @Test
        @DisplayName("★ 按月：SELECT 与 GROUP BY 都按年月截断")
        void monthBucketsBothClauses() {
            String sql = render(monthly(), "MYSQL5.7").sql();

            assertThat(sql)
                    .contains("DATE_FORMAT(`t1`.`effect_date`, '%Y-%m') AS `生效日期`")
                    .contains("GROUP BY DATE_FORMAT(`t1`.`effect_date`, '%Y-%m')");
        }

        @Test
        @DisplayName("★ SELECT 与 GROUP BY 必须是同一个表达式 —— 分开算会静默错开")
        void selectAndGroupByUseTheSameExpression() {
            String sql = render(monthly(), "MYSQL5.7").sql();

            // 两者都出现，且都是截断后的表达式；若只有 SELECT 截断，
            // MySQL 不报错，但分组口径与显示口径就不一致了
            int groupBy = sql.indexOf("GROUP BY");
            assertThat(groupBy).isGreaterThan(0);
            assertThat(sql.substring(0, groupBy)).contains("DATE_FORMAT");
            assertThat(sql.substring(groupBy)).contains("DATE_FORMAT");
        }

        @Test
        @DisplayName("按年：MySQL 用原生 YEAR()，不是把 %m 换成 %Y")
        void yearBucket() {
            AskDsl yearly = new AskDsl(List.of("保费"), List.of("生效日期"), null, List.of(),
                    AskDsl.Granularity.YEAR);

            assertThat(render(yearly, "MYSQL5.7").sql())
                    .contains("YEAR(`t1`.`effect_date`) AS `生效日期`")
                    .contains("GROUP BY YEAR(`t1`.`effect_date`)");
        }

        @Test
        @DisplayName("不指定粒度（默认按天）：原样分组，不套任何日期函数")
        void dayGranularityIsUntouched() {
            assertThat(render(dsl(List.of("保费"), List.of("生效日期"), null, List.of()),
                    "MYSQL5.7").sql())
                    .doesNotContain("DATE_FORMAT")
                    .contains("GROUP BY `t1`.`effect_date`");
        }

        @Test
        @DisplayName("★ 非日期维度不截断 —— 对 varchar 套日期函数会直接报错或悄悄得到 NULL")
        void nonTemporalDimensionIsNeverBucketed() {
            AskDsl byRegion = new AskDsl(List.of("保费"), List.of("区域"), null, List.of(),
                    AskDsl.Granularity.MONTH);

            assertThat(render(byRegion, "MYSQL5.7").sql())
                    .doesNotContain("DATE_FORMAT")
                    .contains("GROUP BY `t1`.`region`");
        }

        @Test
        @DisplayName("方言分派：PG / Oracle 用 to_char，不是 MySQL 的 %Y-%m")
        void dialectDispatch() {
            assertThat(render(monthly(), "POSTGRESQL").sql())
                    .contains("to_char(\"t1\".\"effect_date\", 'YYYY-MM')")
                    .doesNotContain("%Y-%m");
            assertThat(render(monthly(), "ORACLE").sql())
                    .contains("to_char(\"t1\".\"effect_date\", 'YYYY-MM')")
                    .doesNotContain("%Y-%m");
        }
    }

    @Nested
    @DisplayName("/** SQL 虚拟表 */")
    class VirtualTables {

        /**
         * 夹具：一张物理表 + 一张内建软删过滤的虚拟表。
         *
         * <p>虚拟表的典型用法就是把"必须成立于每一条查询"的口径写进定义，
         * 例如 {@code WHERE deleted = 0}——它不该出现在语义层的列定义里，
         * 也不该指望模型每次都记得加。
         */
        private SemanticModel virtualModel() {
            RegisteredTable tickets = new RegisteredTable("tbl-ticket", "ticket", "工单表",
                    RegisteredTable.Mode.SQL, "SELECT * FROM ticket WHERE deleted = 0", true, List.of(
                    new ColumnDef("ticket_no", "varchar", Aggregation.COUNT, "工单数", true),
                    new ColumnDef("status", "varchar", Aggregation.NONE, "状态", true)));

            RegisteredTable categories = new RegisteredTable("tbl-category", "ticket_category", "工单分类",
                    RegisteredTable.Mode.SQL, "SELECT * FROM ticket_category WHERE deleted = 0", true, List.of(
                    new ColumnDef("name", "varchar", Aggregation.NONE, "分类名称", true)));

            Domain domain = new Domain("d1", "工单域", "ds1", "MYSQL5.7", true,
                    List.of(tickets, categories),
                    List.of(new TableJoin(1L, "tbl-ticket", "tbl-category",
                            "category_id", "id", TableJoin.JoinType.INNER)),
                    openPermission(), List.of(), List.of());
            return new SemanticModel(domain);
        }

        @Test
        @DisplayName("★ 单张虚拟表：子查询外层只能有一个别名（曾渲染出 `ticket` `t1` 两个）")
        void singleVirtualTableHasExactlyOneAlias() {
            DomainPermission perm = openPermission();
            SemanticModel m = virtualModel();

            RenderedSql r = AskSqlRenderer.render(
                    dsl(List.of("工单数"), List.of(), null, List.of()),
                    m, SqlDialects.of("MYSQL5.7"), allowed(perm, "分析师"), TODAY);

            // 别名编号按域内全部可见表分配（另一张表占了 t1），重点是子查询外层
            // **只有一个**别名，且 SELECT 里引用的就是它
            assertThat(r.sql()).isEqualTo(
                    "SELECT COUNT(`t2`.`ticket_no`) AS `工单数` "
                            + "FROM (SELECT * FROM ticket WHERE deleted = 0) `t2` LIMIT 1000");
        }

        @Test
        @DisplayName("★ 虚拟表参与 JOIN：两侧都只带一个别名，且 JOIN 前缀不重复表引用")
        void virtualTablesInJoin() {
            DomainPermission perm = openPermission();
            SemanticModel m = virtualModel();

            RenderedSql r = AskSqlRenderer.render(
                    dsl(List.of("工单数"), List.of("分类名称"), null, List.of()),
                    m, SqlDialects.of("MYSQL5.7"), allowed(perm, "分析师"), TODAY);

            // 别名编号按域内全部可见表分配，顺序不固定；这里只断言**结构**：
            // 两个派生表各一个别名，且 ON 用的就是这两个别名
            Matcher ticketAlias = Pattern
                    .compile("FROM \\(SELECT \\* FROM ticket WHERE deleted = 0\\) `(t\\d+)`")
                    .matcher(r.sql());
            Matcher categoryAlias = Pattern
                    .compile("INNER JOIN \\(SELECT \\* FROM ticket_category WHERE deleted = 0\\) `(t\\d+)`")
                    .matcher(r.sql());
            assertThat(ticketAlias.find()).as("工单虚拟表应被渲染为带别名的派生表").isTrue();
            assertThat(categoryAlias.find()).as("分类虚拟表应被渲染为带别名的派生表").isTrue();

            String t = ticketAlias.group(1);
            String c = categoryAlias.group(1);
            assertThat(t).isNotEqualTo(c);
            assertThat(r.sql()).contains("ON `" + t + "`.`category_id` = `" + c + "`.`id`");
            // 子查询后面只能跟一个别名；曾经是 `ticket` `t1` 两个
            assertThat(r.sql()).doesNotContainPattern("\\) `\\w+` `t\\d+`");
        }

        @Test
        @DisplayName("虚拟表与物理表混用：物理表仍直接引用表名")
        void virtualAndPhysicalMixed() {
            RegisteredTable tickets = new RegisteredTable("tbl-ticket", "ticket", "工单表",
                    RegisteredTable.Mode.SQL, "SELECT * FROM ticket WHERE deleted = 0", true, List.of(
                    new ColumnDef("ticket_no", "varchar", Aggregation.COUNT, "工单数", true)));
            RegisteredTable teams = new RegisteredTable("tbl-team", "team", "团队表",
                    RegisteredTable.Mode.TABLE, null, true, List.of(
                    new ColumnDef("name", "varchar", Aggregation.NONE, "团队名称", true)));

            Domain domain = new Domain("d1", "工单域", "ds1", "MYSQL5.7", true,
                    List.of(tickets, teams),
                    List.of(new TableJoin(1L, "tbl-ticket", "tbl-team",
                            "team_id", "id", TableJoin.JoinType.INNER)),
                    openPermission(), List.of(), List.of());
            DomainPermission perm = openPermission();

            RenderedSql r = AskSqlRenderer.render(
                    dsl(List.of("工单数"), List.of("团队名称"), null, List.of()),
                    new SemanticModel(domain), SqlDialects.of("MYSQL5.7"),
                    allowed(perm, "分析师"), TODAY);

            Matcher ticketAlias = Pattern
                    .compile("FROM \\(SELECT \\* FROM ticket WHERE deleted = 0\\) `(t\\d+)`")
                    .matcher(r.sql());
            Matcher teamAlias = Pattern.compile("`team` `(t\\d+)`").matcher(r.sql());
            assertThat(ticketAlias.find()).isTrue();
            assertThat(teamAlias.find()).as("物理表仍直接引用表名并带一个别名").isTrue();

            String t = ticketAlias.group(1);
            String team = teamAlias.group(1);
            assertThat(t).isNotEqualTo(team);
            assertThat(r.sql()).contains("ON `" + t + "`.`team_id` = `" + team + "`.`id`");
        }
    }

    @Nested
    @DisplayName("/** 时间列的选择范围 */")
    class TimeColumnScope {

        /**
         * 夹具：保单表有「生效日期」，客户表有「注册日期」。
         *
         * <p>两张表都能被 {@code resolveDimension} 解析到，但一次查询里
         * 只有**参与 FROM 的表**能用。挑错表会渲染出
         * {@code Unknown column 'tX.effect_date'}——SQL 直接执行失败。
         */
        private SemanticModel twoDateTables() {
            RegisteredTable policy = new RegisteredTable("tbl-policy", "ins_policy", "保单表",
                    RegisteredTable.Mode.TABLE, null, true, List.of(
                    new ColumnDef("premium", "decimal", Aggregation.SUM, "保费", true),
                    new ColumnDef("cust_id", "bigint", Aggregation.NONE, "客户ID", true),
                    new ColumnDef("effect_date", "date", Aggregation.NONE, "生效日期", true)));

            RegisteredTable customer = new RegisteredTable("tbl-cust", "ins_customer", "客户表",
                    RegisteredTable.Mode.TABLE, null, true, List.of(
                    new ColumnDef("cust_level", "varchar", Aggregation.NONE, "客户等级", true),
                    new ColumnDef("reg_date", "date", Aggregation.NONE, "注册日期", true)));

            Domain domain = new Domain("d1", "保险分析域", "ds1", "MYSQL5.7", true,
                    List.of(policy, customer),
                    List.of(new TableJoin(1L, "tbl-policy", "tbl-cust",
                            "cust_id", "id", TableJoin.JoinType.INNER)),
                    openPermission(), List.of(), List.of());
            return new SemanticModel(domain);
        }

        @Test
        @DisplayName("★ 只查客户表时的带时间条件，必须用客户表的日期列（曾选到未参与 FROM 的表）")
        void timeColumnMustBelongToInvolvedTables() {
            DomainPermission perm = openPermission();
            SemanticModel m = twoDateTables();

            // 只查「客户等级」→ FROM 只有客户表。「生效日期」属于保单表、不在 FROM 里；
            // 客户表的「注册日期」又不在候选词表里。
            //
            // 正确行为是**拒绝**：要么明确告诉用户"这个问法在本域取不到时间口径"，
            // 要么去建模里补上术语。渲染出 `t2.effect_date` 是最差的一种——
            // 用户拿到的是 MySQL 的 Unknown column，完全指不到配置问题。
            assertThatThrownBy(() -> AskSqlRenderer.render(
                    new AskDsl(List.of(), List.of("客户等级"),
                            new AskDsl.TimeRange("今年", null, null), List.of()),
                    m, SqlDialects.of("MYSQL5.7"), allowed(perm, "分析师"), TODAY))
                    .isInstanceOf(SemanticException.class)
                    // 措辞变了、判据没变：仍然拒绝，只是说清了"为什么不自动 JOIN"
                    .hasMessageContaining("父子反方向");
        }

        @Test
        @DisplayName("★ 时间表是**父表**时自动拉进 FROM——明细没有日期、日期在主表上是常见形态")
        void timeColumnParentIsPulledIn() {
            // 明细 → 主表（多对一）：拉主表不放大行数，可以自动 JOIN
            RegisteredTable header = new RegisteredTable("tbl-order", "f_order", "订单表",
                    RegisteredTable.Mode.TABLE, null, true, List.of(
                    new ColumnDef("id", "bigint", Aggregation.COUNT, "订单数", true),
                    new ColumnDef("order_date", "date", Aggregation.NONE, "日期", true)));

            RegisteredTable detail = new RegisteredTable("tbl-line", "f_order_line", "订单明细",
                    RegisteredTable.Mode.TABLE, null, true, List.of(
                    new ColumnDef("qty", "int", Aggregation.SUM, "数量", true),
                    new ColumnDef("order_id", "bigint", Aggregation.NONE, "订单ID", true)));

            Domain d = new Domain("d1", "订单域", "ds1", "MYSQL5.7", true,
                    List.of(header, detail),
                    List.of(new TableJoin(1L, "tbl-line", "tbl-order",
                            "order_id", "id", TableJoin.JoinType.INNER)),
                    openPermission(), List.of(), List.of());

            RenderedSql r = AskSqlRenderer.render(
                    new AskDsl(List.of("数量"), List.of(),
                            new AskDsl.TimeRange("今年", null, null), List.of()),
                    new SemanticModel(d), SqlDialects.of("MYSQL5.7"), allowed(openPermission(), "分析师"), TODAY);

            assertThat(r.sql())
                    .as("订单表被自动 JOIN 进来，时间条件才落得下")
                    .contains("JOIN `f_order`")
                    // 别名按注册表 id 排序分配：tbl-line=t1、tbl-order=t2
                    .contains("`t2`.`order_date` >= ?");
        }
    }

    @Nested
    @DisplayName("/** 权限表自动进 FROM */")
    class PermissionTableJoin {

        /**
         * 星型模型的典型形态：事实表只有 {@code store_id}，组织层级在维表上。
         *
         * <p>行规则只能配在维表（{@code dim_org.org_path}）——事实表上没有这个字段。
         * 但用户问「订单金额」时，DSL 只涉及 f_order，维表不在候选里。
         * 若不把权限表拉进 FROM，谓词 {@code tN.org_path LIKE ...} 引用的别名不存在，
         * 执行时直接报 {@code Unknown column}。
         */
        private SemanticModel starSchema() {
            RegisteredTable order = new RegisteredTable("tbl-order", "f_order", "订单表",
                    RegisteredTable.Mode.TABLE, null, true, List.of(
                    new ColumnDef("store_id", "bigint", Aggregation.NONE, "门店ID", true),
                    new ColumnDef("amount", "decimal", Aggregation.SUM, "订单金额", true)));

            RegisteredTable stock = new RegisteredTable("tbl-stock", "f_stock", "库存表",
                    RegisteredTable.Mode.TABLE, null, true, List.of(
                    new ColumnDef("store_id", "bigint", Aggregation.NONE, "库存门店ID", true),
                    new ColumnDef("qty", "int", Aggregation.SUM, "库存数量", true)));

            RegisteredTable org = new RegisteredTable("tbl-org", "dim_org", "组织表",
                    RegisteredTable.Mode.TABLE, null, true, List.of(
                    new ColumnDef("id", "bigint", Aggregation.NONE, "组织ID", true),
                    new ColumnDef("org_path", "varchar", Aggregation.NONE, "组织路径", true)));

            Domain domain = new Domain("d1", "门店分析域", "ds1", "MYSQL5.7", true,
                    List.of(order, stock, org),
                    List.of(
                            new TableJoin(1L, "tbl-order", "tbl-org",
                                    "store_id", "id", TableJoin.JoinType.LEFT),
                            new TableJoin(2L, "tbl-stock", "tbl-org",
                                    "store_id", "id", TableJoin.JoinType.LEFT)),
                    permOf("""
                            {"v":1,"grantRoles":["区域经理"],
                             "rowRules":[{"id":1,"role":"区域经理","field":"org_path",
                                          "op":"starts_with","sysVar":"orgPaths","dynamic":true}]}"""),
                    List.of(), List.of());
            return new SemanticModel(domain);
        }

        private PermissionDecision.Allowed allowedWithOrg(String path) {
            PermissionContext ctx = new PermissionContext("u1", Set.of("区域经理"),
                    java.util.Map.of("orgPaths", List.of(path)));
            DomainPermission perm = starSchema().domain().permission();
            return (PermissionDecision.Allowed) PermissionGuard.check(null, perm, ctx);
        }

        @Test
        @DisplayName("★ 查事实表时，权限引用的维表必须自动 JOIN 进 FROM")
        void permissionTableAutoJoins() {
            SemanticModel m = starSchema();

            RenderedSql r = AskSqlRenderer.render(
                    dsl(List.of("订单金额"), List.of(), null, List.of()),
                    m, SqlDialects.of("MYSQL5.7"), allowedWithOrg("/1/100/110/"), TODAY);

            assertThat(r.sql())
                    .as("谓词引用了 org_path，它所在的行只有 JOIN 进来才存在")
                    .contains("dim_org")
                    .contains("org_path");
            // 取值走参数绑定，不拼进 SQL 文本
            assertThat(r.params())
                    .as("前缀参数是 值+%")
                    .containsExactly("/1/100/110/%");
        }

        @Test
        @DisplayName("★ 换一张事实表，同一条行规则照样生效（不需要为每张表配规则）")
        void sameRuleAppliesToEveryFactTable() {
            SemanticModel m = starSchema();
            PermissionDecision.Allowed allowed = allowedWithOrg("/1/100/110/");

            RenderedSql order = AskSqlRenderer.render(
                    dsl(List.of("订单金额"), List.of(), null, List.of()),
                    m, SqlDialects.of("MYSQL5.7"), allowed, TODAY);
            RenderedSql stock = AskSqlRenderer.render(
                    dsl(List.of("库存数量"), List.of(), null, List.of()),
                    m, SqlDialects.of("MYSQL5.7"), allowed, TODAY);

            assertThat(order.sql()).contains("dim_org").contains("org_path");
            assertThat(stock.sql()).contains("dim_org").contains("org_path");
            // 两张表各自 JOIN 组织表，不是共用同一份 FROM
            assertThat(order.sql()).contains("f_order");
            assertThat(stock.sql()).contains("f_stock");
        }

        @Test
        @DisplayName("无行规则时不多 JOIN 一张表（避免无谓的行数变化）")
        void noPermissionTableWhenUnrestricted() {
            SemanticModel m = starSchema();
            PermissionDecision allowed = new PermissionDecision.Allowed(
                    new RowScope.Unrestricted(), Set.of());

            RenderedSql r = AskSqlRenderer.render(
                    dsl(List.of("订单金额"), List.of(), null, List.of()),
                    m, SqlDialects.of("MYSQL5.7"), allowed, TODAY);

            assertThat(r.sql())
                    .as("没有谓词要用 org_path，就不该把维表拉进来")
                    .doesNotContain("dim_org");
        }
    }
}

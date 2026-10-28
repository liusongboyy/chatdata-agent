package com.liusongboyy.chatdata.engine.nlsql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liusongboyy.chatdata.engine.datasource.dialect.SqlDialects;
import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import com.liusongboyy.chatdata.engine.permission.PermissionContext;
import com.liusongboyy.chatdata.engine.permission.PermissionDecision;
import com.liusongboyy.chatdata.engine.permission.PermissionGuard;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;
import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把渲染出的 SQL **真的执行一遍**，用真实数据核对结果。
 *
 * <p>这是渲染器唯一的"真"验证。前面那些测试断言的是 SQL 文本，文本对了不等于
 * 语义对了——列名可能写错、参数可能错位、权限谓词可能注到了错误的表上，
 * 这些只有跑一遍才知道。
 *
 * <p>连不上库时**跳过**而不是失败，这样在没有 MySQL 的环境里也能构建。
 * 但要注意：跳过意味着**本次构建没有验证这一层**。
 */
class AskSqlRendererRealDbTest {

    private static final String URL =
            "jdbc:mysql://127.0.0.1:3306/chatdata_demo?useUnicode=true&characterEncoding=UTF-8"
                    + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    private static final String USER = "root";
    private static final String PASSWORD = "root";
    private static final ObjectMapper M = new ObjectMapper();

    /** 金标准由 SQL 直接算出，不是渲染器给的。 */
    private static final BigDecimal GOLD_华东_生效 = new BigDecimal("369140.20");
    private static final BigDecimal GOLD_华北_生效 = new BigDecimal("337711.23");

    private static boolean dbAvailable;

    @BeforeAll
    static void checkDb() {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD)) {
            try (ResultSet rs = c.createStatement().executeQuery("SELECT 1")) {
                dbAvailable = rs.next();
            }
        } catch (Exception e) {
            dbAvailable = false;
        }
        Assumptions.assumeTrue(dbAvailable,
                "本地 MySQL 不可用，跳过渲染器的真库验证");
    }

    /** 按真实表 ins_policy 建语义模型。 */
    private static SemanticModel model(DomainPermission perm) {
        RegisteredTable policy = new RegisteredTable("tbl-policy", "ins_policy", "保单表",
                RegisteredTable.Mode.TABLE, null, true, List.of(
                new ColumnDef("premium", "decimal", Aggregation.SUM, "保费", true),
                new ColumnDef("region", "varchar", Aggregation.NONE, "区域", true),
                new ColumnDef("status", "varchar", Aggregation.NONE, "状态", true),
                new ColumnDef("effect_date", "date", Aggregation.NONE, "生效日期", true)));

        Domain domain = new Domain("d1", "保险分析域", "ds1", "MYSQL5.7", true,
                List.of(policy), null, perm, List.of(), List.of());
        return new SemanticModel(domain);
    }

    private static DomainPermission permOf(String json) {
        try {
            return DomainPermission.from(M.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException("夹具 JSON 写错了", e);
        }
    }

    /** 执行渲染结果，取第一行第一列。 */
    private static Object execute(RenderedSql r) throws Exception {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             PreparedStatement ps = c.prepareStatement(r.sql())) {
            for (int i = 0; i < r.params().size(); i++) {
                ps.setObject(i + 1, r.params().get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("查询应当至少返回一行").isTrue();
                return rs.getObject(1);
            }
        }
    }

    @Test
    @DisplayName("★ 渲染出的 SQL 真跑一遍，答案与金标准一致")
    void renderedSqlProducesCorrectAnswer() throws Exception {
        DomainPermission perm = permOf("""
                {"v":1,"grantRoles":["分析师"],"unrestrictedRoles":["分析师"]}""");
        SemanticModel m = model(perm);
        PermissionDecision ok = PermissionGuard.check(null, perm, PermissionContext.of("u1", Set.of("分析师")));

        AskDsl dsl = new AskDsl(List.of("保费"), List.of(), null, List.of(
                new AskDsl.Filter("区域", "=", "华东"),
                new AskDsl.Filter("状态", "=", "生效")));

        RenderedSql r = AskSqlRenderer.render(dsl, m, SqlDialects.of("MYSQL5.7"), ok, java.time.LocalDate.now());

        System.out.println("[真库验证] 渲染出的 SQL：");
        System.out.println("  " + r.sql());
        System.out.println("  参数：" + r.params());

        BigDecimal actual = new BigDecimal(String.valueOf(execute(r)));

        assertThat(actual)
                .as("渲染出的 SQL 跑出来的结果必须等于金标准——这是整条链路唯一的真验证")
                .isEqualByComparingTo(GOLD_华东_生效);
    }

    @Test
    @DisplayName("★★ 同一 DSL、仅切换权限，真跑出来的结果不同 —— 证明权限确实生效")
    void sameDslDifferentPermissionYieldsDifferentResult() throws Exception {
        AskDsl dsl = new AskDsl(List.of("保费"), List.of(), null, List.of(
                new AskDsl.Filter("状态", "=", "生效")));

        // ① 华东区域经理：行规则限定 region = 华东
        DomainPermission eastPerm = permOf("""
                {"v":1,"grantRoles":["区域经理"],
                 "rowRules":[{"id":1,"role":"区域经理","field":"region","op":"=","value":"华东"}]}""");
        SemanticModel eastModel = model(eastPerm);
        PermissionDecision eastOk = PermissionGuard.check(
                null, eastPerm, PermissionContext.of("u1", Set.of("区域经理")));
        RenderedSql eastSql = AskSqlRenderer.render(
                dsl, eastModel, SqlDialects.of("MYSQL5.7"), eastOk, java.time.LocalDate.now());

        // ② 华北区域经理：同一条 DSL，仅权限不同
        DomainPermission northPerm = permOf("""
                {"v":1,"grantRoles":["区域经理"],
                 "rowRules":[{"id":1,"role":"区域经理","field":"region","op":"=","value":"华北"}]}""");
        SemanticModel northModel = model(northPerm);
        PermissionDecision northOk = PermissionGuard.check(
                null, northPerm, PermissionContext.of("u1", Set.of("区域经理")));
        RenderedSql northSql = AskSqlRenderer.render(
                dsl, northModel, SqlDialects.of("MYSQL5.7"), northOk, java.time.LocalDate.now());

        System.out.println("[真库验证] 华东经理 SQL：");
        System.out.println("  " + eastSql.sql() + "  参数=" + eastSql.params());
        System.out.println("[真库验证] 华北经理 SQL：");
        System.out.println("  " + northSql.sql() + "  参数=" + northSql.params());

        BigDecimal east = new BigDecimal(String.valueOf(execute(eastSql)));
        BigDecimal north = new BigDecimal(String.valueOf(execute(northSql)));

        assertThat(east).as("华东经理只应看到华东").isEqualByComparingTo(GOLD_华东_生效);
        assertThat(north).as("华北经理只应看到华北").isEqualByComparingTo(GOLD_华北_生效);
        assertThat(east)
                .as("同一问题、仅切换权限，结果必须不同——否则权限根本没生效")
                .isNotEqualByComparingTo(north);
    }

    @Test
    @DisplayName("★ 脱敏列真跑出来是 *** —— 数据根本没出库")
    void maskedColumnReallyReturnsPlaceholder() throws Exception {
        DomainPermission perm = permOf("""
                {"v":1,"grantRoles":["分析师"],"unrestrictedRoles":["分析师"],
                 "colRules":[{"id":1,"role":"分析师","field":"premium","action":"脱敏"}]}""");
        SemanticModel m = model(perm);
        PermissionDecision ok = PermissionGuard.check(null, perm, PermissionContext.of("u1", Set.of("分析师")));

        AskDsl dsl = new AskDsl(List.of("保费"), List.of(), null,
                List.of(new AskDsl.Filter("区域", "=", "华东")));

        RenderedSql r = AskSqlRenderer.render(dsl, m, SqlDialects.of("MYSQL5.7"), ok, java.time.LocalDate.now());

        System.out.println("[真库验证] 脱敏 SQL：");
        System.out.println("  " + r.sql());

        assertThat(String.valueOf(execute(r)))
                .as("SELECT 里就是常量 '***'，真实金额从未离开数据库")
                .isEqualTo("***");
    }
}

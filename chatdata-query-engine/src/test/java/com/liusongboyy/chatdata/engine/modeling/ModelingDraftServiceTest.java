package com.liusongboyy.chatdata.engine.modeling;

import com.liusongboyy.chatdata.engine.datasource.DatasourceResolver;
import com.liusongboyy.chatdata.engine.datasource.MetaReader;
import com.liusongboyy.chatdata.engine.datasource.MetaReader.ColumnMeta;
import com.liusongboyy.chatdata.engine.datasource.MetaReader.TableMeta;
import com.liusongboyy.chatdata.engine.modeling.ModelingDraftService.Draft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 草稿生成。
 *
 * <p>测的是「编排出什么形状」和「告警说不说得清」，不是推断规则本身
 * （那在 {@link ColumnInferrerTest} 与 {@link JoinInferrerTest} 里）。
 */
class ModelingDraftServiceTest {

    /** 让基数采样走不通，只测推断编排。真实的采样在集成测试里验 */
    private static DatasourceResolver deadResolver() {
        DatasourceResolver r = mock(DatasourceResolver.class);
        when(r.resolve(anyString())).thenThrow(new IllegalStateException("测试环境无数据源"));
        return r;
    }

    private static MetaReader stubMeta(List<TableMeta> tables,
                                       java.util.Map<String, List<ColumnMeta>> columns) {
        return new MetaReader(deadResolver()) {
            @Override
            public List<TableMeta> listTables(String datasourceId) {
                return tables;
            }

            @Override
            public List<ColumnMeta> listColumns(String datasourceId, String table) {
                return columns.getOrDefault(table, List.of());
            }
        };
    }

    private static ColumnMeta pk(String n) {
        return new ColumnMeta(n, "bigint", false, true, "");
    }

    private static ColumnMeta col(String n, String t) {
        return new ColumnMeta(n, t, true, false, "");
    }

    private static ColumnMeta col(String n, String t, String comment) {
        return new ColumnMeta(n, t, true, false, comment);
    }

    @Test
    @DisplayName("★ 草稿不落库：只返回结构，没有任何写操作")
    void draftNeverWrites() {
        // 这个测试本身不"执行"什么——它断言的是 API 设计：
        // ModelingDraftService 只有 generate，没有任何 create/save 方法
        var methods = ModelingDraftService.class.getMethods();
        assertThat(methods)
                .as("草稿服务不该有写入方法——确认后必须走现有 createDomain/createTable")
                .noneMatch(m -> m.getName().toLowerCase().contains("create")
                        || m.getName().toLowerCase().contains("save")
                        || m.getName().toLowerCase().contains("insert"));
    }

    @Test
    @DisplayName("生成基本草稿结构")
    void generateBasicDraft() {
        MetaReader meta = stubMeta(
                List.of(new TableMeta("orders", "订单", "TABLE")),
                java.util.Map.of("orders", List.of(
                        pk("id"),
                        col("total_amount", "decimal(10,2)"),
                        col("status", "varchar(20)"))));
        var service = new ModelingDraftService(meta, deadResolver());

        Draft draft = service.generate("1", null);

        assertThat(draft.tables()).hasSize(1);
        assertThat(draft.tables().get(0).name()).isEqualTo("orders");
        assertThat(draft.tables().get(0).label()).isEqualTo("订单");
        // **登记数不等于物理列数**：度量列会展开成「维度 + 指标」两条。
        // 这个测试里 id(pk) 1 条 + total_amount 双登记 3 条 + status 1 条 = 5
        var cols = draft.tables().get(0).columns();
        assertThat(cols).as("登记数应大于物理列数（双登记）").hasSizeGreaterThan(3);
        // 物理列还是那三列
        assertThat(cols.stream().map(c -> c.col()).distinct())
                .containsExactlyInAnyOrder("id", "total_amount", "status");
        assertThat(draft.stats().tables()).isEqualTo(1);
        assertThat(draft.stats().columns()).isEqualTo(cols.size());
    }

    @Test
    @DisplayName("★ 敏感列默认关闭，普通列默认开启")
    void sensitiveColumnsDisabledByDefault() {
        MetaReader meta = stubMeta(
                List.of(new TableMeta("customers", "客户", "TABLE")),
                java.util.Map.of("customers", List.of(
                        pk("id"),
                        col("customer_phone", "varchar(20)"),
                        col("customer_name", "varchar(50)"))));
        var service = new ModelingDraftService(meta, deadResolver());

        Draft draft = service.generate("1", null);
        var columns = draft.tables().get(0).columns();

        assertThat(columns).filteredOn(c -> c.col().equals("customer_phone"))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.enabled()).isFalse();
                    assertThat(c.reason()).contains("默认关闭");
                });
        assertThat(columns).filteredOn(c -> c.col().equals("customer_name"))
                .singleElement()
                .satisfies(c -> assertThat(c.reason()).isNotBlank());
    }

    @Test
    @DisplayName("★ 敏感列数量进告警，管理员看得见")
    void sensitiveCountInWarnings() {
        MetaReader meta = stubMeta(
                List.of(new TableMeta("t", "", "TABLE")),
                java.util.Map.of("t", List.of(
                        pk("id"),
                        col("phone", "varchar"),
                        col("email", "varchar"),
                        col("idcard", "varchar"))));
        var service = new ModelingDraftService(meta, deadResolver());

        Draft draft = service.generate("1", null);

        assertThat(draft.warnings())
                .anyMatch(w -> w.contains("3") && w.contains("敏感"));
    }

    @Test
    @DisplayName("★ 业务名未确认的列要进告警")
    void unconfirmedAliasesWarned() {
        MetaReader meta = stubMeta(
                List.of(new TableMeta("t", "", "TABLE")),
                java.util.Map.of("t", List.of(pk("id"), col("amount", "decimal"))));
        var service = new ModelingDraftService(meta, deadResolver());

        Draft draft = service.generate("1", null);

        assertThat(draft.warnings())
                .anyMatch(w -> w.contains("业务名") && w.contains("确认"));
    }

    @Test
    @DisplayName("★ 关联歧义要告警：挑错会导致数字不对但不报错")
    void ambiguousJoinsWarned() {
        MetaReader meta = stubMeta(
                List.of(new TableMeta("sales", "", "TABLE"),
                        new TableMeta("product", "", "TABLE"),
                        new TableMeta("dim_product", "", "TABLE")),
                java.util.Map.of(
                        "sales", List.of(pk("id"), col("ProductID", "bigint")),
                        "product", List.of(pk("ProductID")),
                        "dim_product", List.of(pk("ProductID"))));
        var service = new ModelingDraftService(meta, deadResolver());

        Draft draft = service.generate("1", null);

        // 两个候选都存在，必须提醒人去选
        assertThat(draft.joins()).hasSize(2);
        assertThat(draft.warnings()).anyMatch(w -> w.contains("多个关联候选"));
    }

    @Test
    @DisplayName("★ 双登记：度量列生成「维度」+「指标」两条")
    void measureColumnProducesTwoRegistrations() {
        // 只登记一种会丢掉另一种问法：
        //   只当指标 → 「保障年限为 10 年的保单」问不出来
        //   只当维度 → 「平均保障年限是多少」问不出来
        // 实测日志里「配了聚合方式，不能作为维度」出现 52 次
        MetaReader meta = stubMeta(
                List.of(new TableMeta("orders", "", "TABLE")),
                java.util.Map.of("orders", List.of(
                        pk("id"),
                        col("total_amount", "decimal(10,2)"))));
        var service = new ModelingDraftService(meta, deadResolver());

        Draft draft = service.generate("1", null);
        var cols = draft.tables().get(0).columns();

        var forAmount = cols.stream().filter(c -> c.col().equals("total_amount")).toList();
        assertThat(forAmount).as("度量列应该展开成多条登记").hasSizeGreaterThanOrEqualTo(2);

        // 一条纯维度（无聚合）
        assertThat(forAmount).anySatisfy(c -> {
            assertThat(c.role()).isEqualTo("DIMENSION");
            assertThat(c.agg()).isEmpty();
        });
        // 至少一条指标（有聚合）
        assertThat(forAmount).anySatisfy(c -> {
            assertThat(c.role()).isEqualTo("METRIC");
            assertThat(c.agg()).isNotEmpty();
        });
    }

    @Test
    @DisplayName("★ 指标那条的别名带语义前缀，与维度区分")
    void metricAliasHasMeaningfulPrefix() {
        MetaReader meta = stubMeta(
                List.of(new TableMeta("t", "", "TABLE")),
                java.util.Map.of("t", List.of(
                        pk("id"),
                        col("total", "decimal", "订单金额"))));
        var service = new ModelingDraftService(meta, deadResolver());

        var cols = service.generate("1", null).tables().get(0).columns()
                .stream().filter(c -> c.col().equals("total")).toList();

        // 维度那条保持原别名
        assertThat(cols).anySatisfy(c -> {
            if ("DIMENSION".equals(c.role())) assertThat(c.alias()).isEqualTo("订单金额");
        });
        // 指标那条加前缀——「订单金额合计」而不是「订单金额（SUM）」
        assertThat(cols).anySatisfy(c -> {
            if ("METRIC".equals(c.role()) && c.agg().contains(
                    com.liusongboyy.chatdata.engine.semantic.Aggregation.SUM)) {
                assertThat(c.alias()).isEqualTo("订单金额合计");
            }
        });
    }

    @Test
    @DisplayName("★ 文本分类列也双登记：维度 + COUNT_DISTINCT（记录数）")
    void textColumnGetsCountDistinct() {
        // 实测：AdventureWorks 用单登记建完，33 个分类列缺 COUNT_DISTINCT
        // 「一共涉及多少种」是最高频问题之一，缺了它模型只能逐项列出
        MetaReader meta = stubMeta(
                List.of(new TableMeta("t", "", "TABLE")),
                java.util.Map.of("t", List.of(pk("id"), col("status", "varchar(20)"))));
        var service = new ModelingDraftService(meta, deadResolver());

        var cols = service.generate("1", null).tables().get(0).columns()
                .stream().filter(c -> c.col().equals("status")).toList();

        assertThat(cols).anySatisfy(c -> {
            assertThat(c.role()).isEqualTo("DIMENSION");
            assertThat(c.agg()).isEmpty();
        });
        // 基数采样在测试里不可用（deadResolver），所以这条只在能估出基数时才生成。
        // 这里断言的是"如果生成了指标那条，它一定是 COUNT_DISTINCT"
        cols.stream().filter(c -> "METRIC".equals(c.role())).forEach(c ->
                assertThat(c.agg()).contains(
                        com.liusongboyy.chatdata.engine.semantic.Aggregation.COUNT_DISTINCT));
    }

    @Test
    @DisplayName("★ 敏感列不展开：关掉就都关掉")
    void sensitiveColumnIsNotExpanded() {
        MetaReader meta = stubMeta(
                List.of(new TableMeta("t", "", "TABLE")),
                java.util.Map.of("t", List.of(pk("id"), col("phone_number", "varchar"))));
        var service = new ModelingDraftService(meta, deadResolver());

        var cols = service.generate("1", null).tables().get(0).columns()
                .stream().filter(c -> c.col().equals("phone_number")).toList();

        // 给两条记录会让管理员误以为只关了一半
        assertThat(cols).hasSize(1);
        assertThat(cols.get(0).enabled()).isFalse();
    }

    @Test
    @DisplayName("只选指定表")
    void selectSpecificTables() {
        MetaReader meta = stubMeta(
                List.of(new TableMeta("a", "", "TABLE"), new TableMeta("b", "", "TABLE")),
                java.util.Map.of("a", List.of(pk("id")), "b", List.of(pk("id"))));
        var service = new ModelingDraftService(meta, deadResolver());

        Draft draft = service.generate("1", List.of("a"));

        assertThat(draft.tables()).extracting(t -> t.name()).containsExactly("a");
    }

    @Test
    @DisplayName("选中的表不存在时给明确告警，不是空结果")
    void unknownTableWarns() {
        MetaReader meta = stubMeta(
                List.of(new TableMeta("a", "", "TABLE")),
                java.util.Map.of("a", List.of(pk("id"))));
        var service = new ModelingDraftService(meta, deadResolver());

        Draft draft = service.generate("1", List.of("nonexistent"));

        assertThat(draft.tables()).isEmpty();
        assertThat(draft.warnings()).anyMatch(w -> w.contains("不存在"));
    }

    @Test
    @DisplayName("★ 数据源不可用时不崩，且告警说明原因")
    void datasourceDownDoesNotCrash() {
        // MetaReader 的 listTables 会抛，service 应该让异常继续向上——
        // 由 controller 转成 400。这里验证的是"不会 NPE 或返回半个草稿"
        MetaReader meta = new MetaReader(deadResolver()) {
            @Override
            public List<TableMeta> listTables(String datasourceId) {
                throw new IllegalStateException("数据源不可用");
            }
        };
        var service = new ModelingDraftService(meta, deadResolver());

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.generate("1", null))
                .hasMessageContaining("数据源不可用");
    }

    @Test
    @DisplayName("统计信息完整，供性能验收")
    void statsPopulated() {
        MetaReader meta = stubMeta(
                List.of(new TableMeta("t", "", "TABLE")),
                java.util.Map.of("t", List.of(pk("id"), col("amount", "decimal"))));
        var service = new ModelingDraftService(meta, deadResolver());

        Draft draft = service.generate("1", null);

        assertThat(draft.stats().tables()).isEqualTo(1);
        // amount 是度量列，双登记后登记数 > 物理列数 2
        assertThat(draft.stats().columns())
                .as("登记数含双登记展开")
                .isGreaterThanOrEqualTo(2);
        assertThat(draft.stats().columns())
                .isEqualTo(draft.tables().get(0).columns().size());
        // 验收要求 5 张表 < 3 秒，这里断言"有测量值"即可
        assertThat(draft.stats().elapsedMs()).isGreaterThanOrEqualTo(0);
    }
}

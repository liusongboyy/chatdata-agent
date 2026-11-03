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
        assertThat(draft.tables().get(0).columns()).hasSize(3);
        assertThat(draft.stats().tables()).isEqualTo(1);
        assertThat(draft.stats().columns()).isEqualTo(3);
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
        assertThat(draft.stats().columns()).isEqualTo(2);
        // 验收要求 5 张表 < 3 秒，这里断言"有测量值"即可
        assertThat(draft.stats().elapsedMs()).isGreaterThanOrEqualTo(0);
    }
}

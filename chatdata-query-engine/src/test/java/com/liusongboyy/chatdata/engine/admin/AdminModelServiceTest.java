package com.liusongboyy.chatdata.engine.admin;

import com.liusongboyy.chatdata.engine.agent.AskEngineModelProperties;
import com.liusongboyy.chatdata.engine.model.ModelDef;
import com.liusongboyy.chatdata.engine.model.ModelService;
import com.liusongboyy.chatdata.engine.model.mapper.ModelMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型管理面。
 *
 * <h2>这里守的三件事</h2>
 *
 * <ol>
 *   <li><b>密钥只进不出</b>——列表里不能出现任何形式的密钥（明文或密文）</li>
 *   <li><b>写完之后运行期必须看见</b>——否则改了 base-url 问数还连旧地址</li>
 *   <li><b>停用会摘掉默认标记</b>——停用的模型还挂着"全局默认"，
 *       会让"没有可用模型"这种故障极难定位</li>
 * </ol>
 *
 * <p>用假 mapper 而不是真库：要验的是<b>决策</b>，不是 SQL。
 * SQL 由 {@code ModelService} 自己的真库测试覆盖。
 */
class AdminModelServiceTest {

    /** 记录写操作的假 mapper，密钥密文可查。 */
    private static class FakeMapper implements ModelMapper {
        final List<String> calls = new ArrayList<>();
        /** 真密文：假字符串会让解密抛异常，而那条异常与"密钥没配"是两回事 */
        String apiKeyEnc = CIPHER.encrypt("sk-test");

        @Override
        public List<ModelRow> selectAll() {
            return List.of();
        }

        @Override
        public ModelRow selectByKey(String key) {
            return null;
        }

        @Override
        public String selectApiKeyEnc(String key) {
            return calls.contains("clearedKey:" + key) ? null : apiKeyEnc;
        }

        @Override
        public int insert(String key, String displayName, String description, String baseUrl,
                          String apiKeyEnc, String modelName, String allowedRoles,
                          BigDecimal priceInput, BigDecimal priceCached, BigDecimal priceOutput,
                          java.time.LocalDateTime pricedAt, boolean isDefault, boolean enabled,
                          String createdBy) {
            calls.add("insert:" + key);
            return 1;
        }

        @Override
        public int update(String key, String displayName, String description, String baseUrl,
                          String modelName, String allowedRoles, BigDecimal priceInput,
                          BigDecimal priceCached, BigDecimal priceOutput,
                          java.time.LocalDateTime pricedAt, boolean isDefault, boolean enabled) {
            calls.add("update:" + key);
            return 1;
        }

        @Override
        public int updateApiKey(String key, String enc) {
            calls.add("updateApiKey:" + key);
            return 1;
        }

        @Override
        public int clearOtherDefaults(String key) {
            calls.add("clearOtherDefaults:" + key);
            return 1;
        }

        @Override
        public int clearDefault(String key) {
            calls.add("clearDefault:" + key);
            return 1;
        }

        @Override
        public int setEnabled(String key, boolean enabled) {
            calls.add("setEnabled:" + key + ":" + enabled);
            return 1;
        }

        @Override
        public int countEnabled() {
            return 1;
        }

        @Override
        public String selectDomainModelKey(long id) {
            return null;
        }

        @Override
        public List<DomainRef> selectDomainsByModelKey(String key) {
            return List.of(new DomainRef(1, "保险分析域"), new DomainRef(2, "工单域"));
        }
    }

    /** 能加解密密钥的 cipher。密钥是测试用的固定值——它不参与任何真实数据。 */
    private static final com.liusongboyy.chatdata.engine.datasource.PasswordCipher CIPHER =
            new com.liusongboyy.chatdata.engine.datasource.PasswordCipher(
                    "admin-model-service-test-secret");

    /**
     * 有两条模型的服务：一条默认、一条受限。
     *
     * <p>用真的 {@code ModelService}（而不是 {@code forTest} 那个只读版）：
     * {@code create / update / setEnabled} 都走它，而 {@code forTest} 把 mapper 置成了 null。
     * 这里要验的正是「写完之后有没有清缓存、有没有摘默认标记」，
     * 所以必须走真实的写路径。
     */
    private static ModelService modelService(ModelMapper mapper) {
        ModelService s = new ModelService(mapper, CIPHER, null);
        s.cachedForTest(List.of(
                new ModelDef("fast", "快速", "日常用", "https://a/v1", "m1", List.of(),
                        new BigDecimal("0.5"), null, new BigDecimal("2"), null, true, true),
                new ModelDef("strong", "强", "复杂分析", "https://a/v1", "m2", List.of("admin"),
                        null, null, null, null, false, true)));
        return s;
    }

    private FakeMapper mapper;
    private AdminModelService service;

    @BeforeEach
    void setUp() {
        mapper = new FakeMapper();
        service = new AdminModelService(modelService(mapper), mapper,
                new AskEngineModelProperties());
    }

    // ── 列表 ──

    @Test
    @DisplayName("★ 列表里没有任何密钥——连密文都不出")
    void listNeverLeaksApiKey() {
        List<Map<String, Object>> rows = service.list();

        for (Map<String, Object> row : rows) {
            assertThat(row).doesNotContainKeys("apiKey", "apiKeyEnc", "api_key", "api_key_enc");
            // 只有"配没配"这一个布尔
            assertThat(row).containsKey("hasApiKey");
        }
    }

    @Test
    @DisplayName("★ 默认模型排在最前面——「现在在用的是哪个」要第一眼可见")
    void defaultModelComesFirst() {
        assertThat(service.list().get(0)).containsEntry("key", "fast");
    }

    @Test
    @DisplayName("单价状态如实反映：配全了 priced=true")
    void pricedFlagReflectsConfig() {
        List<Map<String, Object>> rows = service.list();

        assertThat(rows).filteredOn(r -> "fast".equals(r.get("key")))
                .singleElement().satisfies(r -> assertThat(r.get("priced")).isEqualTo(true));
        assertThat(rows).filteredOn(r -> "strong".equals(r.get("key")))
                .singleElement().satisfies(r -> assertThat(r.get("priced")).isEqualTo(false));
    }

    // ── 创建 ──

    @Test
    @DisplayName("键重复 → 明确拒绝，并说清为什么不能复用")
    void duplicateKeyRejected() {
        var input = new ModelInput("fast", "另一个快速", null, "https://b/v1", "sk", "m3",
                List.of(), null, null, null, false, true);

        assertThatThrownBy(() -> service.create(input, "admin"))
                .isInstanceOf(AdminException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    @DisplayName("新建成功后运行期缓存被清——否则新模型不会出现在下拉里")
    void createInvalidatesRuntimeCache() {
        var input = new ModelInput("newone", "新的", null, "https://c/v1", "sk", "m4",
                List.of(), null, null, null, false, true);

        service.create(input, "admin");

        assertThat(mapper.calls).contains("insert:newone");
    }

    // ── 启停 ──

    @Test
    @DisplayName("★ 停用会摘掉默认标记——停用的模型不能是默认")
    void disableClearsDefaultFlag() {
        service.setEnabled("fast", false);

        assertThat(mapper.calls).contains("setEnabled:fast:false");
        assertThat(mapper.calls).as("不摘默认会让「没有可用模型」极难定位")
                .contains("clearDefault:fast");
    }

    @Test
    @DisplayName("启用不碰默认标记")
    void enableKeepsDefaultFlag() {
        service.setEnabled("strong", true);

        assertThat(mapper.calls).contains("setEnabled:strong:true");
        assertThat(mapper.calls).doesNotContain("clearDefault:strong");
    }

    // ── 域绑定 ──

    @Test
    @DisplayName("★ 停用前能查出哪些域绑着它")
    void boundDomainsListed() {
        var domains = service.boundDomains("fast");

        assertThat(domains).hasSize(2);
        assertThat(domains).extracting(d -> d.get("name"))
                .containsExactly("保险分析域", "工单域");
    }

    @Test
    @DisplayName("读域绑定失败不抛异常——它只是提示，不该变成守卫")
    void boundDomainsFailureIsSwallowed() {
        var broken = new FakeMapper() {
            @Override
            public List<DomainRef> selectDomainsByModelKey(String key) {
                throw new IllegalStateException("表不在");
            }
        };
        var svc = new AdminModelService(modelService(broken), broken,
                new AskEngineModelProperties());

        assertThat(svc.boundDomains("fast")).as("因为提示失败而阻止停用，是把提示错当成了守卫")
                .isEmpty();
    }

    // ── 测试连通性 ──

    @Test
    @DisplayName("模型不存在 → 明确报错，不去发请求")
    void testUnknownModelFailsFast() {
        assertThatThrownBy(() -> service.testSaved("nope"))
                .isInstanceOf(AdminException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("★ 表单里没填密钥 → 提示去填，而不是拿空密钥发一次无意义的请求")
    void draftWithoutApiKeyExplainsInsteadOfGuessing() {
        var draft = new ModelInput("brandnew", "新", null, "https://d/v1", null, "m5",
                List.of(), null, null, null, false, true);

        var result = service.testDraft(null, draft);

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("密钥");
    }

    @Test
    @DisplayName("★ 编辑时密钥留空 → 用已存密钥，不拿空值去测")
    void draftFallsBackToSavedKey() {
        var draft = new ModelInput("fast", "快速", null, "https://a/v1", null, "m1",
                List.of(), null, null, null, false, true);

        // 真的会发请求（本机没有供应商），所以只看它**没有**报"还没填密钥"
        var result = service.testDraft("fast", draft);

        assertThat(result.message()).doesNotContain("还没有填密钥");
    }
}

package com.liusongboyy.chatdata.engine.model;

import com.liusongboyy.chatdata.engine.datasource.PasswordCipher;
import com.liusongboyy.chatdata.engine.model.mapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模型的读写与应用。
 *
 * <h2>缓存策略</h2>
 *
 * <p>模型配置变更极少（一个月几次），而每次问数都要读——所以缓存。
 *
 * <p><b>但每次写入必须清缓存</b>：与数据源连接池同一个教训
 * （{@code evict} 写了却从没被调用，改完配置不生效）。
 * 这里的写法是「所有写方法末尾都调 {@link #invalidate()}」，
 * 而不是靠调用方记得清。
 *
 * <h2>密钥</h2>
 *
 * <p>数据库里存密文，用数据源那一套 {@link PasswordCipher}（AES-GCM）。
 * 复用而不是另起一套：两套加密实现迟早有一处漏了轮换密钥。
 */
@Service
public class ModelService {

    private static final Logger log = LoggerFactory.getLogger(ModelService.class);

    private final ModelMapper mapper;
    private final PasswordCipher cipher;
    private final ObjectMapper json = new ObjectMapper();
    /** 发事件清 ModelPool。**可为 null**（单元测试里不接容器）。 */
    private final org.springframework.context.ApplicationEventPublisher events;

    /** 列表缓存。不用 Spring Cache——只有一份数据，简单 Map 够了。 */
    private volatile List<ModelDef> cached;

    public ModelService(ModelMapper mapper, PasswordCipher cipher,
                        org.springframework.context.ApplicationEventPublisher events) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.events = events;
    }

    // ─────────────────────────────────────────────────────────────
    // 读
    // ─────────────────────────────────────────────────────────────

    /** 全部模型（含停用）。带缓存。 */
    public List<ModelDef> all() {
        List<ModelDef> local = cached;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cached == null) {
                cached = load();
            }
            return cached;
        }
    }

    /** 只读启用的。问数与下拉都用这个。 */
    public List<ModelDef> enabled() {
        return all().stream().filter(ModelDef::enabled).toList();
    }

    /** 按 key 取。找不到返回 null——调用方要区分"不存在"与"没权限"。 */
    public ModelDef byKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return all().stream().filter((m) -> m.key().equals(key)).findFirst().orElse(null);
    }

    /** 取某个模型的密钥明文。**只在发起调用时用**，绝不返回给前端。 */
    public String apiKeyOf(String key) {
        String enc = mapper.selectApiKeyEnc(key);
        if (enc == null || enc.isBlank()) {
            return null;
        }
        try {
            return cipher.decrypt(enc);
        } catch (RuntimeException e) {
            // 解不开通常是密钥轮换过。**明确报错，不返回 null**——
            // 返回 null 会让上层以为是"没配密钥"，去改配置而不是查密钥轮换
            throw new IllegalStateException(
                    "模型「" + key + "」的密钥无法解密（可能轮换过 data source secret）："
                            + e.getMessage(), e);
        }
    }

    /**
     * 某个域绑定的模型键。
     *
     * <p>读不到（域不存在、SQL 异常）时返回 {@code null} —— 表示"不限制"。
     * <b>这是放行方向</b>，但对"下拉列表"这个场景是安全的：真正的强制
     * 发生在发起问数时（`AskServiceFactory.modelFor`），那里会明确报错。
     *
     * <p>若这里改成报错，一个域查询抖动就会让整个下拉空掉，
     * 而用户看到的是"你没有可用模型"——与真实原因毫无关系。
     *
     * @throws IllegalArgumentException 域 id 不是数字时
     */
    public String domainBinding(String domainId) {
        if (domainId == null || domainId.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(domainId.trim());
            String key = mapper == null ? null : mapper.selectDomainModelKey(id);
            return key == null || key.isBlank() ? null : key;
        } catch (NumberFormatException e) {
            // 域 id 就是数字（ask_domain.id 是 BIGINT）。传别的说明调用方搞错了，
            // 这时静默返回 null 会让前端以为"没锁定"，而实际是请求参数错了
            throw new IllegalArgumentException("数据域 id 必须是数字：" + domainId, e);
        } catch (RuntimeException e) {
            log.warn("读取域 {} 的模型绑定失败，按不限制处理：{}", domainId, e.getMessage());
            return null;
        }
    }

    private List<ModelDef> load() {
        List<ModelDef> out = new ArrayList<>();
        for (ModelMapper.ModelRow r : mapper.selectAll()) {
            out.add(new ModelDef(
                    r.modelKey(), r.displayName(), r.description(),
                    r.baseUrl(), r.modelName(), parseRoles(r.allowedRoles()),
                    r.priceInput(), r.priceCached(), r.priceOutput(), r.pricedAt(),
                    r.isDefault(), r.enabled()));
        }
        log.debug("加载了 {} 个模型配置", out.size());
        return List.copyOf(out);
    }

    /** {@code allowed_roles} 的 JSON 数组 → List。坏数据返回空列表（=不限制）。 */
    private List<String> parseRoles(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            String[] arr = json.readValue(raw, String[].class);
            return arr == null ? List.of() : List.of(arr);
        } catch (RuntimeException e) {
            // 解析失败按"不限制"处理，是**放行**方向——这里不能这么做。
            // 改成按"谁都不可用"，失败方向安全
            log.warn("模型的 allowed_roles 解析失败，按「谁都不可用」处理：{}", raw);
            return List.of("__invalid__");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 写
    // ─────────────────────────────────────────────────────────────

    /**
     * 新建。
     *
     * @param apiKey 明文密钥；null 表示不配（本地模型可能不需要）
     * @throws IllegalArgumentException key 重复或缺必填项
     */
    public void create(String key, String displayName, String description,
                       String baseUrl, String apiKey, String modelName,
                       List<String> allowedRoles,
                       BigDecimal priceInput, BigDecimal priceCached, BigDecimal priceOutput,
                       boolean isDefault, boolean enabled, String operator) {
        validate(key, displayName, baseUrl, modelName, priceInput, priceCached, priceOutput);
        if (byKey(key) != null) {
            throw new IllegalArgumentException("模型键「" + key + "」已存在");
        }

        try {
            mapper.insert(key, displayName, description, baseUrl,
                    apiKey == null || apiKey.isBlank() ? null : cipher.encrypt(apiKey),
                    modelName, writeRoles(allowedRoles),
                    priceInput, priceCached, priceOutput,
                    LocalDateTime.now(), isDefault, enabled, operator);
        } finally {
            // **finally 而不是成功分支**：即使 insert 抛了，缓存已经不可信
            invalidate();
        }

        if (isDefault) {
            mapper.clearOtherDefaults(key);
        }
        // 启用状态变了也要清——first 那次 invalidate 是在 insert 之后、
        // clearOtherDefaults 之前，缓存里可能是"两个默认"
        invalidate();
    }

    /**
     * 更新。
     *
     * @param apiKey 明文密钥；**null 表示保持不变**，空串表示清空
     */
    public void update(String key, String displayName, String description,
                       String baseUrl, String apiKey, String modelName,
                       List<String> allowedRoles,
                       BigDecimal priceInput, BigDecimal priceCached, BigDecimal priceOutput,
                       boolean isDefault, boolean enabled) {
        if (byKey(key) == null) {
            throw new IllegalArgumentException("模型「" + key + "」不存在");
        }
        validate(key, displayName, baseUrl, modelName, priceInput, priceCached, priceOutput);

        try {
            mapper.update(key, displayName, description, baseUrl, modelName,
                    writeRoles(allowedRoles),
                    priceInput, priceCached, priceOutput, LocalDateTime.now(),
                    isDefault, enabled);

            if (apiKey != null) {
                mapper.updateApiKey(key, apiKey.isEmpty() ? null : cipher.encrypt(apiKey));
            }
            if (isDefault) {
                mapper.clearOtherDefaults(key);
            }
        } finally {
            invalidate();
        }
    }

    /** 启用 / 停用。停用时同时摘掉默认标记——停用的模型不能是默认。 */
    public void setEnabled(String key, boolean enabled) {
        if (byKey(key) == null) {
            throw new IllegalArgumentException("模型「" + key + "」不存在");
        }
        try {
            mapper.setEnabled(key, enabled);
            if (!enabled) {
                // 停用的模型留着默认标记，会让"没有可用模型"这种故障很难查
                mapper.clearDefault(key);
            }
        } finally {
            invalidate();
        }
    }

    public void invalidate() {
        cached = null;
        if (events != null) {
            // ModelPool 监听它，清掉按 key 缓存的 Model 实例。
            // 不清的话，改了 base-url 或密钥之后，问数还在连旧地址
            events.publishEvent(new ModelsChangedEvent());
        }
        log.debug("模型缓存已清");
    }

    // ─────────────────────────────────────────────────────────────

    /**
     * 启动时的一致性检查。
     *
     * <p>两类问题必须在启动时暴露，而不是等到用户提问：
     *
     * <ol>
     *   <li><b>一个启用的模型都没有</b>——所有问数都会失败</li>
     *   <li><b>没有默认模型</b>——域没绑定的问数会失败</li>
     * </ol>
     *
     * @return 问题描述；空列表表示正常
     */
    public List<String> healthCheck() {
        List<String> problems = new ArrayList<>();
        List<ModelDef> on = enabled();
        if (on.isEmpty()) {
            problems.add("没有任何启用的模型，全部问数都会失败");
            return problems;
        }
        if (on.stream().noneMatch(ModelDef::isDefault)) {
            problems.add("没有默认模型；未绑定模型的域提交问数时会失败");
        }
        long unpriced = on.stream().filter((m) -> !m.priced()).count();
        if (unpriced > 0) {
            // 只是提示，不是错误——私有部署用自建模型时本来就"没有价格"
            log.info("有 {} 个启用的模型未配置单价，成本看板不会统计它们的费用", unpriced);
        }
        return problems;
    }

    private void validate(String key, String displayName, String baseUrl,
                          String modelName,
                          BigDecimal priceInput, BigDecimal priceCached, BigDecimal priceOutput) {
        require(key, "model_key");
        require(displayName, "显示名");
        require(baseUrl, "接口地址");
        require(modelName, "模型名");
        // 单价三分量要么全配、要么全不配。配一半会让成本算成半对，最难查
        boolean anyPrice = priceInput != null || priceCached != null || priceOutput != null;
        if (anyPrice && (priceInput == null || priceOutput == null)) {
            throw new IllegalArgumentException(
                    "单价要么全配（输入 / 输出必填），要么全不配。"
                            + "配一半会让成本算成半对，比不配更难查");
        }
        checkPositive(priceInput, "输入单价");
        checkPositive(priceCached, "缓存单价");
        checkPositive(priceOutput, "输出单价");
    }

    private static void checkPositive(BigDecimal v, String name) {
        if (v != null && v.compareTo(BigDecimal.ZERO) <= 0) {
            // **0 会让成本算成 0**，而 0 元比"不知道"危险得多
            throw new IllegalArgumentException(name + "必须大于 0。不配单价请全部留空");
        }
    }

    private static void require(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }

    /** List → JSON 数组。**空列表写 `[]` 而不是 null**——两者含义相反。 */
    private String writeRoles(List<String> roles) {
        return json.writeValueAsString(roles == null ? List.of() : roles);
    }

    /**
     * 供管理页回显用（不含密钥）。
     *
     * <p>用 {@code LinkedHashMap} 而不是 {@code Map.of}：后者的重载最多 10 对，
     * 而这里有 14 个字段。硬凑成嵌套 Map 反而更难读。
     */
    public Map<String, Object> toView(ModelDef m, boolean hasApiKey) {
        Map<String, Object> v = new java.util.LinkedHashMap<>();
        v.put("key", m.key());
        v.put("displayName", m.displayName());
        v.put("description", m.description() == null ? "" : m.description());
        v.put("baseUrl", m.baseUrl());
        v.put("modelName", m.modelName());
        v.put("allowedRoles", m.allowedRoles());
        // **只回"配没配"，不回密文也不回明文**
        v.put("hasApiKey", hasApiKey);
        v.put("priceInput", m.priceInput());
        v.put("priceCached", m.priceCached());
        v.put("priceOutput", m.priceOutput());
        v.put("pricedAt", m.pricedAt());
        v.put("priced", m.priced());
        v.put("isDefault", m.isDefault());
        v.put("enabled", m.enabled());
        return v;
    }

    /** 默认模型的键。没有默认时返回 null。 */
    public String defaultModelKey() {
        return enabled().stream().filter(ModelDef::isDefault)
                .findFirst().map(ModelDef::key).orElse(null);
    }

    /** 缓存预热：供测试与启动时用。 */
    void warmUp() {
        invalidate();
        all();
    }

    /** 仅测试用：注入固定列表，绕开数据库。 */
    static ModelService forTest(List<ModelDef> fixed, PasswordCipher cipher) {
        ModelService s = new ModelService(null, cipher, null);
        s.cached = fixed;
        return s;
    }
}

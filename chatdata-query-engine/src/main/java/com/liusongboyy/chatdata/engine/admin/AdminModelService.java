package com.liusongboyy.chatdata.engine.admin;

import com.liusongboyy.chatdata.engine.agent.AskEngineModelProperties;
import com.liusongboyy.chatdata.engine.model.ModelDef;
import com.liusongboyy.chatdata.engine.model.ModelService;
import com.liusongboyy.chatdata.engine.model.mapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型的管理面：增删改、启停、测试。
 *
 * <h2>为什么写操作在 {@link ModelService} 之外</h2>
 *
 * <p>{@code ModelService} 是**运行期**的读侧——每次问数都要读，故带缓存。
 * 管理面是**低频写侧**，它需要的额外能力（读回密钥密文、探测连通性、
 * 组装带用处统计的列表）全都不该出现在热路径上。
 *
 * <p>但**写完之后必须让运行期看见**——这一点通过 {@code ModelService.invalidate()}
 * 完成，它同时发事件清 {@code ModelPool}。漏掉它的后果实测过：
 * 改了 base-url，问数还在连旧地址，因为模型实例被按 key 缓存着。
 *
 * <h2>删除 vs 停用</h2>
 *
 * <p>只提供<b>停用</b>，不提供删除。审计表里存的是 {@code model_key}，
 * 删掉之后历史成本记录会指向一个查不到的键——看板上那一行只剩一个裸串。
 * 停用保留历史可读性，代价只是列表里多一行灰的。
 */
public class AdminModelService {

    private static final Logger log = LoggerFactory.getLogger(AdminModelService.class);

    private final ModelService models;
    private final ModelMapper mapper;
    private final AskEngineModelProperties defaults;

    public AdminModelService(ModelService models, ModelMapper mapper,
                             AskEngineModelProperties defaults) {
        this.models = models;
        this.mapper = mapper;
        this.defaults = defaults;
    }

    /**
     * 全部模型（含停用），按「默认 → 启用 → 键」排序。
     *
     * @return 每一项**不含密钥**，只含 {@code hasApiKey}。
     *         连密文都不该出仓库——它不是明文，但也没有任何展示价值
     */
    public List<Map<String, Object>> list() {
        List<ModelDef> all = new ArrayList<>(models.all());
        // 默认的打头，其次启用的，最后按 key —— 要让"现在在用的是哪个"第一眼可见
        all.sort(java.util.Comparator
                .comparing(ModelDef::isDefault).reversed()
                .thenComparing(ModelDef::enabled, java.util.Comparator.reverseOrder())
                .thenComparing(ModelDef::key));

        List<Map<String, Object>> out = new ArrayList<>();
        for (ModelDef m : all) {
            out.add(models.toView(m, hasApiKey(m.key())));
        }
        return out;
    }

    /**
     * 新建。
     *
     * @throws AdminException 输入有问题，或键已存在
     */
    public void create(ModelInput input, String operator) {
        ModelInput v = input.validated(true);
        if (models.byKey(v.key()) != null) {
            throw new AdminException("模型键「" + v.key() + "」已存在。"
                    + "键是审计里的标识，不能复用——请换一个");
        }
        // ModelService.create 已经做了一轮校验，但那一轮不知道"键不能带中文"
        // 之类的前端可读规则；两轮都留着，各自守自己的边界
        models.create(v.key(), v.displayName(), v.description(), v.baseUrl(),
                v.apiKey(), v.modelName(), v.allowedRoles(),
                v.priceInput(), v.priceCached(), v.priceOutput(),
                v.isDefault(), v.enabled(), operator);
        log.info("新增模型：key={} by={}", v.key(), operator);
    }

    /**
     * 更新。**键不可改**——它从路径来，不在入参里。
     *
     * @param key   要更新的模型键
     * @param input 新值。{@code apiKey} 为 null 表示不改密钥
     */
    public void update(String key, ModelInput input) {
        ModelInput v = input.validated(false);
        models.update(key, v.displayName(), v.description(), v.baseUrl(),
                v.apiKey(), v.modelName(), v.allowedRoles(),
                v.priceInput(), v.priceCached(), v.priceOutput(),
                v.isDefault(), v.enabled());
        log.info("更新模型：key={}", key);
    }

    /**
     * 启用 / 停用。
     *
     * <p>单独一个操作而不是走 {@link #update}：停用是**最常用**的动作
     * （临时把某个模型摘出下拉），而 UPDATE 要求把其余字段原样带上。
     * 让前端为了一次启停去读全量再写回，等于给"并发覆盖别人的修改"开了一道门。
     *
     * <p>停用时会同时摘掉默认标记——{@code ModelService.setEnabled} 里做的。
     * 一个停用的模型还挂着"全局默认"，会让"没有可用模型"这种故障极难定位。
     */
    public void setEnabled(String key, boolean enabled) {
        models.setEnabled(key, enabled);
        log.info("模型 {} 已{}", key, enabled ? "启用" : "停用");
    }

    /**
     * 测试连通性。
     *
     * @param key 已保存的模型：密钥从库里取
     * @return 探测结果
     */
    public ModelProbe.Result testSaved(String key) {
        ModelDef def = models.byKey(key);
        if (def == null) {
            throw new AdminException("模型「" + key + "」不存在");
        }
        String apiKey = models.apiKeyOf(key);
        if (apiKey == null || apiKey.isBlank()) {
            // 本地模型可能真不需要密钥，但这种情况管理员自己也清楚。
            // 直接用占位值去测，让它按供应商的真实反应失败，比在这里猜要好
            apiKey = "not-required";
        }
        return ModelProbe.probe(defaults, apiKey, def.baseUrl(), def.modelName());
    }

    /**
     * 测试**表单里尚未保存**的配置。
     *
     * <p>与数据源的「测试连接」同一取舍：配完当场能验，不用等到某个用户提问失败。
     *
     * @param input 表单内容。{@code apiKey} 为空时**尝试用已存密钥**——
     *              管理页回显不了密钥，所以编辑时留空是常态，
     *              直接拿空密钥去测会得到一次无意义的 401
     */
    public ModelProbe.Result testDraft(String key, ModelInput input) {
        ModelInput v = input.validated(false);
        String apiKey = v.apiKey();
        if (apiKey == null && key != null && !key.isBlank()) {
            apiKey = models.apiKeyOf(key);
        }
        if (apiKey == null || apiKey.isBlank()) {
            return new ModelProbe.Result(false,
                    "还没有填密钥，也没有已保存的密钥可用——请填上再测", 0);
        }
        return ModelProbe.probe(defaults, apiKey, v.baseUrl(), v.modelName());
    }

    /**
     * 绑定了这个模型的域。
     *
     * <p>管理页在停用前要能告诉管理员"有 3 个域绑着它"——停用之后那些域会
     * **立刻问不了**，而管理员的本意通常只是"把它从下拉里摘掉"。
     * 不说的话，他会在几分钟后收到报障。
     *
     * <p>读失败返回空列表而不是抛异常：这只是个提示，
     * 因为它失败而阻止启停是把提示错当成了守卫。
     */
    public List<Map<String, Object>> boundDomains(String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (ModelMapper.DomainRef d : mapper.selectDomainsByModelKey(key)) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("id", d.id());
                v.put("name", d.name());
                out.add(v);
            }
        } catch (RuntimeException e) {
            log.warn("读取模型 {} 的域绑定失败（不影响启停）：{}", key, e.getMessage());
        }
        return out;
    }

    /** 这个模型的密钥配了没配。**不返回密钥本身**。 */
    private boolean hasApiKey(String key) {
        try {
            String enc = mapper.selectApiKeyEnc(key);
            return enc != null && !enc.isBlank();
        } catch (RuntimeException e) {
            // 读不到就报"没配"会让人去重新填一遍密钥；那比重启一下代价大。
            // 但这里也报不了别的，只留日志
            log.warn("读取模型 {} 的密钥状态失败：{}", key, e.getMessage());
            return false;
        }
    }
}

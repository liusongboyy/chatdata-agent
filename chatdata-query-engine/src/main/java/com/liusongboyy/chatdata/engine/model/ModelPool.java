package com.liusongboyy.chatdata.engine.model;

import com.liusongboyy.chatdata.engine.agent.AskEngineModelFactory;
import com.liusongboyy.chatdata.engine.agent.AskEngineModelProperties;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按模型键缓存 `Model` 实例。
 *
 * <h2>为什么必须缓存</h2>
 *
 * <p>`OpenAIChatModel` 内部持有 HTTP 客户端与连接池。每次问数新建一个，
 * 等于每次都重建连接池——高并发下会迅速耗尽文件描述符，而症状是
 * 随机出现的连接失败，与"模型调用"看起来毫无关系。
 *
 * <h2>为什么用 {@code computeIfAbsent} 而不是先查再放</h2>
 *
 * <p>并发下"先查再放"会建两个实例，其中一个被丢弃但**它的连接池没关**——
 * 泄漏。`computeIfAbsent` 保证同一个 key 只建一次。
 *
 * <h2>配置变更要清</h2>
 *
 * <p>模型改了 base-url 或密钥，旧实例还连着旧地址。`ModelService.invalidate()`
 * 会调 {@link #clear()}。这与数据源连接池是同一个教训
 * （`evict` 写了却从没被调用，改完配置不生效）。
 */
@Component
public class ModelPool {

    private static final Logger log = LoggerFactory.getLogger(ModelPool.class);

    private final AskEngineModelProperties defaults;
    private final ModelService models;
    private final Map<String, Model> byKey = new ConcurrentHashMap<>();

    public ModelPool(AskEngineModelProperties defaults, ModelService models) {
        this.defaults = defaults;
        this.models = models;
    }

    /**
     * 全部模型（含停用）。
     *
     * <p>访问判定需要看到停用的那些——{@code ModelAccess.resolve} 要能区分
     * "不存在"与"已停用"，两者给用户的提示不同。
     */
    public java.util.List<ModelDef> all() {
        return models.all();
    }

    /**
     * 全局默认模型。没有默认时返回 {@code null}——调用方据此回落到 yml 配置。
     */
    public ModelDef defaultModel() {
        return models.enabled().stream()
                .filter(ModelDef::isDefault)
                .findFirst().orElse(null);
    }

    /** 按 key 取配置（不建实例）。**不校验启用状态**——调用方要区分"不存在"与"停用"。 */
    public ModelDef byKey(String key) {
        return models.byKey(key);
    }

    /**
     * 取某个模型的实例。
     *
     * @throws IllegalStateException 模型不存在、已停用、或密钥缺失
     */
    public Model get(String key) {
        return byKey.computeIfAbsent(key, (k) -> {
            ModelDef def = models.byKey(k);
            if (def == null) {
                throw new IllegalStateException("模型「" + k + "」不存在");
            }
            if (!def.enabled()) {
                throw new IllegalStateException("模型「" + k + "」已停用");
            }
            String apiKey = models.apiKeyOf(k);
            if (apiKey == null || apiKey.isBlank()) {
                // 本地自建模型（vLLM / Ollama）可能真不需要密钥，
                // 但 OpenAI 兼容服务通常要。给一个占位值而不是 null——
                // 传 null 会在 builder 里 NPE，报错信息毫无指向
                apiKey = "not-required";
                log.debug("模型 {} 未配置密钥，使用占位值", k);
            }
            log.info("构建模型实例：key={} name={} baseUrl={}", k, def.modelName(), def.baseUrl());
            return AskEngineModelFactory.build(defaults, apiKey, def.baseUrl(), def.modelName());
        });
    }

    /**
     * 配置变更后清空。
     *
     * <p>用事件监听而不是让 {@code ModelService} 直接调本方法：
     * 两者互相依赖会形成循环，而 Spring 的循环依赖报错完全指不出真实原因。
     */
    @org.springframework.context.event.EventListener
    public void onModelsChanged(ModelsChangedEvent ignored) {
        clear();
    }

    /** 清空。**不关旧实例的连接**——见类注释的取舍。 */
    public void clear() {
        if (!byKey.isEmpty()) {
            log.info("清空模型实例缓存（{} 个）", byKey.size());
            byKey.clear();
        }
    }

    /** 当前缓存了几个实例。测试与诊断用。 */
    int cachedCount() {
        return byKey.size();
    }
}

package com.liusongboyy.chatdata.engine.model;

import com.liusongboyy.chatdata.engine.agent.AskEngineModelProperties;
import com.liusongboyy.chatdata.engine.datasource.PasswordCipher;
import com.liusongboyy.chatdata.engine.model.mapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 首次启动时把 yml 里的模型配置灌进数据库。
 *
 * <h2>为什么需要这一步</h2>
 *
 * <p>模型配置从 yml 搬到了 {@code ask_model} 表。但老部署的
 * {@code chatdata.engine.model.*} 还在——不迁移的话升级后
 * 「一个模型都没有」，所有问数直接失败。
 *
 * <h2>只在表为空时灌</h2>
 *
 * <p><b>绝不覆盖已有数据。</b>管理员在管理台改过配置之后，重启不该把它改回去。
 * 所以判据是"表里一条都没有"，而不是"yml 和表不一致"。
 *
 * <h2>迁移完以表为准</h2>
 *
 * <p>之后改 yml 不会再同步——这一点必须让人知道，否则会出现
 * "改了 yml 没生效"这种最难查的问题。启动日志里会明说。
 */
@Component
public class ModelSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ModelSeedRunner.class);

    /** 种子模型的键。稳定不变——审计里存的是它。 */
    static final String SEED_KEY = "default";

    private final ModelMapper mapper;
    private final ModelService models;
    private final AskEngineModelProperties yml;
    private final PasswordCipher cipher;

    public ModelSeedRunner(ModelMapper mapper, ModelService models,
                           AskEngineModelProperties yml, PasswordCipher cipher) {
        this.mapper = mapper;
        this.models = models;
        this.yml = yml;
        this.cipher = cipher;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seed();
            reportHealth();
        } catch (RuntimeException e) {
            // 迁移失败不阻断启动——管理面还能进，管理员可以手工配
            log.error("模型配置迁移失败（应用照常启动，但问数可能不可用）：{}", e.getMessage(), e);
        }
    }

    private void seed() {
        if (models.all().isEmpty()) {
            if (yml.getApiKey() == null || yml.getApiKey().isBlank()) {
                log.warn("ask_model 表为空，且 yml 未配置 chatdata.engine.model.api-key——"
                        + "问数不可用。请在管理台「模型」里添加一个");
                return;
            }
            mapper.insert(SEED_KEY,
                    // 显示名取模型名——管理员进管理台能认出它对应 yml 里哪一条
                    yml.getModelName() == null ? "默认模型" : yml.getModelName(),
                    "从 application.yml 迁移而来",
                    yml.getBaseUrl(), cipher.encrypt(yml.getApiKey()), yml.getModelName(),
                    // 空角色列表 = 不限制
                    "[]",
                    null, null, null, null,   // 单价留空：yml 里本来就没有
                    true, true, "migration");
            models.invalidate();
            log.info("已从 application.yml 迁移模型配置：key={} name={}", SEED_KEY, yml.getModelName());
            log.warn("** 迁移完成，此后以数据库为准 **：改 application.yml 里的模型配置不再生效，"
                    + "请在管理台「模型」页调整");
            return;
        }
        log.debug("ask_model 已有 {} 条记录，跳过 yml 迁移", models.all().size());
    }

    /**
     * 启动时体检。
     *
     * <p>两类问题必须在启动时暴露，而不是等用户提问失败：
     * 一个启用的模型都没有、没有默认模型。
     */
    private void reportHealth() {
        List<String> problems = models.healthCheck();
        for (String p : problems) {
            log.error("模型配置问题：{}", p);
        }
        if (problems.isEmpty()) {
            log.info("模型配置正常：{} 个启用（默认：{}）",
                    models.enabled().size(),
                    models.defaultModelKey() == null ? "未设置" : models.defaultModelKey());
        }
    }
}

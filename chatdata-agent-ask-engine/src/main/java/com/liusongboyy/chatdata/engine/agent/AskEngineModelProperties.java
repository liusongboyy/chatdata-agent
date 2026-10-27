package com.liusongboyy.chatdata.engine.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 问数引擎的模型配置。
 *
 * <p>与现有 {@code vendor.jmreport.ai.*} 并存——那是 第三方 BI 产品 的配置，
 * 本引擎不共用（见 ADR-0006：两者并行运行，切换方式另行决策）。
 */
@ConfigurationProperties(prefix = "chatdata.engine.model")
public class AskEngineModelProperties {

    /**
     * OpenAI 兼容接口的 base URL。
     *
     * <p>火山方舟走 OpenAI 兼容栈——agentscope 2.0.4 **没有** ark 专用 provider，
     * 只能靠 baseUrl 指过去。不配则回退到 api.openai.com，那是错的，故无默认值。
     */
    private String baseUrl;

    /** 密钥。从环境变量注入，**绝不写进配置文件**。 */
    private String apiKey;

    /** 模型名，如 deepseek-v4-1-flash-260910。 */
    private String modelName;

    /**
     * 生成温度。**默认 0**——问数链路要求可复现，
     * 同一问题在不同温度下会得出不同 DSL，无法做回归测试。
     */
    private Double temperature = 0.0;

    /**
     * 单次生成上限。**必须显式设置**：不设时输出可能被截断，
     * 表现为「结构化 JSON 只出了一半、末尾字段凭空消失」。
     * 见 .scratch/self-built-chatbi/spike-p0-results.md。
     */
    private Integer maxTokens = 16384;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }
}

package com.liusongboyy.chatdata.engine.agent;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

/**
 * 模型装配。**这里的每一行参数都是 P0/P0.5 spike 实测换来的，改动前请先读原因。**
 *
 * <p>见 {@code .scratch/self-built-chatbi/spike-p0-results.md}。
 */
public final class AskEngineModelFactory {

    private AskEngineModelFactory() {
    }

    /**
     * 构建 OpenAI 兼容模型。
     *
     * <p><strong>两个参数不可随意改动：</strong>
     *
     * <ul>
     *   <li>{@code nativeStructuredOutput(true)} —— 让模型走 native {@code response_format}。</li>
     *   <li>{@code nativeStructuredOutputWithTools(false)} —— <strong>必须是 false。</strong>
     *       spike E3 实测：置 true 时结构化输出**必然失败**，报
     *       {@code No structured output in message metadata. Key '_structured_output' not found.}
     *       且伴随 {@code Failed to parse native structured output as JSON}。
     *       网关**接受**请求（不是 400）但返回内容解析不了，报错信息与真实原因相距甚远——
     *       若有人为"更好的结构化输出"把它打开，会很难查。</li>
     * </ul>
     */
    public static Model build(AskEngineModelProperties props) {
        requireText(props.getApiKey(), "chatdata.engine.model.api-key");
        requireText(props.getBaseUrl(), "chatdata.engine.model.base-url");
        requireText(props.getModelName(), "chatdata.engine.model.model-name");

        return doBuild(props.getApiKey(), props.getBaseUrl(), props.getModelName(),
                props.getTemperature(), props.getMaxTokens());
    }

    /**
     * 按一条**数据库里的模型配置**构建。
     *
     * <p>与上面的区别只在于参数来源。温度与 maxTokens 仍取全局配置——
     * 它们不是"哪个模型"的属性，而是"问数链路要什么行为"的属性
     * （温度必须是 0 才能复现，maxTokens 必须够大否则结构化输出被截断）。
     * <b>让每个模型可配这两个值，等于给"输出被截断"开了一个后门。</b>
     */
    public static Model build(AskEngineModelProperties defaults,
                              String apiKey, String baseUrl, String modelName) {
        requireText(apiKey, "模型的 api-key");
        requireText(baseUrl, "模型的 base-url");
        requireText(modelName, "模型的 model-name");

        return doBuild(apiKey, baseUrl, modelName,
                defaults.getTemperature(), defaults.getMaxTokens());
    }

    /**
     * 唯一实际构建点。
     *
     * <p>两个 {@code build} 都汇到这里，避免"参数校验在 A 有、在 B 漏"——
     * 漏掉的表现是某个模型能建起来但行为与预期不符。
     */
    private static Model doBuild(String apiKey, String baseUrl, String modelName,
                                 Double temperature, Integer maxTokens) {
        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                .nativeStructuredOutput(true)
                .nativeStructuredOutputWithTools(false)
                .generateOptions(GenerateOptions.builder()
                        .temperature(temperature)
                        .maxTokens(maxTokens)
                        .build())
                .build();
    }

    private static void requireText(String v, String key) {
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("缺少配置 " + key + "。密钥请通过环境变量注入，不要写进配置文件。");
        }
    }
}

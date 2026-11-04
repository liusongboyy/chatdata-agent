package com.liusongboyy.chatdata.engine.admin;

import com.liusongboyy.chatdata.engine.agent.AskEngineModelFactory;
import com.liusongboyy.chatdata.engine.agent.AskEngineModelProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型连通性测试：真发一次极小的请求。
 *
 * <h2>为什么不"只校验配置格式"</h2>
 *
 * <p>格式对、地址错、密钥过期、模型名下架——这四种情况的表现完全一样：
 * 管理员存完配置，一切看起来正常，直到某个用户提问时才失败。
 * 而那时报的是"处理失败，请稍后重试"，与"密钥过期"毫无关系。
 *
 * <p>所以这里必须真发一次。与数据源的「测试连接」同一个理由。
 *
 * <h2>为什么用真实调用路径的模型工厂</h2>
 *
 * <p>自己 new 一个 {@code OpenAIChatModel} 也能测，但那样测的是"另一种配置"
 * ——而 {@link AskEngineModelFactory} 里有几个<b>不可改</b>的参数
 * （见它的类注释：{@code nativeStructuredOutputWithTools(false)} 是 spike 换来的）。
 * 测试的目的就是验证"这条链路能不能用"，绕开它就没有意义了。
 */
public final class ModelProbe {

    /**
     * 探测用的提示。**要极短。**
     *
     * <p>每次点"测试"都是一次真实计费。一个 5 个字的提示花费约等于 0，
     * 而"请介绍一下你自己"会让管理员犹豫要不要点第二次。
     */
    private static final String PING = "hi";

    /** 探测超时（秒）。超时要能给出明确结论，而不是挂住页面。 */
    private static final long TIMEOUT_SECONDS = 30;

    private ModelProbe() {
    }

    /**
     * 测试结果。
     *
     * @param ok      是否连通
     * @param message 给人看的结果。**失败时要能指导怎么改**——
     *                原文透传供应商的报错，同时给出可能的处置方向
     * @param elapsedMs 往返耗时。管理员据此判断"这个模型快不快"，
     *                而这是选模型时仅有的几个客观指标之一
     */
    public record Result(boolean ok, String message, long elapsedMs) {

        static Result success(long ms) {
            return new Result(true, "连通（耗时 " + ms + " ms）", ms);
        }

        static Result failure(String message, long ms) {
            return new Result(false, message, ms);
        }
    }

    /**
     * 真发一次请求。
     *
     * @param defaults 全局默认参数（温度、maxTokens 取自它——它们不是"哪个模型"的属性）
     * @param apiKey   明文密钥
     * @param baseUrl  接口地址
     * @param modelName 模型名
     * @return 结果。**不抛异常**——连不上是这类接口的正常结果，不是程序错误
     */
    public static Result probe(AskEngineModelProperties defaults,
                               String apiKey, String baseUrl, String modelName) {
        long t0 = System.currentTimeMillis();

        Model model;
        try {
            model = AskEngineModelFactory.build(defaults, apiKey, baseUrl, modelName);
        } catch (RuntimeException e) {
            // 配置本身就不成立（缺字段）。这里给的是**配置问题**，不是网络问题
            return Result.failure("配置不完整：" + e.getMessage(), 0);
        }

        try {
            ReActAgent agent = ReActAgent.builder()
                    .name("model-probe")
                    .sysPrompt("你是一个连通性测试助手，只需回复 ok。")
                    .model(model)
                    .build();

            Msg reply = agent.call(List.of(new UserMessage(PING)),
                            RuntimeContext.builder().userId("probe").sessionId("probe").build())
                    .block(java.time.Duration.ofSeconds(TIMEOUT_SECONDS));

            long ms = System.currentTimeMillis() - t0;
            if (reply == null) {
                return Result.failure("模型没有返回内容（耗时 " + ms + " ms）。"
                        + "地址与模型名可能不匹配——有些供应商对未知模型返回空响应而不是报错", ms);
            }
            return Result.success(ms);

        } catch (RuntimeException e) {
            long ms = System.currentTimeMillis() - t0;
            return Result.failure(explain(e), ms);
        }
    }

    /**
     * 把异常翻译成能指导行动的话。
     *
     * <h2>为什么不能只透传原始报错</h2>
     *
     * <p>供应商的报错常常是 {@code 401 Unauthorized} 或
     * {@code Connection refused}——它说清了自己是什么，但没说该改哪一格。
     * 管理页上有五个格子，管理员需要一个方向。
     *
     * <p><b>但原文必须保留</b>：翻译是我猜的，原文是事实。
     * 只留翻译会让人在罕见情况下一筹莫展。
     */
    private static String explain(RuntimeException e) {
        String raw = deepestMessage(e);
        String lower = raw == null ? "" : raw.toLowerCase(java.util.Locale.ROOT);

        String hint = null;
        if (lower.contains("401") || lower.contains("unauthorized") || lower.contains("invalid api")
                || lower.contains("authentication")) {
            hint = "密钥无效或已过期——请检查「密钥」这一格";
        } else if (lower.contains("404") || lower.contains("not found") || lower.contains("model_not")) {
            hint = "地址或模型名不对——请检查「接口地址」是否带对了路径（多数供应商要 /v1），"
                    + "以及「模型名」是否是供应商侧的准确名称";
        } else if (lower.contains("403") || lower.contains("forbidden")) {
            hint = "密钥没有访问该模型的权限——请在供应商控制台开通这个模型";
        } else if (lower.contains("timeout") || lower.contains("timed out")) {
            hint = "请求超时——地址可能不可达，或网络需要代理";
        } else if (lower.contains("connect") || lower.contains("unknownhost")
                || lower.contains("refused")) {
            hint = "连不上地址——请检查地址拼写与网络连通性";
        } else if (lower.contains("429") || lower.contains("rate limit")) {
            hint = "被限流——配置本身没问题，稍后再试";
        }

        return hint == null
                ? "测试失败：" + raw
                : "测试失败：" + hint + "\n供应商原文：" + raw;
    }

    /** 最深一层的消息——外层通常只有"Error creating bean…"之类的包装。 */
    private static String deepestMessage(Throwable e) {
        Throwable t = e;
        String best = e.getMessage();
        AtomicReference<String> ref = new AtomicReference<>(best);
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
            if (t.getMessage() != null && !t.getMessage().isBlank()) {
                ref.set(t.getMessage());
            }
        }
        return ref.get() == null ? e.getClass().getSimpleName() : ref.get();
    }
}

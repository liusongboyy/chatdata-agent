package com.liusongboyy.chatdata.engine.chat;

/**
 * 问数过程中的一个事件。
 *
 * <p>形态见 {@code docs/adr/0003-streaming-event-level-sse.md}：**事件级**而非逐字级。
 * 问数是多步流程（权限 → 生成 DSL → 渲染 SQL → 执行），步骤级进度比逐字更有意义。
 *
 * <p>三种状态对应 PRD FR-5.3 的要求：
 * <table>
 *   <tr><th>PRD 要求</th><th>本模型里的表现</th></tr>
 *   <tr><td>思考中</td><td>{@link Progress} 且 {@code stage = GENERATING_DSL}</td></tr>
 *   <tr><td>正在查数</td><td>{@link Progress} 且 {@code stage = EXECUTING}</td></tr>
 *   <tr><td>最终答案</td><td>{@link Completed}</td></tr>
 * </table>
 *
 * <p><strong>事件里不带 SQL。</strong>SQL 在 {@link Completed} 的 {@link AskResult} 里，
 * 且是否透出给用户由上层决定（助手的 {@code show_sql} 配置）——
 * 中间过程推送 SQL 会让这个开关失去意义。
 */
public sealed interface AskEvent {

    /** SSE 事件名，与厂商集成那条链路的 {@code AskStreamController} 保持一致。 */
    String eventName();

    /** 收到问题，开始处理。 */
    record Started(String question) implements AskEvent {
        @Override
        public String eventName() {
            return "started";
        }
    }

    /** 处理阶段推进。 */
    record Progress(Stage stage, String message) implements AskEvent {
        @Override
        public String eventName() {
            return "progress";
        }
    }

    /** 完成，带最终结果。 */
    record Completed(AskResult result) implements AskEvent {
        @Override
        public String eventName() {
            return "result";
        }
    }

    /**
     * 失败。
     *
     * @param code    机器可读的失败码，供前端分支与审计分类。**不要靠 message 判断**
     * @param message 给用户看的说明，不含内部细节
     */
    record Failed(String code, String message) implements AskEvent {
        @Override
        public String eventName() {
            return "error";
        }
    }

    /** 处理阶段。 */
    enum Stage {

        /** 正在判定权限。**排在最前**——无权限的请求不该消耗 LLM 调用。 */
        CHECKING_PERMISSION("正在校验权限"),

        /** 正在把问题翻译成 DSL。这一步会调模型，是耗时的主要来源。 */
        GENERATING_DSL("正在理解问题"),

        /** 正在渲染 SQL 并注入权限谓词。纯计算，通常很快。 */
        RENDERING_SQL("正在生成查询"),

        /** 正在执行查询。 */
        EXECUTING("正在查询数据");

        private final String label;

        Stage(String label) {
            this.label = label;
        }

        /** 给用户看的中文说明。 */
        public String label() {
            return label;
        }
    }

    /** 失败码。前端据此分支，审计据此分类。 */
    final class FailureCode {

        private FailureCode() {
        }

        /** 权限拒绝。与执行失败分开——前者是权限问题，后者是数据或口径问题。 */
        public static final String DENIED = "DENIED";

        /** 没能理解问题（DSL 为空）。**不是"查询无数据"**，两者必须分开。 */
        public static final String NOT_UNDERSTOOD = "NOT_UNDERSTOOD";

        /** DSL 生成失败（模型没返回、返回的不是 JSON 等）。**重试有可能成功**。 */
        public static final String DSL_FAILED = "DSL_FAILED";

        /**
         * 引擎没配置好（模型密钥缺失等）。**重试永远不会成功**。
         *
         * <p>与 {@link #DSL_FAILED} 分开是必须的：两者若共用一个码，
         * 用户看到的都是"请稍后重试"，而配置缺失时那句话是主动误导——
         * 用户会反复重试，运维永远收不到"去配密钥"这个真实信号。
         */
        public static final String ENGINE_NOT_READY = "ENGINE_NOT_READY";

        /** 语义配置有问题（字段找不到、多表连不通等）。 */
        public static final String SEMANTIC_ERROR = "SEMANTIC_ERROR";

        /** 执行失败（连接不可用、SQL 报错、超时）。 */
        public static final String EXECUTION_FAILED = "EXECUTION_FAILED";
    }
}

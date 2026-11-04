package com.liusongboyy.chatdata.engine.analysis;

/**
 * 产出分析计划。与 {@code DslSource} 分开，是因为两者的产物结构不同，
 * 但**入参语义相同**（问题 + 域描述 + 会话标识）。
 *
 * <p>实现走同一个模型与同一套"手写 schema"路径，理由见 {@code AskDslSchema}。
 *
 * <p>返回值带 token 消耗，理由与 {@code DslSource} 相同：模型调用的用量只在
 * 回复消息上存在一次，不随返回值带出来就永远采不到了。
 */
public interface AnalysisPlanSource {

    /**
     * @param question  用户问题
     * @param sysPrompt 域描述 + 编排规则
     * @param userId    会话隔离
     * @param sessionId 会话隔离
     */
    com.liusongboyy.chatdata.engine.agent.Generated<AnalysisPlan> generatePlan(
            String question, String sysPrompt, String userId, String sessionId);
}

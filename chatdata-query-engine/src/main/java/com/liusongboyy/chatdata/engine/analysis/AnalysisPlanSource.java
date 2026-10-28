package com.liusongboyy.chatdata.engine.analysis;

/**
 * 产出分析计划。与 {@code DslSource} 分开，是因为两者的产物结构不同，
 * 但**入参语义相同**（问题 + 域描述 + 会话标识）。
 *
 * <p>实现走同一个模型与同一套"手写 schema"路径，理由见 {@code AskDslSchema}。
 */
public interface AnalysisPlanSource {

    /**
     * @param question  用户问题
     * @param sysPrompt 域描述 + 编排规则
     * @param userId    会话隔离
     * @param sessionId 会话隔离
     */
    AnalysisPlan generatePlan(String question, String sysPrompt, String userId, String sessionId);
}

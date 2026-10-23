package com.liusongboyy.chatdata.engine.agent;

import com.liusongboyy.chatdata.engine.nlsql.AskDsl;

/**
 * DSL 的来源。
 *
 * <p>抽成接口是为了让编排逻辑可测：{@link AskService} 需要验证"权限被拒时不该调用模型"、
 * "没听懂时不该执行查询"这类**顺序**约束，而那些测试不该依赖一个真的 LLM。
 *
 * @see DslGenerator 真实实现
 */
@FunctionalInterface
public interface DslSource {

    /**
     * 生成 DSL。
     *
     * @param question  用户问题
     * @param sysPrompt 由 {@code PromptAssembler} 按语义域装配
     * @param userId    用户标识
     * @param sessionId 会话标识
     */
    AskDsl generate(String question, String sysPrompt, String userId, String sessionId);
}

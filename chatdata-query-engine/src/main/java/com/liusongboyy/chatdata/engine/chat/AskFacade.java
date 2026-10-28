package com.liusongboyy.chatdata.engine.chat;

import java.util.List;
import java.util.function.Consumer;

/**
 * 一个语义域对外的问数入口。**两种模式实现它**，控制器只依赖这个接口。
 *
 * <p>建模模式（{@link AskService}）：DSL → 我们渲染 SQL → 注入行/列权限。
 * 直连模式（{@link RawAskService}）：模型自己写 SQL，**只有表级授权**。
 *
 * <p>抽成接口是为了让"按模式分流"发生在装配层（{@code AskServiceFactory}），
 * 而不是在控制器里写一串 {@code if (mode == RAW)}——那种分支迟早会漏一处，
 * 而漏掉的那一处一定在权限或审计上。
 */
public interface AskFacade {

    /** 流式问数：把阶段事件推给 {@code sink}，返回最终结果。 */
    AskResult askStreaming(AskRequest request, Consumer<AskEvent> sink);

    /**
     * 这个域能不能做分析编排。
     *
     * <p>直连模式返回 false：它没有 DSL，也就没有"每步都是一个受约束查询"的前提。
     * 前端据此决定是否显示「深入分析」开关。
     */
    default boolean supportsAnalysis() {
        return false;
    }

    /**
     * 分析编排。默认退回单查询——**不支持编排的模式不应报错**，
     * 用户点了一下开关就该拿到和平时一样的答案，而不是一个"功能不可用"。
     */
    default AskResult analyzeStreaming(AskRequest request, Consumer<AskEvent> sink) {
        return askStreaming(request, sink);
    }

    /** 域显示名。界面用它说明"当前在问哪个域"。 */
    String domainName();

    /**
     * 域 id。
     *
     * <p>结果回传与反馈都要它：反馈必须落在**产生这条答案的那个域**上，
     * 否则审核通过后写入的范例会被别的域检索到（见 {@code ask_feedback}）。
     */
    String domainId();

    /**
     * 域内可问的字段名。
     *
     * <p>直连模式返回空列表：那一模式没有"登记过的字段"这个概念，
     * 模型看的是库里的真实表结构。界面据此不显示字段引导。
     */
    List<String> availableFields();
}

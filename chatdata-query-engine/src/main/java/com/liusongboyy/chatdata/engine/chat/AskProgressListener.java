package com.liusongboyy.chatdata.engine.chat;

/**
 * 问数阶段推进的回调。
 *
 * <p>存在的理由是**进度可见**：生成 DSL 要调模型，是耗时的主要来源，
 * 用户需要知道"它还在动"。
 *
 * <p>刻意做成回调而不是让 {@code AskEngine} 返回事件流：引擎的核心是同步的
 * （每一步都依赖上一步的结果），硬做成响应式只会让代码变复杂而没有任何并发收益。
 * 需要流式时由上层用回调转成事件流。
 */
@FunctionalInterface
public interface AskProgressListener {

    void onStage(AskEvent.Stage stage);

    /** 什么都不做。用于不关心进度的调用方（如批处理、测试）。 */
    AskProgressListener NOOP = stage -> {
        // 故意留空
    };
}

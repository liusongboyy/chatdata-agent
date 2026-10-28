package com.liusongboyy.chatdata.engine.analysis;

/** 一个分析步骤的结局。**没有"跳过"**：每一步要么有数据，要么失败并写清原因。 */
public enum StepStatus {
    OK,
    FAILED
}

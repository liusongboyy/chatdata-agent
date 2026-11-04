package com.liusongboyy.chatdata.engine.model;

/**
 * 模型配置变了。
 *
 * <h2>为什么用事件而不是直接调 {@code ModelPool.clear()}</h2>
 *
 * <p>{@code ModelPool} 依赖 {@code ModelService}（要读配置），
 * 若 {@code ModelService} 再反过来依赖 {@code ModelPool}，就是循环依赖——
 * Spring 会报 "requested bean is currently in creation"，而那句话
 * 完全指不出真实原因。
 *
 * <p>事件把方向理顺：Service 发信号，Pool 监听。
 */
public record ModelsChangedEvent() {
}

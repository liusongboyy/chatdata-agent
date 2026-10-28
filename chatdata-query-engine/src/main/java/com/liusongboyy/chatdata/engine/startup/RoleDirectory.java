package com.liusongboyy.chatdata.engine.startup;

import java.util.Map;
import java.util.Optional;

/**
 * 角色清单——用来判断权限配置里写的角色名是否真的存在。
 *
 * <h2>为什么做成接口而不是直接查库</h2>
 *
 * <p>角色存在哪里是**宿主应用的事**（本项目在 IAM），引擎只认调用方
 * 传进来的角色码，从不自己去找角色。把 {@code sys_role} 的查询塞进引擎，
 * 等于让一个库去猜宿主的认证模型——那正是本项目一直在避免的耦合。
 *
 * <h2>为什么返回 {@link Optional}</h2>
 *
 * <p>{@code empty} 表示**拿不到**（查询失败、或宿主没有提供这一路），
 * 而不是"一个角色都没有"。这两者必须分开：
 *
 * <ul>
 *   <li>"拿不到" → 跳过这项校验，否则会把所有配置都误报成"角色不存在"</li>
 *   <li>"确实一个角色都没有" → 那所有引用角色的配置**真的**都失效了，应当报</li>
 * </ul>
 *
 * <p>把前者当成后者，会让这道校验在数据库抖动时刷出一屏假告警——
 * 而一屏假告警等于没有告警。
 */
@FunctionalInterface
public interface RoleDirectory {

    /**
     * @return 角色码 → 是否启用；{@code empty} 表示无法获取，此时跳过该项校验
     */
    Optional<Map<String, Boolean>> roles();
}

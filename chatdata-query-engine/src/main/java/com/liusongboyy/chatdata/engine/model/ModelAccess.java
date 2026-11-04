package com.liusongboyy.chatdata.engine.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 谁能用哪个模型。
 *
 * <h2>三道校验，缺一不可</h2>
 *
 * <ol>
 *   <li><b>角色白名单</b>：模型的 {@code allowedRoles} 含用户的任一角色</li>
 *   <li><b>域绑定</b>：域若绑了模型键，只能用那一个</li>
 *   <li><b>模型存在且启用</b></li>
 * </ol>
 *
 * <h2>不通过时拒绝，不静默降级</h2>
 *
 * <p>用户以为自己在用强模型、实际用了弱模型，会让他对结果产生错误判断——
 * <b>那比报错糟得多</b>。所以校验不过一律拒绝。
 *
 * <h2>纯函数</h2>
 *
 * <p>输入（模型列表、用户角色、域绑定）→ 输出（可选模型集合 / 拒绝原因）。
 * 不查库、不读上下文，可单测。这条判据也用在
 * {@code GET /api/ask/models} 与真正发起问数两处，**必须同源**——
 * 两处各写一遍会漂移，而漂移的表现是「下拉里看得见、选了报 403」。
 */
public final class ModelAccess {

    private ModelAccess() {
    }

    /**
     * 拒绝原因。
     *
     * @param code    稳定的错误码
     * @param message 给人看的说明。**要说清怎么办**，不能只说"无权限"
     */
    public record Denial(String code, String message) {
    }

    /**
     * 用户可选的模型列表。
     *
     * @param all   全部模型（含停用的——由本方法过滤）
     * @param roles 用户的角色集合
     * @param bound 域绑定的模型键；null 表示不限制
     * @return 可选的模型。**可能为空**——那意味着这个用户一个都不能用，
     *         调用方要据此禁用输入框而不是让他打了字才报错
     */
    public static List<ModelDef> selectable(List<ModelDef> all, Set<String> roles,
                                            String bound) {
        List<ModelDef> out = new ArrayList<>();
        for (ModelDef m : all) {
            if (!m.enabled()) {
                continue;
            }
            // 域绑定优先：绑了就只能用它，别的连列都不列
            if (bound != null && !bound.isBlank() && !bound.equals(m.key())) {
                continue;
            }
            if (!rolesAllow(m, roles)) {
                continue;
            }
            out.add(m);
        }
        return out;
    }

    /**
     * 解析出**实际要用**的那个模型。
     *
     * <h2>解析顺序</h2>
     *
     * <pre>
     * 用户显式选了   → 用它（但三道校验都要过）
     * 否则域绑了模型 → 用域绑的
     * 否则           → 用全局默认
     * </pre>
     *
     * @param all         全部模型
     * @param roles       用户角色
     * @param bound       域绑定的模型键；null 表示不限制
     * @param requested   用户显式请求的模型键；空表示没选
     * @return 选中的模型，或拒绝原因。两者必有其一
     */
    public static Result resolve(List<ModelDef> all, Set<String> roles,
                                 String bound, String requested) {
        // ① 用户显式选了
        if (requested != null && !requested.isBlank()) {
            Optional<ModelDef> hit = all.stream()
                    .filter((m) -> m.key().equals(requested))
                    .findFirst();
            if (hit.isEmpty()) {
                return new Result(null, new Denial("MODEL_NOT_FOUND",
                        "模型「" + requested + "」不存在或已被删除"));
            }
            ModelDef m = hit.get();
            if (!m.enabled()) {
                return new Result(null, new Denial("MODEL_DISABLED",
                        "模型「" + m.displayName() + "」已停用，请换一个"));
            }
            // 域绑定优先于用户选择：域锁死了就不能绕过
            if (bound != null && !bound.isBlank() && !bound.equals(m.key())) {
                return new Result(null, new Denial("MODEL_LOCKED_BY_DOMAIN",
                        "这个数据域限定使用别的模型，不能自选"));
            }
            if (!rolesAllow(m, roles)) {
                return new Result(null, new Denial("MODEL_NOT_ALLOWED",
                        "你的账号无权使用「" + m.displayName() + "」"));
            }
            return new Result(m, null);
        }

        // ② 域绑了模型
        if (bound != null && !bound.isBlank()) {
            Optional<ModelDef> hit = all.stream()
                    .filter((m) -> m.key().equals(bound))
                    .findFirst();
            if (hit.isPresent() && hit.get().enabled()) {
                // **域绑定不再校验角色白名单。**
                //
                // 域绑定是"这个数据域必须用这个模型"的技术约束（数据量大、
                // 弱模型跑不动），不是权限授予。而角色白名单是"谁有资格选它"。
                //
                // 如果这里再卡角色，会出现「域锁定的模型这个角色没权限 →
                // 他连这个域都用不了」，而管理员的本意是"这个域大家都得用强模型"。
                return new Result(hit.get(), null);
            }
            // 绑定指向的模型没了/停了——**明确报错，不悄悄回落默认**。
            // 回落会让这个域突然用上弱模型，而没人知道
            return new Result(null, new Denial("BOUND_MODEL_UNAVAILABLE",
                    "这个数据域绑定的模型「" + bound + "」不可用，请联系管理员"));
        }

        // ③ 全局默认
        Optional<ModelDef> def = all.stream()
                .filter((m) -> m.isDefault() && m.enabled())
                .findFirst();
        if (def.isEmpty()) {
            return new Result(null, new Denial("NO_DEFAULT_MODEL",
                    "没有配置默认模型，请联系管理员"));
        }
        // 默认模型也要过角色：某些角色可能连默认的都不该用（如只读审计员）
        if (!rolesAllow(def.get(), roles)) {
            return new Result(null, new Denial("NO_MODEL_FOR_ROLE",
                    "你的账号没有可用的模型，请联系管理员"));
        }
        return new Result(def.get(), null);
    }

    /**
     * 角色白名单判断。
     *
     * <p><b>空白名单 = 不限制。</b>这是刻意的：新加的模型不该默认对所有人隐身，
     * 那会让管理员配完以为没生效。
     */
    static boolean rolesAllow(ModelDef model, Set<String> roles) {
        if (model.allowedRoles().isEmpty()) {
            return true;
        }
        if (roles == null || roles.isEmpty()) {
            return false;
        }
        for (String r : roles) {
            if (model.allowedRoles().contains(r)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析结果。**成功与失败互斥**——用 record 而不是抛异常，
     * 因为"没有可用模型"是正常的业务状态（管理员还没配完），不是程序错误。
     */
    public record Result(ModelDef model, Denial denial) {

        public Result {
            if ((model == null) == (denial == null)) {
                throw new IllegalArgumentException("Result 必须且只能有其一");
            }
        }

        public boolean ok() {
            return model != null;
        }
    }
}

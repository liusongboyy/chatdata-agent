package com.liusongboyy.chatdata.ask.engine;

import com.liusongboyy.chatdata.ask.identity.CurrentUser;
import com.liusongboyy.chatdata.common.core.result.Result;
import com.liusongboyy.chatdata.engine.admin.AdminException;
import com.liusongboyy.chatdata.engine.admin.AdminModelService;
import com.liusongboyy.chatdata.engine.admin.ModelInput;
import com.liusongboyy.chatdata.engine.admin.ModelProbe;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 模型管理。
 *
 * <h2>为什么单独一个控制器</h2>
 *
 * <p>{@link AdminEngineController} 已经有 39 个端点、十几项依赖。模型这部分
 * 有它自己的两个特性——<b>密钥只进不出</b>与<b>会真发请求做连通性测试</b>——
 * 塞进去之后那个类会同时存在"只是读写配置"和"会花管理员钱"的两种操作，
 * 而权限上它们该分开授。
 *
 * <h2>密钥的边界</h2>
 *
 * <p>入参可以带明文密钥（新建 / 更换），<b>出参永远不带</b>——
 * 连密文都不出。列表里只有一个 {@code hasApiKey} 布尔。
 * 这与数据源密码是同一条规则，理由也一样：回显的价值为零，泄露的代价无穷。
 *
 * <h2>删除</h2>
 *
 * <p>没有删除端点，只有停用。审计表里存的是 {@code model_key}，
 * 删掉之后历史成本记录会指向一个查不到的键。停用保留历史可读性。
 */
@RestController
@RequestMapping("/api/engine/admin/models")
@org.springframework.security.access.prepost.PreAuthorize("@perm.allowAdminApi()")
public class AdminModelController {

    private static final Logger log = LoggerFactory.getLogger(AdminModelController.class);

    private final AdminModelService service;
    private final CurrentUser currentUser;

    public AdminModelController(AdminModelService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    /** 全部模型（含停用）。**不含密钥**。 */
    @GetMapping
    public ResponseEntity<Result<List<Map<String, Object>>>> list(HttpServletRequest request) {
        ResponseEntity<Result<List<Map<String, Object>>>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ok(service.list());
    }

    /** 新建。返回体里刻意不含新 id——键本身就是标识，前端拿它复查。 */
    @PostMapping
    public ResponseEntity<Result<Void>> create(@RequestBody ModelInput input,
                                               HttpServletRequest request) {
        ResponseEntity<Result<Void>> denied = requireAdmin(request);
        if (denied != null) return denied;
        service.create(input, operator());
        return ok(null);
    }

    /**
     * 更新。
     *
     * <p>键在路径里，**不在请求体里**——它创建后不可改。放进请求体的话，
     * 前端传一个不同的键会被当成"改键"，而那会让审计里的历史记录断链。
     *
     * @param key 模型键
     */
    @PutMapping("/{key}")
    public ResponseEntity<Result<Void>> update(@PathVariable String key,
                                               @RequestBody ModelInput input,
                                               HttpServletRequest request) {
        ResponseEntity<Result<Void>> denied = requireAdmin(request);
        if (denied != null) return denied;
        service.update(key, input);
        return ok(null);
    }

    /**
     * 启用 / 停用。
     *
     * <p>单独一个端点而不是走 PUT：停用是最常用的动作，而 PUT 要求把其余字段
     * 原样带上。让前端为了一次启停去读全量再写回，等于给
     * "并发覆盖别人的修改"开了一道门。
     */
    @PutMapping("/{key}/enabled")
    public ResponseEntity<Result<Void>> setEnabled(@PathVariable String key,
                                                   @RequestBody EnabledBody body,
                                                   HttpServletRequest request) {
        ResponseEntity<Result<Void>> denied = requireAdmin(request);
        if (denied != null) return denied;
        service.setEnabled(key, body != null && body.enabled());
        return ok(null);
    }

    /**
     * 哪些域绑定了这个模型。
     *
     * <p>停用前的拦截提示。停用之后那些域会立刻问不了，
     * 而管理员的本意通常只是"把它从问数下拉里摘掉"。
     */
    @GetMapping("/{key}/domains")
    public ResponseEntity<Result<List<Map<String, Object>>>> boundDomains(
            @PathVariable String key, HttpServletRequest request) {
        ResponseEntity<Result<List<Map<String, Object>>>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ok(service.boundDomains(key));
    }

    /**
     * 测试**已保存**模型的连通性。
     *
     * <p>会真发一次极小的请求（几个 token）。这是刻意的：
     * 只校验格式的话，"地址错、密钥过期、模型名下架"三种情况完全测不出来，
     * 它们要到某个用户提问时才暴露，而那时报的是"处理失败，请稍后重试"。
     */
    @PostMapping("/{key}/test")
    public ResponseEntity<Result<ProbeBody>> testSaved(@PathVariable String key,
                                                       HttpServletRequest request) {
        ResponseEntity<Result<ProbeBody>> denied = requireAdmin(request);
        if (denied != null) return denied;
        return ok(ProbeBody.of(service.testSaved(key)));
    }

    /**
     * 测试**表单里尚未保存**的配置。
     *
     * <p>密钥留空时用已存密钥——管理页回显不了密钥，编辑时留空是常态，
     * 拿空密钥去测只会得到一次无意义的 401。
     *
     * @param key 编辑中的模型键；新增时为空
     */
    @PostMapping("/test")
    public ResponseEntity<Result<ProbeBody>> testDraft(
            @RequestBody DraftBody body, HttpServletRequest request) {
        ResponseEntity<Result<ProbeBody>> denied = requireAdmin(request);
        if (denied != null) return denied;
        if (body == null || body.model() == null) {
            return ResponseEntity.badRequest().body(Result.error(400, "缺少模型配置"));
        }
        return ok(ProbeBody.of(service.testDraft(body.key(), body.model())));
    }

    /** 启停请求体。 */
    public record EnabledBody(boolean enabled) {
    }

    /** 表单试连请求体。 */
    public record DraftBody(String key, ModelInput model) {
    }

    /** 试连结果。与数据源的 {@code ProbeBody} 同一形状，前端可复用同一段渲染。 */
    public record ProbeBody(boolean ok, String message, long elapsedMs) {
        static ProbeBody of(ModelProbe.Result r) {
            return new ProbeBody(r.ok(), r.message(), r.elapsedMs());
        }
    }

    // ── 鉴权与错误 ──

    /**
     * 管理面的准入。
     *
     * <p>与 {@code AdminEngineController} 同一条规则：细粒度权限开启时
     * 判定已在进入方法体之前完成（{@code @PreAuthorize}），这里只兜底未登录。
     */
    private <T> ResponseEntity<Result<T>> requireAdmin(HttpServletRequest request) {
        String username = currentUser.username().orElse(null);
        if (username == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Result.error(401, "未登录"));
        }
        return null;
    }

    private String operator() {
        return currentUser.username().orElse("unknown");
    }

    private static <T> ResponseEntity<Result<T>> ok(T data) {
        return ResponseEntity.ok(Result.success(data));
    }

    /**
     * 输入问题转 400 而不是 500。
     *
     * <p>{@link AdminException} 的消息是写给管理员看的，直接透出。
     * 其它异常记日志并给通用提示——不把堆栈泄露给前端。
     */
    @ExceptionHandler(AdminException.class)
    public ResponseEntity<Result<Void>> handleAdminError(AdminException e) {
        return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgument(IllegalArgumentException e) {
        // ModelService 的校验也抛这个类型（键重复、单价为负）。对管理员来说
        // 它与 AdminException 是同一类：输入有问题，改一下再试
        return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Result<Void>> handleIllegalState(IllegalStateException e) {
        // 例如默认模型缺失这类状态不一致。也是管理员能处理的，给 400
        return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Result<Void>> handleUnexpected(RuntimeException e) {
        log.error("模型管理操作失败：{}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(500, "操作失败，请稍后重试"));
    }
}

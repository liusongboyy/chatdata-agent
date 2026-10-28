package com.liusongboyy.chatdata.iam.admin;

import com.liusongboyy.chatdata.common.core.result.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理。
 *
 * <h2>鉴权</h2>
 *
 * <p>过滤链对 {@code /iam/admin/**} 只要求"已登录"，真正的判据在本类的方法上。
 * <strong>类级 {@code @PreAuthorize("false")} 是默认拒绝</strong>——漏挂一个方法
 * 应该表现为"用不了"，而不是"任何登录用户都能调"。
 *
 * <p>{@code @perm} 与 {@code /iam/me} 共用同一个解析器，所以界面上看到的按钮
 * 与这里放行的接口必然一致。
 */
@RestController
@RequestMapping("/iam/admin/users")
@PreAuthorize("false")
public class AdminUserController {

    private final AdminUserService service;

    public AdminUserController(AdminUserService service) {
        this.service = service;
    }

    /**
     * 分页列表。
     *
     * <p>以前一次返回全部用户——10 万用户 ≈ 1.7 秒、20MB JSON，
     * 前端再把这 10 万行渲染进表格。现在由服务端分页，默认 20 条。
     */
    @GetMapping
    @PreAuthorize("@perm.has('iam.users')")
    public Result<AdminUserService.UserPage> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long orgId) {
        return Result.success(service.list(page, size, keyword, status, orgId));
    }

    /** 顶部统计。与列表分开，避免翻页时重复算全量。 */
    @GetMapping("/stats")
    @PreAuthorize("@perm.has('iam.users')")
    public Result<AdminUserService.UserStats> stats() {
        return Result.success(service.stats());
    }

    @GetMapping("/{id}")
    @PreAuthorize("@perm.has('iam.users')")
    public Result<AdminUserService.UserView> get(@PathVariable long id) {
        return Result.success(service.get(id));
    }

    /**
     * 新建用户。
     *
     * <p>请求体可以带 {@code roles} —— 那等于"能建账号"+"能授角色"两件事，
     * 服务层会再要求一次 {@code iam.users.roles}。光靠这里一个注解挡不住
     * "造一个超管账号"。
     */
    @PostMapping
    @PreAuthorize("@perm.has('iam.users.write')")
    public Result<Long> create(@RequestBody AdminInputs.UserCreate input) {
        return Result.success(service.create(input));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@perm.has('iam.users.write')")
    public Result<Void> update(@PathVariable long id, @RequestBody AdminInputs.UserUpdate input) {
        service.update(id, input);
        return Result.success();
    }

    @PutMapping("/{id}/password")
    @PreAuthorize("@perm.has('iam.users.resetPassword')")
    public Result<Void> resetPassword(@PathVariable long id,
                                      @RequestBody AdminInputs.PasswordReset input) {
        service.resetPassword(id, input);
        return Result.success();
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("@perm.has('iam.users.roles')")
    public Result<Void> assignRoles(@PathVariable long id,
                                    @RequestBody AdminInputs.RoleCodes input) {
        service.assignRoles(id, input);
        return Result.success();
    }
}

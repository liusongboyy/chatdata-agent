package com.liusongboyy.chatdata.iam.permission;

import com.liusongboyy.chatdata.iam.admin.AdminInputs;
import com.liusongboyy.chatdata.common.core.result.Result;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 功能权限目录与角色权限配置。
 *
 * <p>整个类的鉴权见方法上的 {@code @PreAuthorize}——本类在 {@code /iam/admin/**} 下，
 * 而那个前缀在过滤链上只是 {@code authenticated()}，真正的判据在这里。
 *
 * <p><strong>类级 {@code @PreAuthorize("false")} 是默认拒绝。</strong>
 * 没被方法级注解覆盖的方法一律拒绝，而不是对任何登录用户开放——
 * 漏挂一个注解应该表现为"用不了"，不是"谁都能用"。
 */
@RestController
@RequestMapping("/iam/admin")
@org.springframework.security.access.prepost.PreAuthorize("false")
public class AdminPermissionController {

    private final AdminRolePermissionService service;

    public AdminPermissionController(AdminRolePermissionService service) {
        this.service = service;
    }

    /**
     * 权限目录树。
     *
     * <p>不带模块节点的码：它们是分组，不可勾选。前端据此渲染三级勾选，
     * **标签与分组都来自这里**，不在前端重写一遍文案。
     */
    @GetMapping("/permissions")
    @org.springframework.security.access.prepost.PreAuthorize("@perm.hasAny('iam.roles.permissions','iam.roles')")
    public Result<List<PermissionCatalog.Node>> catalog() {
        return Result.success(service.catalog());
    }

    @GetMapping("/roles/{roleId}/permissions")
    @org.springframework.security.access.prepost.PreAuthorize("@perm.hasAny('iam.roles.permissions','iam.roles')")
    public Result<RolePermissionView> codes(@PathVariable long roleId) {
        return Result.success(new RolePermissionView(roleId, List.copyOf(service.codesOf(roleId))));
    }

    /**
     * 整表替换角色的权限。
     *
     * <p>请求体用 {@code List<String>} 而不是自定义类型：Spring MVC 的消息转换器
     * 用的是 Jackson 3，而 classpath 上同时有 Jackson 2，记录组件写成 Jackson 2 的
     * 类型会在进入方法体之前就构造失败（见 {@code AskEngineStreamController} 的同类说明）。
     */
    @PutMapping("/roles/{roleId}/permissions")
    @org.springframework.security.access.prepost.PreAuthorize("@perm.has('iam.roles.permissions')")
    public Result<Map<String, Object>> save(@PathVariable long roleId,
                                            @RequestBody AdminInputs.RolePermissionCodes input) {
        int saved = service.save(roleId, input == null ? null : input.codes(), operator());
        return Result.success(Map.of("saved", saved));
    }

    private static String operator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getName() == null ? "unknown" : auth.getName();
    }

    /** 角色当前配置的权限码。 */
    public record RolePermissionView(long roleId, List<String> codes) {
    }
}

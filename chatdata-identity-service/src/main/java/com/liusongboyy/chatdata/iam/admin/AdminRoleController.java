package com.liusongboyy.chatdata.iam.admin;

import com.liusongboyy.chatdata.common.core.result.Result;
import com.liusongboyy.chatdata.iam.role.RoleRow;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色管理。code 不可改，见 {@link AdminRoleService}。
 *
 * <p>类级默认拒绝，见 {@code AdminUserController} 的说明。
 */
@RestController
@RequestMapping("/iam/admin/roles")
@PreAuthorize("false")
public class AdminRoleController {

    private final AdminRoleService service;

    public AdminRoleController(AdminRoleService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@perm.has('iam.roles')")
    public Result<List<RoleRow>> list() {
        return Result.success(service.list());
    }

    @PostMapping
    @PreAuthorize("@perm.has('iam.roles.write')")
    public Result<Void> create(@RequestBody AdminInputs.RoleCreate input) {
        service.create(input);
        return Result.success();
    }

    @PutMapping("/{id}")
    @PreAuthorize("@perm.has('iam.roles.write')")
    public Result<Void> update(@PathVariable long id, @RequestBody AdminInputs.RoleUpdate input) {
        service.update(id, input);
        return Result.success();
    }
}

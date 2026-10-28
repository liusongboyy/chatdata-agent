package com.liusongboyy.chatdata.iam.admin;

import com.liusongboyy.chatdata.common.core.result.Result;
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
 * 组织管理。平铺返回，前端组树。
 *
 * <p>类级默认拒绝，见 {@code AdminUserController} 的说明。
 */
@RestController
@RequestMapping("/iam/admin/orgs")
@PreAuthorize("false")
public class AdminOrgController {

    private final AdminOrgService service;

    public AdminOrgController(AdminOrgService service) {
        this.service = service;
    }

    /**
     * 组织列表。
     *
     * <p>用户表单也要用它（选组织），所以判据是"任一用得上它的页面"——
     * 只卡 {@code iam.orgs} 的话，只有用户管理权限的人打开用户页时会因为
     * {@code Promise.all} 里这一个 403 而整页空白。
     */
    @GetMapping
    @PreAuthorize("@perm.hasAny('iam.orgs','iam.users')")
    public Result<List<AdminOrgService.OrgView>> list() {
        return Result.success(service.list());
    }

    @PostMapping
    @PreAuthorize("@perm.has('iam.orgs.write')")
    public Result<Long> create(@RequestBody AdminInputs.OrgCreate input) {
        return Result.success(service.create(input));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@perm.has('iam.orgs.write')")
    public Result<Void> update(@PathVariable long id, @RequestBody AdminInputs.OrgUpdate input) {
        service.update(id, input);
        return Result.success();
    }
}

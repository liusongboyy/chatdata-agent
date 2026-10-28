package com.liusongboyy.chatdata.iam.admin;

import com.liusongboyy.chatdata.iam.config.IamProperties;
import com.liusongboyy.chatdata.iam.org.OrgMapper;
import com.liusongboyy.chatdata.iam.org.OrgRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 组织管理。
 *
 * <h2>path 必须包含自己的 id</h2>
 *
 * <p>插入时先占位 {@code "/"}，拿到自增 id 后再写成 {@code 父path + id + "/"}。
 * 不能在插入前算出来——那时还不知道 id。
 *
 * <h2>两条路径：path（id）与 code_path（编码）</h2>
 *
 * <p>id 路径稳定但业务库看不懂，编码路径是行规则真正用得上的那个。
 * 两者在**同一次事务、同一个时机**写入：分开写会出现"id 路径已更新、
 * 编码路径还是旧父节点"的中间态，而那个状态下算出的行权限是错的。
 *
 * <p>P0 不支持移动节点：改 parentId 要连同整棵子树的 path 一起重写，
 * 而组织移动还会影响按组织授予的角色，属于独立的一次改动。
 */
@Service
public class AdminOrgService {

    private static final Logger log = LoggerFactory.getLogger(AdminOrgService.class);

    private final OrgMapper orgs;
    private final IamProperties properties;

    public AdminOrgService(OrgMapper orgs, IamProperties properties) {
        this.orgs = orgs;
        this.properties = properties;
    }

    public List<OrgView> list() {
        return orgs.selectAll(properties.getTenant()).stream().map(OrgView::of).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public long create(AdminInputs.OrgCreate input) {
        String code = require(input.code(), "组织编码不能为空");
        String name = require(input.name(), "组织名称不能为空");
        String parentPath = "/";
        String parentCodePath = "/";
        if (input.parentId() != null) {
            OrgRow parent = orgs.selectById(input.parentId(), properties.getTenant());
            if (parent == null) {
                throw new IamAdminException("父组织不存在：id=" + input.parentId());
            }
            parentPath = parent.path();
            // 父节点的编码路径为空说明存量数据没回填（迁移没跑），此时拼出来的
            // 编码路径也是错的。宁可拒绝，也不要写入一条会静默匹配不到数据的路径
            String pcp = parent.codePath();
            if (pcp == null || pcp.isBlank()) {
                throw new IamAdminException(
                        "父组织缺少编码路径（code_path 为空），请先执行 sql/iam/09-org-code-path.sql");
            }
            parentCodePath = pcp;
        }

        Map<String, Object> params = new HashMap<>();
        params.put("tenant", properties.getTenant());
        params.put("parentId", input.parentId());
        params.put("code", code);
        params.put("name", name);
        params.put("sortNo", input.sortNo() == null ? 0 : input.sortNo());
        params.put("enabled", input.enabled() == null || input.enabled());
        try {
            orgs.insertOrg(params);
        } catch (DuplicateKeyException e) {
            throw new IamAdminException("组织编码已存在：" + code);
        }
        Object idValue = params.get("id");
        if (!(idValue instanceof Number n)) {
            throw new IllegalStateException("新增组织后未回填主键");
        }
        long id = n.longValue();
        String path = parentPath + id + "/";
        String codePath = parentCodePath + code + "/";
        orgs.updatePath(id, path);
        orgs.updateCodePath(id, codePath);
        log.info("新增组织：id={} code={} path={} codePath={}", id, code, path, codePath);
        return id;
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(long id, AdminInputs.OrgUpdate input) {
        OrgRow current = orgs.selectById(id, properties.getTenant());
        if (current == null) {
            throw new IamAdminException("组织不存在：id=" + id);
        }
        orgs.updateOrg(id,
                input.name() == null || input.name().isBlank() ? current.name() : input.name().trim(),
                input.sortNo() == null ? current.sortNo() : input.sortNo(),
                input.enabled() == null ? current.enabled() : input.enabled());
        log.info("更新组织：id={}", id);
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IamAdminException(message);
        }
        return value.trim();
    }

    /** 平铺返回，由前端组树——组织规模小，前端组树比后端递归更简单。 */
    public record OrgView(long id, Long parentId, String code, String name,
                          String path, String codePath, int sortNo, boolean enabled, String createdAt) {
        static OrgView of(OrgRow row) {
            return new OrgView(row.id(), row.parentId(), row.code(), row.name(),
                    row.path(), row.codePath(), row.sortNo(), row.enabled(),
                row.createdAt() == null ? null : row.createdAt().toString());
        }
    }
}

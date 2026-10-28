package com.liusongboyy.chatdata.engine.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liusongboyy.chatdata.engine.permission.DomainPermission;
import com.liusongboyy.chatdata.engine.semantic.Domain;

/**
 * 语义域的创建/更新入参。
 *
 * @param name         域名称
 * @param description  描述
 * @param datasourceId 绑定的数据源 id，null 表示用应用默认库
 * @param status       enabled / disabled
 * @param joinsJson    表关联 JSON，可为 null
 * @param permJson     权限配置 JSON，可为 null（但启用中的域必须有授权，见校验）
 * @param sortNo       排序号
 * @param mode         模式 MODELED / RAW。**RAW 下不得配行/列规则**——见 {@link Domain.Mode}
 */
public record DomainInput(
        String name,
        String description,
        Long datasourceId,
        String status,
        String joinsJson,
        String permJson,
        int sortNo,
        String mode
) {
    /** 兼容构造器：不指定模式即建模模式。既有调用点（含测试）不必改。 */
    public DomainInput(String name, String description, Long datasourceId, String status,
                       String joinsJson, String permJson, int sortNo) {
        this(name, description, datasourceId, status, joinsJson, permJson, sortNo, null);
    }

    private static final ObjectMapper M = new ObjectMapper();

    public DomainInput validated() {
        require(name, "域名称");

        String s = (status == null || status.isBlank()) ? "enabled" : status.trim();
        if (!"enabled".equals(s) && !"disabled".equals(s)) {
            throw new AdminException("状态只能是 enabled 或 disabled，实际：" + status);
        }

        String joins = blankToNull(joinsJson);
        if (joins != null) {
            try {
                M.readTree(joins);
            } catch (Exception e) {
                throw new AdminException("表关联配置不是合法 JSON：" + e.getMessage());
            }
        }

        Domain.Mode m = Domain.Mode.parse(mode);

        String perm = blankToNull(permJson);
        if (perm != null) {
            DomainPermission parsed;
            try {
                parsed = DomainPermission.from(M.readTree(perm));
            } catch (Exception e) {
                throw new AdminException("权限配置不是合法 JSON：" + e.getMessage());
            }

            // 角色名/用户名里出现分隔符，几乎必然是"多个值没被切开"——
            // 实测踩到过：前端提示「多个用、分隔」但切分符里漏了顿号，
            // 于是 grantRoles 变成了 ["区域经理、分析师"] 这一个不存在的角色，
            // **授权静默失效**（所有用户都被拒）。在这里拦住，把静默变成显式报错。
            rejectSeparators(parsed.grantRoles(), "授权角色");
            rejectSeparators(parsed.grantUsers(), "授权用户");
            rejectSeparators(parsed.unrestrictedRoles(), "可见全量的角色");

            // **直连模式不执行行/列规则**：SQL 由模型自己写，形状任意（CTE/UNION/子查询），
            // 没有安全的注入点（docs/adr/0002 已否决"解析后注入谓词"）。
            // 允许配置却不执行，等于让管理员以为自己配了限制——所以在这里拒绝保存，
            // 而不是静默忽略。
            if (m == Domain.Mode.RAW
                    && (!parsed.rowRules().isEmpty() || !parsed.colRules().isEmpty())) {
                throw new AdminException(
                        "直连模式（RAW）不支持行级/列级规则：该模式由模型自己写 SQL，"
                                + "规则没有安全的注入点，配了也不会生效。"
                                + "若需要行/列权限，请改用建模模式（MODELED），"
                                + "或把这两项清空后再保存。");
            }

            // **在源头拦住，而不是等启动校验**。
            // 一个启用中却没有授权的域，按厂商语义是对所有已认证用户开放——
            // 而那正是本项目要修的主要缺口。让它在保存时就失败，
            // 比让它在下次重启时把整个应用拦下来要好得多。
            if ("enabled".equals(s) && !parsed.hasAnyGrant()) {
                throw new AdminException(
                        "启用中的数据域必须配置访问授权：grantRoles / grantUsers 至少填一个，"
                                + "或把 isPublic 设为 true。"
                                + "否则该域对所有已通过认证的用户开放。"
                                + "（若只是草稿，请把状态设为 disabled）");
            }
        } else if ("enabled".equals(s)) {
            throw new AdminException(
                    "启用中的数据域必须配置访问授权。若只是草稿，请把状态设为 disabled");
        }

        return new DomainInput(name.trim(), blankToNull(description), datasourceId,
                s, joins, perm, sortNo, m.name());
    }

    private static void require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new AdminException(label + "不能为空");
        }
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /**
     * 拒绝带分隔符的名字。
     *
     * <p>角色名与用户名里出现 {@code 、} {@code ，} {@code ,} {@code ;} 时，
     * 几乎必然是"多个值没被切开"——那是**静默失效**：授权名单里多了一个
     * 谁都不匹配的字符串，结果是所有用户都被拒（或都放行，取决于在哪一层）。
     *
     * <p>合法角色名里出现这些字符的可能性极低，而误判的代价只是一次明确的报错。
     */
    private static void rejectSeparators(java.util.Set<String> values, String label) {
        for (String v : values) {
            if (v.chars().anyMatch(c -> c == '、' || c == '，' || c == ',' || c == ';' || c == '；')) {
                throw new AdminException(
                        "%s「%s」里含有分隔符。请把多个值拆成独立的多项，"
                                .formatted(label, v)
                                + "否则它会被当成一个角色名，导致授权静默失效。");
            }
        }
    }
}

package com.liusongboyy.chatdata.engine.permission;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 语义域的权限配置，解析自 {@code ask_domain.perm_json}。
 *
 * <p>沿用厂商的 JSON blob 形态（不拆表，见 PRD §6），字段名保持一致以便照搬配置。
 *
 * <h2>相对厂商的两处改动</h2>
 *
 * <ol>
 *   <li><b>新增 {@code v} 版本字段。</b>不拆表意味着 schema 演进只能靠约定，
 *       没有版本号的话半年后没人知道某条 JSON 是哪个版本写的。
 *       解析时按版本分支，缺失版本按当前版本处理但**记录告警**。</li>
 *   <li><b>新增 {@code unrestrictedRoles}。</b>厂商的 {@code rowRules} 是
 *       "按角色授予限制"——某角色没有匹配规则时**不附加任何过滤**，看到全部行。
 *       这是默认放行，必须改。改法不是"没规则就拒绝"（那会让所有未配规则的域直接不可用），
 *       而是**要求显式声明**：谁可以看全量，写进 {@code unrestrictedRoles}；
 *       没写又没有规则的，一律拒绝。见 {@link RowScope}。</li>
 * </ol>
 *
 * @param version           perm_json 的 schema 版本（{@code v} 字段）
 * @param grantRoles        可访问本域的角色
 * @param grantUsers        可访问本域的用户
 * @param isPublic          是否对所有已认证用户开放
 * @param unrestrictedRoles 显式授予"可见全量行"的角色（本项目的扩展字段）
 * @param rowRules          行级规则
 * @param colRules          列级规则
 */
public record DomainPermission(
        int version,
        Set<String> grantRoles,
        Set<String> grantUsers,
        boolean isPublic,
        Set<String> unrestrictedRoles,
        List<RowRule> rowRules,
        List<ColRule> colRules
) {

    /** 当前 perm_json 的 schema 版本。字段有增删时递增，并在解析处加分支。 */
    public static final int CURRENT_VERSION = 1;

    public DomainPermission {
        grantRoles = grantRoles == null ? Set.of() : Set.copyOf(grantRoles);
        grantUsers = grantUsers == null ? Set.of() : Set.copyOf(grantUsers);
        unrestrictedRoles = unrestrictedRoles == null ? Set.of() : Set.copyOf(unrestrictedRoles);
        rowRules = rowRules == null ? List.of() : List.copyOf(rowRules);
        colRules = colRules == null ? List.of() : List.copyOf(colRules);
    }

    /**
     * 从 {@code perm_json} 解析。
     *
     * <p>对缺失字段一律按**失败关闭**处理：解析不出来就是空授权、无豁免，
     * 而不是"解析失败就放行"。厂商两层的默认值都是放行，那是本项目要修的主要缺口。
     */
    public static DomainPermission from(JsonNode json) {
        if (json == null || json.isMissingNode() || json.isNull()) {
            // 完全没有配置 = 没有任何授权 = 谁都进不来（失败关闭）
            return new DomainPermission(CURRENT_VERSION, Set.of(), Set.of(), false, Set.of(), List.of(), List.of());
        }

        List<RowRule> rows = new ArrayList<>();
        json.path("rowRules").forEach(n -> rows.add(RowRule.from(n)));

        List<ColRule> cols = new ArrayList<>();
        json.path("colRules").forEach(n -> cols.add(ColRule.from(n)));

        return new DomainPermission(
                json.path("v").asInt(CURRENT_VERSION),
                strings(json, "grantRoles"),
                strings(json, "grantUsers"),
                json.path("isPublic").asBoolean(false),
                strings(json, "unrestrictedRoles"),
                rows,
                cols);
    }

    /**
     * 域层是否放行该主体。
     *
     * <p>厂商判据：{@code grantRoles} / {@code grantUsers} 有内容，或 {@code isPublic=true}。
     * 但厂商在**两者皆空**时是"对所有已认证用户开放"——那是默认放行，我们改为拒绝。
     * 本方法返回 false 时，调用方应拒绝整个请求。
     */
    public boolean grantsAccess(PermissionContext ctx) {
        if (isPublic) {
            return true;
        }
        if (grantUsers.contains(ctx.userId())) {
            return true;
        }
        for (String role : ctx.roles()) {
            if (grantRoles.contains(role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本域是否已配置过任何授权。
     *
     * <p>供启动校验使用（PRD FR-3.8）：一个启用中的域若这里返回 false，
     * **必须拒绝启动**，而不是静默地对所有人开放。
     */
    public boolean hasAnyGrant() {
        return isPublic || !grantRoles.isEmpty() || !grantUsers.isEmpty();
    }

    /** 本域是否有任何角色被显式授予全量行可见。 */
    public boolean hasUnrestrictedRole(Set<String> roles) {
        for (String r : roles) {
            if (unrestrictedRoles.contains(r)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> strings(JsonNode parent, String field) {
        Set<String> out = new TreeSet<>();
        parent.path(field).forEach(n -> {
            String s = n.asText();
            if (s != null && !s.isBlank()) {
                out.add(s);
            }
        });
        return out;
    }
}

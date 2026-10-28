package com.liusongboyy.chatdata.engine.permission;

import java.util.Set;
import java.util.TreeSet;

/**
 * 计算主体在当前域内需要脱敏的列。
 *
 * <p>只做"按配置打码"，**不做等价字段发现**——后者是语义建模侧的职责，原因见 {@link ColRule}。
 */
public final class ColumnMasker {

    /** 脱敏后的显示值。与厂商行为一致，便于对照排查。 */
    public static final String MASKED = "***";

    private ColumnMasker() {
    }

    /**
     * 返回本主体需要脱敏的列名集合。
     *
     * <p>多个角色的规则取**并集**：一个用户若因任一角色受限，该列就应脱敏。
     * 这里与行规则相反，用并集而非交集——列级是"限制"，取并集才是收紧。
     */
    public static Set<String> maskedColumns(DomainPermission perm, PermissionContext ctx) {
        Set<String> masked = new TreeSet<>();
        for (ColRule rule : perm.colRules()) {
            if (rule.field() == null || rule.field().isBlank()) {
                continue;
            }
            if (!rule.shouldMask()) {
                continue;
            }
            if (ctx.hasRole(rule.role())) {
                masked.add(rule.field().trim());
            }
        }
        return Set.copyOf(masked);
    }

    /** 某个列是否需要脱敏。列名比对**忽略大小写**——SQL 标识符在不同库大小写敏感性不同。 */
    public static boolean shouldMask(Set<String> maskedColumns, String column) {
        if (column == null || maskedColumns.isEmpty()) {
            return false;
        }
        String c = column.trim();
        for (String m : maskedColumns) {
            if (m.equalsIgnoreCase(c)) {
                return true;
            }
        }
        return false;
    }
}

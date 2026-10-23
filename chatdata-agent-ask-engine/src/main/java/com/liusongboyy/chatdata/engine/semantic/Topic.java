package com.liusongboyy.chatdata.engine.semantic;

import java.util.List;

/**
 * 查询主题。
 *
 * <p>域内表多的时候，主题把「这次问什么」收敛到一个字段子集——
 * prompt 更短，模型选错表与选错字段的概率更低。
 *
 * <p>与术语的分工：术语解决「同一个东西的不同说法」，主题解决「一次只问一小块」。
 *
 * @param name        主题名称
 * @param description 描述
 * @param published   是否已发布。草稿不进 prompt
 * @param fields      字段引用，形如 {@code ["ins_policy.premium", "ins_customer.cust_level"]}
 */
public record Topic(
        String name,
        String description,
        boolean published,
        List<String> fields
) {

    public Topic {
        if (name == null || name.isBlank()) {
            throw new SemanticException(SemanticException.Reason.INVALID_TABLE_DEFINITION,
                    "主题缺少名称");
        }
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    /**
     * 字段引用是否合法：必须是 {@code 表名.列名} 的形式。
     *
     * <p>这里只校验形状，**不校验表与列是否真的存在**——那需要域内的注册表信息，
     * 由 {@code AdminDomainService} 在保存时做（那里能拿到域的全部表）。
     * 放在两个地方校验同一个东西会分叉，故职责分开。
     */
    public static boolean isWellFormed(String fieldRef) {
        if (fieldRef == null) {
            return false;
        }
        String[] parts = fieldRef.trim().split("\\.");
        return parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank();
    }
}

package com.liusongboyy.chatdata.iam.org;

import java.time.LocalDateTime;

/**
 * 组织。
 *
 * @param path 物化路径（/1/12/37/）。判断"某组织及其子树"用前缀匹配，
 *             不要递归查询——组织树在千级以内，前缀匹配既简单又稳定。
 * @param codePath 编码物化路径（/HQ/华东/上海/），与 {@code path} 同构，只是用 code。
 *             业务库认编码不认 id，行规则的动态变量 {@code orgCodePath} 取它。
 *             code 与 parentId 创建后不可改，所以它和 path 一样不会漂移。
 */
public record OrgRow(
        long id,
        String tenantId,
        Long parentId,
        String code,
        String name,
        String path,
        String codePath,
        int sortNo,
        boolean enabled,
        LocalDateTime createdAt
) {
}

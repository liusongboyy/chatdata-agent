package com.liusongboyy.chatdata.iam.org;

import java.time.LocalDateTime;

/**
 * 组织。
 *
 * @param path 物化路径（/1/12/37/）。判断"某组织及其子树"用前缀匹配，
 *             不要递归查询——组织树在千级以内，前缀匹配既简单又稳定。
 */
public record OrgRow(
        long id,
        String tenantId,
        Long parentId,
        String code,
        String name,
        String path,
        int sortNo,
        boolean enabled,
        LocalDateTime createdAt
) {
}

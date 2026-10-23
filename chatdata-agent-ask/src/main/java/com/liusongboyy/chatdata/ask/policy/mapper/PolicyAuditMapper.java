package com.liusongboyy.chatdata.ask.policy.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 策略变更审计落库（{@code ask_policy_audit}，见 {@code sql/18-ask-policy-audit.sql}）。
 *
 * <p>只记摘要不记全文：策略里是角色码/用户名与规则，价值在于"谁在什么时候
 * 把哪条策略改成了什么版本"，全文会让表迅速膨胀。需要还原时按摘要与
 * 备份表（{@link #SQL} 注释中的迁移脚本）对照。
 */
@Mapper
public interface PolicyAuditMapper {

    @Insert("""
            INSERT INTO ask_policy_audit
              (resource_type, resource_id, resource_name, action, operator, perm_digest, created_at)
            VALUES
              (#{resourceType}, #{resourceId}, #{resourceName}, #{action}, #{operator},
               #{permDigest}, #{createdAt})
            """)
    int insert(@Param("resourceType") String resourceType,
               @Param("resourceId") long resourceId,
               @Param("resourceName") String resourceName,
               @Param("action") String action,
               @Param("operator") String operator,
               @Param("permDigest") String permDigest,
               @Param("createdAt") LocalDateTime createdAt);
}

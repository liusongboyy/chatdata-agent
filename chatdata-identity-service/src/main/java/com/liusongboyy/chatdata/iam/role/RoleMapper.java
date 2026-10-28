package com.liusongboyy.chatdata.iam.role;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface RoleMapper {

    @Select("""
            SELECT id, tenant_id AS tenantId, code, name, category, description, enabled,
                   (SELECT COUNT(*) FROM iam_role_permission p WHERE p.role_id = r.id)
                       AS permissionCount
            FROM iam_role r
            WHERE tenant_id = #{tenant}
            ORDER BY category, id
            """)
    List<RoleRow> selectAll(@Param("tenant") String tenant);

    @Select("""
            SELECT id, tenant_id AS tenantId, code, name, category, description, enabled,
                   (SELECT COUNT(*) FROM iam_role_permission p WHERE p.role_id = r.id)
                       AS permissionCount
            FROM iam_role r
            WHERE tenant_id = #{tenant} AND code = #{code}
            """)
    RoleRow selectByCode(@Param("tenant") String tenant, @Param("code") String code);

    @Insert("""
            INSERT INTO iam_role (tenant_id, code, name, category, description, enabled)
            VALUES (#{tenant}, #{code}, #{name}, #{category}, #{description}, #{enabled})
            """)
    int insertRole(@Param("tenant") String tenant,
                   @Param("code") String code,
                   @Param("name") String name,
                   @Param("category") String category,
                   @Param("description") String description,
                   @Param("enabled") boolean enabled);

    /** code 不在更新语句里：角色码是不可变的。 */
    @Update("""
            UPDATE iam_role
            SET name = #{name}, category = #{category},
                description = #{description}, enabled = #{enabled}
            WHERE id = #{id}
            """)
    int updateRole(@Param("id") long id,
                   @Param("name") String name,
                   @Param("category") String category,
                   @Param("description") String description,
                   @Param("enabled") boolean enabled);
}

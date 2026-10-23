package com.liusongboyy.chatdata.iam.permission;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RolePermissionMapper {

    @Select("SELECT code FROM iam_role_permission WHERE role_id = #{roleId} ORDER BY code")
    List<String> selectCodesByRole(@Param("roleId") long roleId);

    /**
     * 某用户经其**启用中**角色得到的全部权限码。
     *
     * <p><strong>{@code DISTINCT} 不能省。</strong>两个角色可能配了同一个码，而下游
     * {@code Set.copyOf} / {@code Set.of} 遇到重复元素会**直接抛异常**——
     * 在 ask 侧表现为 401（不是缓存未命中），在 IAM 侧表现为解码失败后每次请求都回源。
     *
     * <p>{@code r.enabled = 1} 与 {@code UserMapper.selectRoleCodes} 保持同一判据，
     * 否则 {@code roles} 与 {@code permissions} 会对不上：角色被停用了，
     * 角色列表里没有了，权限却还在。
     */
    @Select("""
            SELECT DISTINCT p.code
            FROM iam_user_role ur
            JOIN iam_role r ON r.id = ur.role_id AND r.enabled = 1
            JOIN iam_role_permission p ON p.role_id = r.id
            WHERE ur.user_id = #{userId}
            ORDER BY p.code
            """)
    List<String> selectCodesByUser(@Param("userId") long userId);

    @Delete("DELETE FROM iam_role_permission WHERE role_id = #{roleId}")
    int deleteByRole(@Param("roleId") long roleId);

    @Insert("""
            INSERT INTO iam_role_permission (role_id, code)
            VALUES (#{roleId}, #{code})
            """)
    int insert(@Param("roleId") long roleId, @Param("code") String code);

    @Select("SELECT COUNT(*) FROM iam_role_permission WHERE role_id = #{roleId}")
    int countByRole(@Param("roleId") long roleId);
}

package com.liusongboyy.chatdata.iam.org;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface OrgMapper {

    @Select("""
            SELECT id, tenant_id AS tenantId, parent_id AS parentId, code, name, path,
                   sort_no AS sortNo, enabled, created_at AS createdAt
            FROM iam_org
            WHERE tenant_id = #{tenant}
            ORDER BY sort_no, id
            """)
    List<OrgRow> selectAll(@Param("tenant") String tenant);

    /**
     * 按 id 取一行。
     *
     * <p><strong>带 {@code tenant_id} 断言。</strong>原先只有 {@code WHERE id = #{id}}——
     * 而 {@code AdminOrgService.create} 用它校验父组织、{@code update} 用它取现值，
     * 于是另一个租户的组织 id 能被当成合法父节点引用。租户隔离是硬约束（spec §7.2），
     * 少一个断言就等于没有。
     */
    @Select("""
            SELECT id, tenant_id AS tenantId, parent_id AS parentId, code, name, path,
                   sort_no AS sortNo, enabled, created_at AS createdAt
            FROM iam_org
            WHERE id = #{id} AND tenant_id = #{tenant}
            """)
    OrgRow selectById(@Param("id") long id, @Param("tenant") String tenant);

    /** 无租户断言的版本，仅供 {@code AdminOrgService} 之外的内部调用（如按主键回读 path）。 */
    @Select("""
            SELECT id, tenant_id AS tenantId, parent_id AS parentId, code, name, path,
                   sort_no AS sortNo, enabled, created_at AS createdAt
            FROM iam_org
            WHERE id = #{id}
            """)
    OrgRow selectByIdUnscoped(@Param("id") long id);

    @Select("""
            SELECT o.id, o.tenant_id AS tenantId, o.parent_id AS parentId, o.code, o.name,
                   o.path, o.sort_no AS sortNo, o.enabled, o.created_at AS createdAt
            FROM iam_user_org uo
            JOIN iam_org o ON o.id = uo.org_id
            WHERE uo.user_id = #{userId}
            ORDER BY uo.is_primary DESC, o.id
            LIMIT 1
            """)
    OrgRow selectPrimaryByUser(@Param("userId") long userId);

    /**
     * 该用户所属的**全部**组织路径（主组织在前）。
     *
     * <p>兼岗场景需要：一个人同时管华东与华南时，行权限要取两个子树的并集，
     * 只取主组织会让另一半数据凭空消失——而用户不知道是权限配错了。
     *
     * <p>排除已停用的组织：停用意味着那段树不再有效，把它的路径带进权限
     * 会让"已撤销的管辖范围"仍然可见。
     */
    @Select("""
            SELECT o.path
            FROM iam_user_org uo
            JOIN iam_org o ON o.id = uo.org_id
            WHERE uo.user_id = #{userId}
              AND o.enabled = 1
            ORDER BY uo.is_primary DESC, o.id
            """)
    java.util.List<String> selectOrgPathsByUser(@Param("userId") long userId);

    /**
     * 插入。path 先占位，拿到自增 id 后由调用方回填——
     * 路径必须包含自己的 id，不能在建行前算出来。
     */
    @Insert("""
            INSERT INTO iam_org (tenant_id, parent_id, code, name, path, sort_no, enabled)
            VALUES (#{tenant}, #{parentId}, #{code}, #{name}, '/', #{sortNo}, #{enabled})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertOrg(Map<String, Object> params);

    @Update("""
            UPDATE iam_org
            SET name = #{name}, sort_no = #{sortNo}, enabled = #{enabled}
            WHERE id = #{id}
            """)
    int updateOrg(@Param("id") long id,
                  @Param("name") String name,
                  @Param("sortNo") int sortNo,
                  @Param("enabled") boolean enabled);

    @Update("UPDATE iam_org SET path = #{path} WHERE id = #{id}")
    int updatePath(@Param("id") long id, @Param("path") String path);
}

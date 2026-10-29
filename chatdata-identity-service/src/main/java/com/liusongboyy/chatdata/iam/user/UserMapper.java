package com.liusongboyy.chatdata.iam.user;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 用户的读写。
 *
 * <p>insert 用 Map 参数是为了回填自增主键：MyBatis 的 {@code keyProperty}
 * 只能写入可变对象，而本项目的行模型习惯用 record。
 */
@Mapper
public interface UserMapper {

    @Select("""
            SELECT id, username, password_hash AS passwordHash, display_name AS displayName,
                   status, token_version AS tokenVersion
            FROM iam_user
            WHERE tenant_id = #{tenant} AND username = #{username}
            """)
    UserRow selectByUsername(@Param("tenant") String tenant, @Param("username") String username);

    @Select("""
            SELECT id, username, password_hash AS passwordHash, display_name AS displayName,
                   status, token_version AS tokenVersion
            FROM iam_user
            WHERE id = #{id}
            """)
    UserRow selectById(@Param("id") long id);

    /**
     * 按外部身份查用户。渠道（飞书/钉钉/企微）换令牌时用它反查。
     *
     * <p><strong>这是 channel grant 的安全支点。</strong>渠道提交
     * {@code (provider=feishu, username)} 时，必须确认这个 username
     * 确实绑定了本渠道——只校验白名单不反查，等于给了一把万能钥匙。
     *
     * <p>查不到返回 null（未绑定），调用方据此拒绝，不要抛异常——
     * "未绑定"是正常状态，不是错误。
     */
    @Select("""
            SELECT id, username, password_hash AS passwordHash, display_name AS displayName,
                   status, token_version AS tokenVersion
            FROM iam_user
            WHERE tenant_id = #{tenant}
              AND username = #{username}
              AND external_provider = #{provider}
              AND external_id IS NOT NULL
              AND external_id <> ''
            """)
    UserRow selectBoundToChannel(@Param("tenant") String tenant,
                                 @Param("username") String username,
                                 @Param("provider") String provider);

    /** 启用中的角色码。停用角色不参与鉴权。 */
    @Select("""
            SELECT r.code
            FROM iam_user_role ur
            JOIN iam_role r ON r.id = ur.role_id
            WHERE ur.user_id = #{userId} AND r.enabled = 1
            ORDER BY r.code
            """)
    List<String> selectRoleCodes(@Param("userId") long userId);


    @Select("""
            SELECT u.id AS id,
                   u.username AS username,
                   u.display_name AS displayName,
                   u.status AS status,
                   u.created_at AS createdAt,
                   (SELECT o.name FROM iam_user_org uo
                      JOIN iam_org o ON o.id = uo.org_id
                     WHERE uo.user_id = u.id
                     ORDER BY uo.is_primary DESC, o.id LIMIT 1) AS orgName,
                   -- 与 orgName 用同一排序：编辑表单要用它预选当前组织。
                   -- 只给 orgName 的话前端得按名字反查，重名组织就会选错
                   (SELECT uo.org_id FROM iam_user_org uo
                     WHERE uo.user_id = u.id
                     ORDER BY uo.is_primary DESC, uo.org_id LIMIT 1) AS orgId,
                   (SELECT GROUP_CONCAT(DISTINCT r.code ORDER BY r.code)
                      FROM iam_user_role ur JOIN iam_role r ON r.id = ur.role_id
                     WHERE ur.user_id = u.id) AS roleCodes,
                   u.external_provider AS externalProvider,
                   u.external_id AS externalId
            FROM iam_user u
            WHERE u.id = #{id}
            """)
    AdminUserRow selectAdminById(@Param("id") long id);

    /**
     * 绑定/解绑外部身份（渠道用）。
     *
     * <p>{@code provider}/{@code externalId} 同时为 null 表示解绑。
     * <strong>不做 upsert</strong>：绑定冲突要让调用方拿到
     * {@code DuplicateKeyException} 并给出「该飞书账号已绑定到 xxx」，
     * 静默覆盖会把别人的绑定抢走——那是数据事故，不是便利。
     *
     * <p>绑定变化要 {@code token_version + 1}：已经签发出去的渠道令牌
     * 仍在 TTL 内，改绑后应尽快失效（与改密码同一取舍）。
     */
    @Update("""
            UPDATE iam_user
            SET external_provider = #{provider},
                external_id = #{externalId},
                token_version = token_version + 1
            WHERE id = #{id}
            """)
    int updateExternalIdentity(@Param("id") long id,
                               @Param("provider") String provider,
                               @Param("externalId") String externalId);

    @Insert("""
            INSERT INTO iam_user (tenant_id, username, password_hash, display_name, status)
            VALUES (#{tenant}, #{username}, #{passwordHash}, #{displayName}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertUser(Map<String, Object> params);

    @Update("""
            UPDATE iam_user
            SET display_name = #{displayName}, status = #{status},
                token_version = token_version + 1
            WHERE id = #{id}
            """)
    int updateUser(@Param("id") long id,
                   @Param("displayName") String displayName,
                   @Param("status") String status);

    /** 改密码时 token_version +1：已签发的令牌全部失效。 */
    @Update("""
            UPDATE iam_user
            SET password_hash = #{passwordHash}, token_version = token_version + 1
            WHERE id = #{id}
            """)
    int updatePassword(@Param("id") long id, @Param("passwordHash") String passwordHash);

    @Delete("DELETE FROM iam_user_role WHERE user_id = #{userId}")
    int deleteUserRoles(@Param("userId") long userId);

    /**
     * 按 code 授角色；不存在的 code 不会插入任何行。
     *
     * <p><strong>幂等。</strong>同一个用户重复授予同一个角色不会插出第二行——
     * 这一点不能靠唯一键：{@code uk_iam_user_role} 是
     * {@code (user_id, role_id, org_id)}，而**租户级授予的 {@code org_id} 是 NULL**，
     * MySQL 的唯一键里 NULL 互不相等，所以 {@code INSERT IGNORE} / {@code ON DUPLICATE KEY}
     * 都拦不住。重复行会让用户列表显示成 {@code 区域经理,区域经理}（{@code GROUP_CONCAT} 不去重），
     * 也会让"几个持有者"这类统计悄悄偏大。实测踩到过。
     *
     * <p>子查询套一层派生表是必须的：MySQL 不允许在 {@code INSERT ... SELECT} 的子查询里
     * 直接引用目标表（错误 1093），派生表会先物化，从而绕过该限制。
     */
    @Insert("""
            INSERT INTO iam_user_role (user_id, role_id, org_id)
            SELECT #{userId}, r.id, NULL
            FROM iam_role r
            WHERE r.tenant_id = #{tenant} AND r.code = #{roleCode}
              AND NOT EXISTS (
                  SELECT 1 FROM (
                      SELECT user_id, role_id FROM iam_user_role WHERE org_id IS NULL
                  ) t
                  WHERE t.user_id = #{userId} AND t.role_id = r.id
              )
            """)
    int insertUserRole(@Param("userId") long userId,
                       @Param("tenant") String tenant,
                       @Param("roleCode") String roleCode);

    @Delete("DELETE FROM iam_user_org WHERE user_id = #{userId}")
    int deleteUserOrgs(@Param("userId") long userId);

    @Insert("""
            INSERT INTO iam_user_org (user_id, org_id, is_primary)
            VALUES (#{userId}, #{orgId}, #{primary})
            """)
    int insertUserOrg(@Param("userId") long userId,
                      @Param("orgId") long orgId,
                      @Param("primary") boolean primary);

    /**
     * 管理台列表的一行。
     *
     * <p>{@code roleCodes} 是逗号分隔串（GROUP_CONCAT），由服务层拆开——
     * 这样 MyBatis 不需要自定义 TypeHandler。
     */
    record AdminUserRow(
            long id,
            String username,
            String displayName,
            String status,
            LocalDateTime createdAt,
            String orgName,
            Long orgId,
            String roleCodes,
            /** 外部身份来源（feishu/dingtalk/…），未绑定为 null。 */
            String externalProvider,
            /** 外部身份 id（如飞书 open_id），未绑定为 null。 */
            String externalId
    ) {
    }

    /**
     * 管理台列表（分页）。
     *
     * <h2>为什么不用 JOIN 而是 {@code IN (子查询)}</h2>
     *
     * <p>一个人可能有多条 {@code iam_user_org}（历史数据里 {@code is_primary} 也不保证唯一），
     * JOIN 会把一个人变成多行，分页的 total 与页大小都会错。
     * {@code IN} 只做"这个人在不在范围内"的判定，不会放大行数。
     *
     * <p>关键字同时搜账号、姓名、组织名、角色码/角色名。前两个是列上的 LIKE，
     * 后两个走 IN 子查询——它们作用在不同的表上，写成一次 JOIN 会把 10 万行乘开。
     * {@code %kw%} 用不上索引，10 万用户下约 0.1～0.6 秒（见 spec 的性能笔记）；
     * 搜索框有防抖，可以接受。要更快就得上全文索引，那是另一件事。
     */
    @Select("""
            <script>
            SELECT u.id AS id,
                   u.username AS username,
                   u.display_name AS displayName,
                   u.status AS status,
                   u.created_at AS createdAt,
                   (SELECT o.name FROM iam_user_org uo
                      JOIN iam_org o ON o.id = uo.org_id
                     WHERE uo.user_id = u.id
                     ORDER BY uo.is_primary DESC, o.id LIMIT 1) AS orgName,
                   (SELECT uo.org_id FROM iam_user_org uo
                     WHERE uo.user_id = u.id
                     ORDER BY uo.is_primary DESC, uo.org_id LIMIT 1) AS orgId,
                   (SELECT GROUP_CONCAT(DISTINCT r.code ORDER BY r.code)
                      FROM iam_user_role ur JOIN iam_role r ON r.id = ur.role_id
                     WHERE ur.user_id = u.id) AS roleCodes,
                   u.external_provider AS externalProvider,
                   u.external_id AS externalId
            FROM iam_user u
            WHERE u.tenant_id = #{tenant}
            <if test="status != null"> AND u.status = #{status} </if>
            <if test="orgPath != null">
                AND u.id IN (SELECT uo.user_id
                               FROM iam_user_org uo JOIN iam_org o ON o.id = uo.org_id
                              WHERE o.path LIKE CONCAT(#{orgPath}, '%'))
            </if>
            <if test="like != null">
                AND (u.username LIKE #{like} ESCAPE '!'
                     OR u.display_name LIKE #{like} ESCAPE '!'
                     OR u.id IN (SELECT uo.user_id
                                   FROM iam_user_org uo JOIN iam_org o ON o.id = uo.org_id
                                  WHERE o.name LIKE #{like} ESCAPE '!')
                     OR u.id IN (SELECT ur.user_id
                                   FROM iam_user_role ur JOIN iam_role r ON r.id = ur.role_id
                                  WHERE r.code LIKE #{like} ESCAPE '!'
                                     OR r.name LIKE #{like} ESCAPE '!'))
            </if>
            ORDER BY u.id
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AdminUserRow> selectPage(@Param("tenant") String tenant,
                                  @Param("status") String status,
                                  @Param("orgPath") String orgPath,
                                  @Param("like") String like,
                                  @Param("limit") int limit,
                                  @Param("offset") long offset);

    /**
     * 与 {@link #selectPage} 同一套条件。
     *
     * <p>条件写两遍是为了让两条 SQL 各自可读；漂移由
     * {@code AdminUserServiceTest} 对参数透传的断言兜住。
     */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM iam_user u
            WHERE u.tenant_id = #{tenant}
            <if test="status != null"> AND u.status = #{status} </if>
            <if test="orgPath != null">
                AND u.id IN (SELECT uo.user_id
                               FROM iam_user_org uo JOIN iam_org o ON o.id = uo.org_id
                              WHERE o.path LIKE CONCAT(#{orgPath}, '%'))
            </if>
            <if test="like != null">
                AND (u.username LIKE #{like} ESCAPE '!'
                     OR u.display_name LIKE #{like} ESCAPE '!'
                     OR u.id IN (SELECT uo.user_id
                                   FROM iam_user_org uo JOIN iam_org o ON o.id = uo.org_id
                                  WHERE o.name LIKE #{like} ESCAPE '!')
                     OR u.id IN (SELECT ur.user_id
                                   FROM iam_user_role ur JOIN iam_role r ON r.id = ur.role_id
                                  WHERE r.code LIKE #{like} ESCAPE '!'
                                     OR r.name LIKE #{like} ESCAPE '!'))
            </if>
            </script>
            """)
    long countPage(@Param("tenant") String tenant,
                   @Param("status") String status,
                   @Param("orgPath") String orgPath,
                   @Param("like") String like);

    /**
     * 策略编辑器用的轻量用户清单。
     *
     * <p>只取策略里真正会写的字段：{@code username} 是权限配置引用的值，
     * 显示名与状态用于让人选得明白。**不查组织与角色**——选项列表不需要，
     * 每行都跑一遍子查询只会让输入时的搜索变慢。
     *
     * <p>包含停用用户是刻意的：已保存的策略可能引用他们，界面要能标出
     * "这个人已停用，规则不会生效"，而不是让该用户凭空从选择器里消失。
     */
    @Select("""
            <script>
            SELECT u.username AS username,
                   u.display_name AS displayName,
                   u.status AS status
            FROM iam_user u
            WHERE u.tenant_id = #{tenant}
            <if test="like != null">
                AND (u.username LIKE #{like} ESCAPE '!'
                     OR u.display_name LIKE #{like} ESCAPE '!')
            </if>
            ORDER BY u.username
            LIMIT #{limit}
            </script>
            """)
    List<UserOptionRow> selectOptions(@Param("tenant") String tenant,
                                      @Param("like") String like,
                                      @Param("limit") int limit);

    /**
     * 按用户名批量取（策略保存时 best-effort 校验引用）。
     *
     * <p>不做 LIKE、不加 limit：调用方已经把要校验的 username 收敛成一个集合，
     * 这里只回答"这些账号哪些存在"。
     */
    @Select("""
            <script>
            SELECT u.username AS username,
                   u.display_name AS displayName,
                   u.status AS status
            FROM iam_user u
            WHERE u.tenant_id = #{tenant}
              AND u.username IN
              <foreach collection="usernames" item="name" open="(" separator="," close=")">
                #{name}
              </foreach>
            </script>
            """)
    List<UserOptionRow> selectByUsernames(@Param("tenant") String tenant,
                                          @Param("usernames") java.util.Collection<String> usernames);

    /** 用户选择器的一行。 */
    record UserOptionRow(String username, String displayName, String status) {
    }

    /**
     * 顶部统计。
     *
     * <p>单独一条聚合查询，而不是"把全部用户取回来在内存里数"——
     * 后者正是这次分页要消灭的东西（10 万行 ≈ 1.7 秒 + 20MB）。
     */
    @Select("""
            SELECT COUNT(*) AS total,
                   COALESCE(SUM(u.status = 'ACTIVE'), 0) AS active,
                   COALESCE(SUM(u.status = 'DISABLED'), 0) AS disabled,
                   (SELECT COUNT(DISTINCT uo.org_id)
                      FROM iam_user_org uo JOIN iam_user x ON x.id = uo.user_id
                     WHERE x.tenant_id = #{tenant}) AS orgs
            FROM iam_user u
            WHERE u.tenant_id = #{tenant}
            """)
    UserStatsRow selectStats(@Param("tenant") String tenant);

    /** 用户是否有某个角色（按角色码）。用于"最后一个超管"的判定。 */
    @Select("""
            SELECT COUNT(*)
            FROM iam_user_role ur JOIN iam_role r ON r.id = ur.role_id
            WHERE ur.user_id = #{userId} AND r.code = #{roleCode}
            """)
    long countUserRole(@Param("userId") long userId, @Param("roleCode") String roleCode);

    /**
     * 启用中、且持有某角色的用户数。
     *
     * <p>停用账号前要用它判断"是不是最后一个超管"。以前是取全量用户再在内存里数，
     * 10 万用户下这一步要 1.7 秒；超管只有个位数，一条聚合查询就够了。
     */
    @Select("""
            SELECT COUNT(*)
            FROM iam_user u
            JOIN iam_user_role ur ON ur.user_id = u.id
            JOIN iam_role r ON r.id = ur.role_id
            WHERE u.tenant_id = #{tenant}
              AND u.status = 'ACTIVE'
              AND r.code = #{roleCode}
            """)
    long countActiveWithRole(@Param("tenant") String tenant, @Param("roleCode") String roleCode);

    /** 用户列表顶部的三个数字。 */
    record UserStatsRow(long total, long active, long disabled, long orgs) {
    }
}

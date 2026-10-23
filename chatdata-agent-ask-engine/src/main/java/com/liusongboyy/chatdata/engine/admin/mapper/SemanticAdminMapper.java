package com.liusongboyy.chatdata.engine.admin.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 管理面的写操作。
 *
 * <p>与 {@code SemanticConfigMapper} 分开：那个只读，供问数链路用；
 * 这个只写，供管理面用。**问数链路不该看得见写方法**——把两者混在一个接口里，
 * 迟早会有人在查询路径上顺手调一个 update。
 *
 * <h2>为什么参数逐个 {@code @Param} 传，而不是传一个对象</h2>
 *
 * <p>MyBatis 通过 {@code getX()} / {@code isX()} 反射读取参数属性，而 record 的访问器
 * 是 {@code x()}——读不到。传 record 会得到一个"所有字段都是 null"的 insert，
 * 而且**不报错**（SQL 里全是 null，数据库可能照收）。
 *
 * <p>为此专门造一批可变 POJO 不值当，逐个 {@code @Param} 反而更直白：
 * 调用点一眼能看出插了什么。
 *
 * <h2>不返回自增主键</h2>
 *
 * <p>{@code @Options(useGeneratedKeys)} 需要往参数对象里回填 id，而 record 不可变。
 * 故插入后由调用方重新查列表——管理面是低频操作，多一次查询换掉一批 POJO 是划算的。
 */
@Mapper
public interface SemanticAdminMapper {

    // ── 数据源 ──

    @Insert("""
            INSERT INTO ask_datasource
              (name, db_type, driver_class, url, username, password_enc, enabled, created_by)
            VALUES
              (#{name}, #{dbType}, #{driverClass}, #{url}, #{username}, #{passwordEnc}, #{enabled}, #{operator})
            """)
    int insertDatasource(@Param("name") String name,
                         @Param("dbType") String dbType,
                         @Param("driverClass") String driverClass,
                         @Param("url") String url,
                         @Param("username") String username,
                         @Param("passwordEnc") String passwordEnc,
                         @Param("enabled") boolean enabled,
                         @Param("operator") String operator);

    /** 更新时不改密码——密码有独立的更新入口，避免"改个名字顺手把密码清空"。 */
    @Update("""
            UPDATE ask_datasource
            SET name = #{name}, db_type = #{dbType}, driver_class = #{driverClass},
                url = #{url}, username = #{username}, enabled = #{enabled}, updated_by = #{operator}
            WHERE id = #{id}
            """)
    int updateDatasource(@Param("id") long id,
                         @Param("name") String name,
                         @Param("dbType") String dbType,
                         @Param("driverClass") String driverClass,
                         @Param("url") String url,
                         @Param("username") String username,
                         @Param("enabled") boolean enabled,
                         @Param("operator") String operator);

    /** 单独更新密码。{@code passwordEnc} 为 null 时清空密码（用于改成免密账号）。 */
    @Update("""
            UPDATE ask_datasource
            SET password_enc = #{passwordEnc}, updated_by = #{operator}
            WHERE id = #{id}
            """)
    int updateDatasourcePassword(@Param("id") long id,
                                 @Param("passwordEnc") String passwordEnc,
                                 @Param("operator") String operator);

    @Delete("DELETE FROM ask_datasource WHERE id = #{id}")
    int deleteDatasource(@Param("id") long id);

    // ── 语义域 ──

    @Insert("""
            INSERT INTO ask_domain
              (name, description, datasource_id, status, mode, joins_json, perm_json, sort_no, created_by)
            VALUES
              (#{name}, #{description}, #{datasourceId}, #{status}, #{mode}, #{joinsJson}, #{permJson}, #{sortNo}, #{operator})
            """)
    int insertDomain(@Param("name") String name,
                     @Param("description") String description,
                     @Param("datasourceId") Long datasourceId,
                     @Param("status") String status,
                     @Param("mode") String mode,
                     @Param("joinsJson") String joinsJson,
                     @Param("permJson") String permJson,
                     @Param("sortNo") int sortNo,
                     @Param("operator") String operator);

    @Update("""
            UPDATE ask_domain
            SET name = #{name}, description = #{description}, datasource_id = #{datasourceId},
                status = #{status}, mode = #{mode}, joins_json = #{joinsJson}, perm_json = #{permJson},
                sort_no = #{sortNo}, updated_by = #{operator}
            WHERE id = #{id}
            """)
    int updateDomain(@Param("id") long id,
                     @Param("name") String name,
                     @Param("description") String description,
                     @Param("datasourceId") Long datasourceId,
                     @Param("status") String status,
                     @Param("mode") String mode,
                     @Param("joinsJson") String joinsJson,
                     @Param("permJson") String permJson,
                     @Param("sortNo") int sortNo,
                     @Param("operator") String operator);

    @Delete("DELETE FROM ask_domain WHERE id = #{id}")
    int deleteDomain(@Param("id") long id);

    /**
     * 只更新表关联。
     *
     * <p>单独一个入口而不是复用 {@code updateDomain}：表关联是在「注册表」页面编辑的，
     * 那里没有域的其它字段（描述、授权…），走全量更新会把它们清掉。
     */
    @Update("""
            UPDATE ask_domain
            SET joins_json = #{joinsJson}, updated_by = #{operator}
            WHERE id = #{id}
            """)
    int updateDomainJoins(@Param("id") long id,
                          @Param("joinsJson") String joinsJson,
                          @Param("operator") String operator);

    // ── 域内注册表 ──

    @Insert("""
            INSERT INTO ask_domain_table
              (domain_id, name, label, mode, sql_text, columns_json, enabled, sort_no, created_by)
            VALUES
              (#{domainId}, #{name}, #{label}, #{mode}, #{sqlText}, #{columnsJson}, #{enabled}, #{sortNo}, #{operator})
            """)
    int insertDomainTable(@Param("domainId") long domainId,
                          @Param("name") String name,
                          @Param("label") String label,
                          @Param("mode") String mode,
                          @Param("sqlText") String sqlText,
                          @Param("columnsJson") String columnsJson,
                          @Param("enabled") boolean enabled,
                          @Param("sortNo") int sortNo,
                          @Param("operator") String operator);

    @Update("""
            UPDATE ask_domain_table
            SET name = #{name}, label = #{label}, mode = #{mode}, sql_text = #{sqlText},
                columns_json = #{columnsJson}, enabled = #{enabled}, sort_no = #{sortNo},
                updated_by = #{operator}
            WHERE id = #{id}
            """)
    int updateDomainTable(@Param("id") long id,
                          @Param("name") String name,
                          @Param("label") String label,
                          @Param("mode") String mode,
                          @Param("sqlText") String sqlText,
                          @Param("columnsJson") String columnsJson,
                          @Param("enabled") boolean enabled,
                          @Param("sortNo") int sortNo,
                          @Param("operator") String operator);

    @Delete("DELETE FROM ask_domain_table WHERE id = #{id}")
    int deleteDomainTable(@Param("id") long id);

    /** 删除域时连同它的注册表一起删——留着会变成孤儿行，而外键没建约束。 */
    @Delete("DELETE FROM ask_domain_table WHERE domain_id = #{domainId}")
    int deleteTablesByDomain(@Param("domainId") long domainId);

    // ── 占用检查 ──

    @org.apache.ibatis.annotations.Select(
            "SELECT COUNT(*) FROM ask_domain WHERE datasource_id = #{datasourceId}")
    int countDomainsUsingDatasource(@Param("datasourceId") long datasourceId);

    @org.apache.ibatis.annotations.Select(
            "SELECT COUNT(*) FROM ask_domain_table WHERE domain_id = #{domainId}")
    int countTablesInDomain(@Param("domainId") long domainId);

    // ── 业务术语 ──

    @Insert("""
            INSERT INTO ask_term
              (domain_id, name, synonyms_json, description, apply_kind, apply_time, apply_clause,
               sort_no, created_by)
            VALUES
              (#{domainId}, #{name}, #{synonymsJson}, #{description}, #{applyKind}, #{applyTime},
               #{applyClause}, #{sortNo}, #{operator})
            """)
    int insertTerm(@Param("domainId") long domainId,
                   @Param("name") String name,
                   @Param("synonymsJson") String synonymsJson,
                   @Param("description") String description,
                   @Param("applyKind") String applyKind,
                   @Param("applyTime") String applyTime,
                   @Param("applyClause") String applyClause,
                   @Param("sortNo") int sortNo,
                   @Param("operator") String operator);

    @Update("""
            UPDATE ask_term
            SET name = #{name}, synonyms_json = #{synonymsJson}, description = #{description},
                apply_kind = #{applyKind}, apply_time = #{applyTime}, apply_clause = #{applyClause},
                sort_no = #{sortNo}, updated_by = #{operator}
            WHERE id = #{id}
            """)
    int updateTerm(@Param("id") long id,
                   @Param("name") String name,
                   @Param("synonymsJson") String synonymsJson,
                   @Param("description") String description,
                   @Param("applyKind") String applyKind,
                   @Param("applyTime") String applyTime,
                   @Param("applyClause") String applyClause,
                   @Param("sortNo") int sortNo,
                   @Param("operator") String operator);

    @Delete("DELETE FROM ask_term WHERE id = #{id}")
    int deleteTerm(@Param("id") long id);

    @Delete("DELETE FROM ask_term WHERE domain_id = #{domainId}")
    int deleteTermsByDomain(@Param("domainId") long domainId);

    // ── 查询主题 ──

    @Insert("""
            INSERT INTO ask_topic
              (domain_id, name, description, status, fields_json, sort_no, created_by)
            VALUES
              (#{domainId}, #{name}, #{description}, #{status}, #{fieldsJson}, #{sortNo}, #{operator})
            """)
    int insertTopic(@Param("domainId") long domainId,
                    @Param("name") String name,
                    @Param("description") String description,
                    @Param("status") String status,
                    @Param("fieldsJson") String fieldsJson,
                    @Param("sortNo") int sortNo,
                    @Param("operator") String operator);

    @Update("""
            UPDATE ask_topic
            SET name = #{name}, description = #{description}, status = #{status},
                fields_json = #{fieldsJson}, sort_no = #{sortNo}, updated_by = #{operator}
            WHERE id = #{id}
            """)
    int updateTopic(@Param("id") long id,
                    @Param("name") String name,
                    @Param("description") String description,
                    @Param("status") String status,
                    @Param("fieldsJson") String fieldsJson,
                    @Param("sortNo") int sortNo,
                    @Param("operator") String operator);

    @Delete("DELETE FROM ask_topic WHERE id = #{id}")
    int deleteTopic(@Param("id") long id);

    @Delete("DELETE FROM ask_topic WHERE domain_id = #{domainId}")
    int deleteTopicsByDomain(@Param("domainId") long domainId);

    // ── 助理 ──

    @Insert("""
            INSERT INTO ask_assistant
              (name, avatar, description, welcome, domain_id, domain_ids, show_sql,
               status, questions_json, perm_json, sort_no, created_by)
            VALUES
              (#{name}, #{avatar}, #{description}, #{welcome}, #{domainId}, #{domainIds}, #{showSql},
               #{status}, #{questionsJson}, #{permJson}, #{sortNo}, #{operator})
            """)
    int insertAssistant(@Param("name") String name,
                        @Param("avatar") String avatar,
                        @Param("description") String description,
                        @Param("welcome") String welcome,
                        @Param("domainId") Long domainId,
                        @Param("domainIds") String domainIds,
                        @Param("showSql") boolean showSql,
                        @Param("status") String status,
                        @Param("questionsJson") String questionsJson,
                        @Param("permJson") String permJson,
                        @Param("sortNo") int sortNo,
                        @Param("operator") String operator);

    @Update("""
            UPDATE ask_assistant
            SET name = #{name}, avatar = #{avatar}, description = #{description}, welcome = #{welcome},
                domain_id = #{domainId}, domain_ids = #{domainIds}, show_sql = #{showSql},
                status = #{status}, questions_json = #{questionsJson}, perm_json = #{permJson},
                sort_no = #{sortNo}, updated_by = #{operator}
            WHERE id = #{id}
            """)
    int updateAssistant(@Param("id") long id,
                        @Param("name") String name,
                        @Param("avatar") String avatar,
                        @Param("description") String description,
                        @Param("welcome") String welcome,
                        @Param("domainId") Long domainId,
                        @Param("domainIds") String domainIds,
                        @Param("showSql") boolean showSql,
                        @Param("status") String status,
                        @Param("questionsJson") String questionsJson,
                        @Param("permJson") String permJson,
                        @Param("sortNo") int sortNo,
                        @Param("operator") String operator);

    @Delete("DELETE FROM ask_assistant WHERE id = #{id}")
    int deleteAssistant(@Param("id") long id);

    // ── SQL 范例 ──

    @Select("""
            SELECT id, domain_id AS domainId, question, sql_text AS `sql`, note, keywords, source, enabled
            FROM ask_sql_example
            WHERE domain_id = #{domainId}
            ORDER BY sort_no, id
            """)
    java.util.List<com.liusongboyy.chatdata.engine.raw.SqlExample> selectAllSqlExamples(
            @Param("domainId") long domainId);

    @Select("""
            SELECT id, domain_id AS domainId, question, sql_text AS `sql`, note, keywords, source, enabled
            FROM ask_sql_example WHERE id = #{id}
            """)
    com.liusongboyy.chatdata.engine.raw.SqlExample selectSqlExampleById(@Param("id") long id);

    /**
     * 按「域 + 问法」取范例。
     *
     * <p>反馈审核要用它判断"这个问法是不是已经有范例"：有手工范例时不能覆盖，
     * 有反馈范例时可以更新。ask_sql_example 上的唯一键保证最多一条。
     */
    @Select("""
            SELECT id, domain_id AS domainId, question, sql_text AS `sql`, note, keywords, source, enabled
            FROM ask_sql_example
            WHERE domain_id = #{domainId} AND question = #{question}
            """)
    com.liusongboyy.chatdata.engine.raw.SqlExample selectSqlExampleByQuestion(
            @Param("domainId") long domainId, @Param("question") String question);

    @Insert("""
            INSERT INTO ask_sql_example
              (domain_id, question, sql_text, note, keywords, source, enabled, sort_no, created_by)
            VALUES
              (#{domainId}, #{question}, #{sql}, #{note}, #{keywords}, #{source}, #{enabled}, #{sortNo}, #{operator})
            """)
    int insertSqlExample(@Param("domainId") long domainId,
                         @Param("question") String question,
                         @Param("sql") String sql,
                         @Param("note") String note,
                         @Param("keywords") String keywords,
                         @Param("source") String source,
                         @Param("enabled") boolean enabled,
                         @Param("sortNo") int sortNo,
                         @Param("operator") String operator);

    @Update("""
            UPDATE ask_sql_example
            SET question = #{question}, sql_text = #{sql}, note = #{note}, keywords = #{keywords},
                enabled = #{enabled}, sort_no = #{sortNo}, updated_by = #{operator}
            WHERE id = #{id}
            """)
    int updateSqlExample(@Param("id") long id,
                         @Param("question") String question,
                         @Param("sql") String sql,
                         @Param("note") String note,
                         @Param("keywords") String keywords,
                         @Param("enabled") boolean enabled,
                         @Param("sortNo") int sortNo,
                         @Param("operator") String operator);

    @Delete("DELETE FROM ask_sql_example WHERE id = #{id}")
    int deleteSqlExample(@Param("id") long id);
}

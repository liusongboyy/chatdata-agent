package com.liusongboyy.chatdata.engine.semantic.mapper;

import com.liusongboyy.chatdata.engine.datasource.DatasourceDef;
import com.liusongboyy.chatdata.engine.semantic.DomainRow;
import com.liusongboyy.chatdata.engine.semantic.AssistantRow;
import com.liusongboyy.chatdata.engine.semantic.DomainTableRow;
import com.liusongboyy.chatdata.engine.semantic.TermRow;
import com.liusongboyy.chatdata.engine.semantic.TopicRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 语义配置的读取。
 *
 * <p>Mapper 单独放 {@code mapper} 子包，与本项目其余模块一致
 * （见 {@code AccessGrantMapper} 的说明）。
 *
 * <p><strong>只提供读。</strong>管理面的写入等管理功能落地时再加——
 * 现在加一堆用不到的 insert/update，既没人测也容易写错。
 */
@Mapper
public interface SemanticConfigMapper {

    // ── 数据源 ──

    /**
     * 全部数据源。
     *
     * <p><strong>刻意不查 {@code password_enc}。</strong>
     * 密码由 {@link #selectDatasourcePassword(long)} 单独取——
     * 让它出现在一个会被列表展示、缓存、写日志的对象里，是长期风险。
     */
    @Select("""
            SELECT id, name, db_type AS dbType, driver_class AS driverClass,
                   url, username, enabled
            FROM ask_datasource
            WHERE enabled = 1
            ORDER BY id
            """)
    List<DatasourceDef> selectEnabledDatasources();

    /**
     * 全部数据源，**含未启用的**。供管理面用。
     *
     * <p>问数链路只该看到启用中的（{@link #selectEnabledDatasources}），
     * 而管理面必须能看到停用的——否则管理员停用一个源之后就再也找不到它了。
     */
    @Select("""
            SELECT id, name, db_type AS dbType, driver_class AS driverClass,
                   url, username, enabled
            FROM ask_datasource
            ORDER BY id
            """)
    List<DatasourceDef> selectAllDatasources();

    /** 单个数据源的加密密码。取不到返回 null。 */
    @Select("""
            SELECT password_enc
            FROM ask_datasource
            WHERE id = #{id}
            """)
    String selectDatasourcePassword(@Param("id") long id);

    // ── 语义域 ──

    @Select("""
            SELECT id, name, datasource_id AS datasourceId, status, mode,
                   joins_json AS joinsJson, perm_json AS permJson
            FROM ask_domain
            WHERE status = 'enabled'
            ORDER BY sort_no, id
            """)
    List<DomainRow> selectEnabledDomains();

    @Select("""
            SELECT id, name, datasource_id AS datasourceId, status, mode,
                   joins_json AS joinsJson, perm_json AS permJson
            FROM ask_domain
            WHERE id = #{id}
            """)
    DomainRow selectDomainById(@Param("id") long id);

    /** 全部语义域，**含停用的**。供管理面用——停用的域也要能找回来改。 */
    @Select("""
            SELECT id, name, datasource_id AS datasourceId, status, mode,
                   joins_json AS joinsJson, perm_json AS permJson
            FROM ask_domain
            ORDER BY sort_no, id
            """)
    List<DomainRow> selectAllDomains();

    // ── SQL 范例（直连模式的 few-shot）──

    /**
     * 某个域里**启用中**的范例。
     *
     * <p>一次全取而不是分页：范例库是人工维护的，量级在几十到几百条，
     * 检索在内存里做（见 {@code SqlExampleSelector}）。分页反而会让"挑最像的"
     * 变成"在这一页里挑最像的"——那是错的。
     */
    @Select("""
            SELECT id, domain_id AS domainId, question, sql_text AS `sql`, note, keywords, source, enabled
            FROM ask_sql_example
            WHERE domain_id = #{domainId} AND enabled = 1
            ORDER BY sort_no, id
            """)
    List<com.liusongboyy.chatdata.engine.raw.SqlExample> selectSqlExamplesByDomain(
            @Param("domainId") long domainId);

    // ── 助理 ──

    @Select("""
            SELECT id, name, avatar, description, welcome, domain_id AS domainId,
                   domain_ids AS domainIds, show_sql AS showSql, status,
                   questions_json AS questionsJson, perm_json AS permJson
            FROM ask_assistant
            WHERE status = 'online'
            ORDER BY sort_no, id
            """)
    List<AssistantRow> selectOnlineAssistants();

    @Select("""
            SELECT id, name, avatar, description, welcome, domain_id AS domainId,
                   domain_ids AS domainIds, show_sql AS showSql, status,
                   questions_json AS questionsJson, perm_json AS permJson
            FROM ask_assistant
            ORDER BY sort_no, id
            """)
    List<AssistantRow> selectAllAssistants();

    @Select("""
            SELECT id, name, avatar, description, welcome, domain_id AS domainId,
                   domain_ids AS domainIds, show_sql AS showSql, status,
                   questions_json AS questionsJson, perm_json AS permJson
            FROM ask_assistant
            WHERE id = #{id}
            """)
    AssistantRow selectAssistantById(@Param("id") long id);

    // ── 域内注册表 ──

    @Select("""
            SELECT id, domain_id AS domainId, name, label, mode, sql_text AS sqlText,
                   columns_json AS columnsJson, enabled
            FROM ask_domain_table
            WHERE domain_id = #{domainId}
            ORDER BY sort_no, id
            """)
    List<DomainTableRow> selectTablesByDomain(@Param("domainId") long domainId);

    // ── 业务术语与查询主题 ──

    @Select("""
            SELECT id, domain_id AS domainId, name, synonyms_json AS synonymsJson,
                   description, apply_kind AS applyKind
            FROM ask_term
            WHERE domain_id = #{domainId}
            ORDER BY sort_no, id
            """)
    List<TermRow> selectTermsByDomain(@Param("domainId") long domainId);

    @Select("""
            SELECT id, domain_id AS domainId, name, description, status,
                   fields_json AS fieldsJson
            FROM ask_topic
            WHERE domain_id = #{domainId}
            ORDER BY sort_no, id
            """)
    List<TopicRow> selectTopicsByDomain(@Param("domainId") long domainId);

    /** 一次取多个域的表，避免按域逐个查产生 N+1。 */
    @Select("""
            <script>
            SELECT id, domain_id AS domainId, name, label, mode, sql_text AS sqlText,
                   columns_json AS columnsJson, enabled
            FROM ask_domain_table
            WHERE domain_id IN
            <foreach item="d" collection="domainIds" open="(" separator="," close=")">#{d}</foreach>
            ORDER BY domain_id, sort_no, id
            </script>
            """)
    List<DomainTableRow> selectTablesByDomains(@Param("domainIds") List<Long> domainIds);
}

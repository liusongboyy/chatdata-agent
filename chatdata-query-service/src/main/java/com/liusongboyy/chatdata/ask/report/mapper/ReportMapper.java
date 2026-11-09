package com.liusongboyy.chatdata.ask.report.mapper;

import com.liusongboyy.chatdata.ask.report.ReportRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 报告落库。
 *
 * <p>放在 {@code report/mapper} 子包而不是与 Service 同包：
 * {@code @MapperScan} 扫整个包，同包里的 Service 接口也会被注册成 mapper，
 * 报错是 "required a single bean, but 2 were found"——指向 bean 冲突，
 * 不会提示"某接口被误当作 mapper"。这个坑 {@code ConversationMapper} 记过。
 */
@Mapper
public interface ReportMapper {

    @Insert("""
            INSERT INTO ask_report
              (id, conversation_id, username, title, intent, body_json,
               source_message_ids, model_key, generated_at)
            VALUES
              (#{id}, #{conversationId}, #{username}, #{title}, #{intent}, #{bodyJson},
               #{sourceMessageIds}, #{modelKey}, #{generatedAt})
            """)
    int insert(ReportRow row);

    /** 按 id 读。**只读未删除的**——软删除后不该还能打开。 */
    @Select("""
            SELECT id, conversation_id AS conversationId, username, title, intent,
                   body_json AS bodyJson, source_message_ids AS sourceMessageIds,
                   model_key AS modelKey, generated_at AS generatedAt
            FROM ask_report
            WHERE id = #{id} AND deleted = 0
            """)
    ReportRow selectById(@Param("id") String id);

    /** 我的报告列表。按生成时间倒序。 */
    @Select("""
            SELECT id, conversation_id AS conversationId, username, title, intent,
                   body_json AS bodyJson, source_message_ids AS sourceMessageIds,
                   model_key AS modelKey, generated_at AS generatedAt
            FROM ask_report
            WHERE username = #{username} AND deleted = 0
            ORDER BY generated_at DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<ReportRow> selectByUser(@Param("username") String username,
                                 @Param("offset") int offset,
                                 @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM ask_report WHERE username = #{username} AND deleted = 0")
    int countByUser(@Param("username") String username);

    /** 软删除。**只作用于自己的报告**——where 里带 username，防止越权删别人的。 */
    @Update("""
            UPDATE ask_report SET deleted = 1
            WHERE id = #{id} AND username = #{username}
            """)
    int softDelete(@Param("id") String id, @Param("username") String username);
}

package com.liusongboyy.chatdata.ask.conversation.mapper;

import com.liusongboyy.chatdata.ask.conversation.ConversationRow;
import com.liusongboyy.chatdata.ask.conversation.MessageRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * {@code ask_conversation} / {@code ask_message} 的读写。
 *
 * <p>所有列表查询都带 {@code username} 条件——归属校验不能只靠调用方记得做。
 * 详情与删除同样按 {@code (id, username)} 命中，查不到就是 404，不区分
 * "不存在"与"不是你的"。
 */
@Mapper
public interface ConversationMapper {

    @Insert("""
            INSERT INTO ask_conversation
              (id, username, assistant_id, title, preview, message_count)
            VALUES
              (#{id}, #{username}, #{assistantId}, #{title}, NULL, 0)
            """)
    int insertConversation(Map<String, Object> params);

    /** 只取归属与删除位。存在性/越权判断用它，不把会话内容带出来。 */
    @Select("SELECT username, deleted FROM ask_conversation WHERE id = #{id}")
    Map<String, Object> selectOwner(@Param("id") String id);

    @Select("""
            SELECT id, username, assistant_id AS assistantId, title, preview,
                   message_count AS messageCount, created_at AS createdAt,
                   updated_at AS updatedAt
            FROM ask_conversation
            WHERE id = #{id}
            """)
    ConversationRow selectById(@Param("id") String id);

    @Select("""
            SELECT message_count
            FROM ask_conversation
            WHERE id = #{id} AND deleted = 0
            FOR UPDATE
            """)
    Integer lockMessageCount(@Param("id") String id);

    @Insert("""
            INSERT INTO ask_message
              (conversation_id, seq, role, kind, content, result_json,
               status, failure_code, failure_message)
            VALUES
              (#{conversationId}, #{seq}, #{role}, #{kind}, #{content}, #{resultJson},
               #{status}, #{failureCode}, #{failureMessage})
            """)
    int insertMessage(Map<String, Object> params);

    @Update("""
            UPDATE ask_conversation
            SET message_count = #{messageCount}, preview = #{preview}, updated_at = NOW()
            WHERE id = #{id}
            """)
    int touch(@Param("id") String id,
              @Param("messageCount") int messageCount,
              @Param("preview") String preview);

    @Select("""
            <script>
            SELECT id, username, assistant_id AS assistantId, title, preview,
                   message_count AS messageCount, created_at AS createdAt,
                   updated_at AS updatedAt
            FROM ask_conversation c
            WHERE c.username = #{username} AND c.deleted = 0
            <if test="assistantId != null and assistantId != ''">
              AND c.assistant_id = #{assistantId}
            </if>
            <if test="pattern != null">
              AND (
                c.title LIKE #{pattern} ESCAPE '!'
                OR EXISTS (
                  SELECT 1 FROM ask_message m
                  WHERE m.conversation_id = c.id AND m.content LIKE #{pattern} ESCAPE '!'
                )
              )
            </if>
            ORDER BY c.updated_at DESC, c.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ConversationRow> selectPage(@Param("username") String username,
                                     @Param("assistantId") String assistantId,
                                     @Param("pattern") String pattern,
                                     @Param("limit") int limit,
                                     @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM ask_conversation c
            WHERE c.username = #{username} AND c.deleted = 0
            <if test="assistantId != null and assistantId != ''">
              AND c.assistant_id = #{assistantId}
            </if>
            <if test="pattern != null">
              AND (
                c.title LIKE #{pattern} ESCAPE '!'
                OR EXISTS (
                  SELECT 1 FROM ask_message m
                  WHERE m.conversation_id = c.id AND m.content LIKE #{pattern} ESCAPE '!'
                )
              )
            </if>
            </script>
            """)
    long count(@Param("username") String username,
               @Param("assistantId") String assistantId,
               @Param("pattern") String pattern);

    @Select("""
            SELECT id, conversation_id AS conversationId, seq, role, kind, content,
                   result_json AS resultJson, status, failure_code AS failureCode,
                   failure_message AS failureMessage, created_at AS createdAt
            FROM ask_message
            WHERE conversation_id = #{conversationId}
            ORDER BY seq ASC, id ASC
            """)
    List<MessageRow> selectMessages(@Param("conversationId") String conversationId);

    @Update("""
            UPDATE ask_conversation
            SET deleted = 1, updated_at = NOW()
            WHERE id = #{id} AND username = #{username} AND deleted = 0
            """)
    int softDelete(@Param("id") String id, @Param("username") String username);
}

package com.liusongboyy.chatdata.engine.feedback.mapper;

import com.liusongboyy.chatdata.engine.feedback.FeedbackRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * {@code ask_feedback} 的读写。
 *
 * <h2>为什么 insert 用 Map 参数</h2>
 *
 * <p>要回填自增主键。MyBatis 的 {@code keyProperty} 只能写入**可变对象**的属性，
 * 而本项目的入参习惯用 record（不可变）。Map 是可变的，且不需要为这一处
 * 造一个只有 getter/setter 的参数类。
 *
 * <p>审核状态用带条件的 update（{@code status='PENDING'}）做**乐观闸门**：
 * 两个人同时点"通过"时，第二次影响 0 行，调用方据此报"已被处理"，
 * 而不是静默覆盖第一个人的审核结果。
 */
@Mapper
public interface FeedbackMapper {

    @Insert("""
            INSERT INTO ask_feedback
              (domain_id, assistant_id, username, question, feedback_type,
               generated_sql, corrected_sql, note, status)
            VALUES
              (#{domainId}, #{assistantId}, #{username}, #{question}, #{feedbackType},
               #{generatedSql}, #{correctedSql}, #{note}, 'PENDING')
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertFeedback(Map<String, Object> params);

    @Select("""
            SELECT id, domain_id AS domainId, assistant_id AS assistantId, username, question,
                   feedback_type AS feedbackType, generated_sql AS generatedSql,
                   corrected_sql AS correctedSql, note, status,
                   example_id AS exampleId, reviewed_by AS reviewedBy,
                   reviewed_at AS reviewedAt, created_at AS createdAt
            FROM ask_feedback
            WHERE id = #{id}
            """)
    FeedbackRow selectById(@Param("id") long id);

    @Select("""
            SELECT id, domain_id AS domainId, assistant_id AS assistantId, username, question,
                   feedback_type AS feedbackType, generated_sql AS generatedSql,
                   corrected_sql AS correctedSql, note, status,
                   example_id AS exampleId, reviewed_by AS reviewedBy,
                   reviewed_at AS reviewedAt, created_at AS createdAt
            FROM ask_feedback
            WHERE status = #{status}
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<FeedbackRow> selectByStatus(@Param("status") String status, @Param("limit") int limit);

    @Select("""
            SELECT id, domain_id AS domainId, assistant_id AS assistantId, username, question,
                   feedback_type AS feedbackType, generated_sql AS generatedSql,
                   corrected_sql AS correctedSql, note, status,
                   example_id AS exampleId, reviewed_by AS reviewedBy,
                   reviewed_at AS reviewedAt, created_at AS createdAt
            FROM ask_feedback
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<FeedbackRow> selectRecent(@Param("limit") int limit);

    /**
     * 审核。只有 PENDING 能被改动。
     *
     * @return 受影响行数；0 表示这条已被别人处理过
     */
    @Update("""
            UPDATE ask_feedback
            SET status = #{status}, example_id = #{exampleId},
                reviewed_by = #{reviewer}, reviewed_at = NOW()
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int markReviewed(@Param("id") long id,
                     @Param("status") String status,
                     @Param("exampleId") Long exampleId,
                     @Param("reviewer") String reviewer);
}

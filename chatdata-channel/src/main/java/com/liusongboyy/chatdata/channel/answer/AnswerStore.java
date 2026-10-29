package com.liusongboyy.chatdata.channel.answer;

import com.liusongboyy.chatdata.channel.ask.AskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 已发送的答案。
 *
 * <h2>为什么落库而不是塞进卡片</h2>
 *
 * <p>ask 最多返回 1000 行。塞进卡片 JSON 会有几百 KB——飞书对卡片体积有限制，
 * 而回传的 {@code value} 更不可能放下。所以卡片里只放 {@code answerId}，
 * 翻页与反馈都靠它回来查。
 *
 * <h2>它同时是反馈的上下文来源</h2>
 *
 * <p>飞书卡片回调只带 {@code open_message_id} 与按钮的 value，<b>不带原始问题</b>。
 * 而 ask 的反馈接口需要 question / assistantId / domainId / generatedSql——
 * 这些都在本表里。
 */
@Component
public class AnswerStore {

    private static final Logger log = LoggerFactory.getLogger(AnswerStore.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;

    public AnswerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 存一条答案。
     *
     * @return 自增主键；失败时记录日志并返回空（分页与反馈都依赖它，
     *         但**不能因此让结果发不出去**——用户先看到数据最重要）
     */
    public Optional<Long> save(String channel, String chatKey, String username,
                               String assistantId, String domainId, String question,
                               AskResult result) {
        String columns = JSON.writeValueAsString(result.columns());
        String rows = JSON.writeValueAsString(result.rows());
        String masked = JSON.writeValueAsString(result.maskedColumns());

        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(conn -> {
                PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO channel_answer
                          (channel, chat_key, username, assistant_id, domain_id, question,
                           columns_json, rows_json, row_count, generated_sql, masked_columns)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, channel);
                ps.setString(2, chatKey);
                ps.setString(3, username);
                ps.setString(4, assistantId);
                ps.setString(5, domainId);
                ps.setString(6, truncate(question, 2000));
                ps.setString(7, columns);
                ps.setString(8, rows);
                ps.setInt(9, result.rowCount());
                ps.setString(10, result.sql());
                ps.setString(11, masked);
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            return key == null ? Optional.empty() : Optional.of(key.longValue());
        } catch (RuntimeException e) {
            // 存不下不影响"把结果发给用户"——翻页会不可用，但主流程不能断
            log.error("保存渠道答案失败：chatKey={} question={}", chatKey,
                    truncate(question, 40), e);
            return Optional.empty();
        }
    }

    /** 按 id 取回。翻页与反馈都用它。 */
    public Optional<StoredAnswer> find(long id) {
        List<StoredAnswer> found = jdbc.query("""
                SELECT id, channel, chat_key, username, assistant_id, domain_id, question,
                       columns_json, rows_json, row_count, generated_sql, masked_columns
                FROM channel_answer WHERE id = ?
                """, (rs, i) -> new StoredAnswer(
                rs.getLong("id"),
                rs.getString("channel"),
                rs.getString("chat_key"),
                rs.getString("username"),
                rs.getString("assistant_id"),
                rs.getString("domain_id"),
                rs.getString("question"),
                parseColumns(rs.getString("columns_json")),
                parseRows(rs.getString("rows_json")),
                rs.getInt("row_count"),
                rs.getString("generated_sql"),
                parseColumns(rs.getString("masked_columns"))), id);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseColumns(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return (List<String>) JSON.readValue(json, List.class);
        } catch (RuntimeException e) {
            // 数据坏了等同于没有——不要因为一个坏字段让整条链路抛异常
            log.warn("解析列名失败：{}", json);
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<List<String>> parseRows(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<List<Object>> raw = (List<List<Object>>) JSON.readValue(json, List.class);
            List<List<String>> out = new ArrayList<>(raw.size());
            for (List<Object> row : raw) {
                List<String> cells = new ArrayList<>(row.size());
                for (Object v : row) {
                    cells.add(v == null ? "" : String.valueOf(v));
                }
                out.add(List.copyOf(cells));
            }
            return List.copyOf(out);
        } catch (RuntimeException e) {
            log.warn("解析结果行失败");
            return List.of();
        }
    }

    private static String truncate(String v, int max) {
        if (v == null) {
            return "";
        }
        return v.length() <= max ? v : v.substring(0, max);
    }

    /**
     * 取回的一条答案。
     *
     * @param rowCount 总行数。可能大于 {@code rows.size()}（ask 自己的 1000 行上限之外
     *                 还有真实总数）
     */
    public record StoredAnswer(
            long id,
            String channel,
            String chatKey,
            String username,
            String assistantId,
            String domainId,
            String question,
            List<String> columns,
            List<List<String>> rows,
            int rowCount,
            String sql,
            List<String> maskedColumns
    ) {
    }
}

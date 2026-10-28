package com.liusongboyy.chatdata.engine.admin;

import com.liusongboyy.chatdata.engine.admin.mapper.SemanticAdminMapper;
import com.liusongboyy.chatdata.engine.raw.SqlExample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * SQL 范例库的维护。
 *
 * <h2>为什么它值得一个独立的服务</h2>
 *
 * <p>范例是**知识**，不是配置：一条写错的范例会让所有相似问题都照着错的写，
 * 而且不会有任何报错。所以这里做两件别处不做的事：
 *
 * <ol>
 *   <li>保存时校验 SQL 为只读（见 {@link SqlExampleInput#validated}）</li>
 *   <li>**不提供自动收录入口**——没有"把审计里成功的都收进来"这种接口。
 *       执行成功 ≠ 语义正确（「上个月」被丢成全量照样成功），
 *       自动收录等于教模型重复那个错误。见 {@code sql/14-ask-sql-example.sql} 的说明。
 *       用户反馈也一样：只有管理员审核通过后才由 {@link #createFromFeedback} 写入</li>
 * </ol>
 */
public class AdminSqlExampleService {

    private static final Logger log = LoggerFactory.getLogger(AdminSqlExampleService.class);

    /**
     * 是不是"同域内问法重复"。
     *
     * <p>两种异常形态都要认：走 mybatis-spring 时是 Spring 的
     * {@code DuplicateKeyException}，直接拿裸 MyBatis 会话（如真库测试）时是
     * 底层 {@code SQLIntegrityConstraintViolationException}（MySQL 错误码 1062）。
     * 只认前者的话，后者会变成一句"操作失败，请稍后重试"——用户不知道是重名。
     */
    private static boolean isDuplicateQuestion(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.springframework.dao.DuplicateKeyException) {
                return true;
            }
            if (t instanceof java.sql.SQLIntegrityConstraintViolationException sql
                    && sql.getErrorCode() == 1062) {
                return true;
            }
        }
        return false;
    }

    private final SemanticAdminMapper mapper;

    public AdminSqlExampleService(SemanticAdminMapper mapper) {
        this.mapper = mapper;
    }

    public List<SqlExample> list(long domainId) {
        return mapper.selectAllSqlExamples(domainId);
    }

    public long create(long domainId, SqlExampleInput input, String operator) {
        SqlExampleInput v = input.validated();
        try {
            mapper.insertSqlExample(domainId, v.question(), v.sql(), v.note(), v.keywords(),
                    "MANUAL", Boolean.TRUE.equals(v.enabled()), v.sortNo(), operator);
        } catch (RuntimeException e) {
            if (isDuplicateQuestion(e)) {
                throw new AdminException(
                        "这个问法已经有范例了：「" + v.question() + "」。"
                                + "请编辑或删除原来那条——两条同问法的范例对检索没有意义，"
                                + "而且会让新增接口返回错的主键");
            }
            throw e;
        }
        log.info("SQL 范例已新增：domain={} by={}", domainId, operator);
        // 不回填自增主键（与其它 insert 一致），按问题查回来
        return list(domainId).stream()
                .filter(e -> e.question().equals(v.question()))
                .map(SqlExample::id)
                .findFirst()
                .orElseThrow(() -> new AdminException("新增范例后未能读回，请刷新列表确认"));
    }

    /**
     * 把一条**已审核通过**的用户反馈写成范例（{@code source=FEEDBACK}）。
     *
     * <h2>它和 {@link #create} 的差别</h2>
     *
     * <p>{@code create} 是管理员手写，遇到同问法直接报错；这里是"用户改的 SQL 被管理员确认"，
     * 允许**覆盖之前同样是反馈来源的那条**——同一条答案被连续反馈两次时，
     * 后一次通常是对前一次的修正。但如果已有手工范例，仍然拒绝：
     * 人工确认过的口径不该被一条用户反馈静默替换。
     *
     * <p>只由 {@code FeedbackService} 在审核通过后调用。**不要**给它接"自动收录"入口。
     */
    public long createFromFeedback(long domainId, SqlExampleInput input, String reviewer) {
        SqlExampleInput v = input.validated();
        SqlExample existing = mapper.selectSqlExampleByQuestion(domainId, v.question());

        if (existing != null) {
            if (!"FEEDBACK".equalsIgnoreCase(existing.source())) {
                throw new AdminException(
                        "这个问法已有手工范例（id=" + existing.id() + "）。"
                                + "请直接在 SQL 范例里编辑，不要用反馈覆盖人工确认过的口径");
            }
            mapper.updateSqlExample(existing.id(), v.question(), v.sql(), v.note(), v.keywords(),
                    true, v.sortNo(), reviewer);
            log.info("反馈范例已更新：domain={} id={} by={}", domainId, existing.id(), reviewer);
            return existing.id();
        }

        try {
            mapper.insertSqlExample(domainId, v.question(), v.sql(), v.note(), v.keywords(),
                    "FEEDBACK", true, v.sortNo(), reviewer);
        } catch (RuntimeException e) {
            // 并发审核同一问法：唯一键挡下了第二条。读回已有的那条即可
            if (isDuplicateQuestion(e)) {
                SqlExample raced = mapper.selectSqlExampleByQuestion(domainId, v.question());
                if (raced != null && "FEEDBACK".equalsIgnoreCase(raced.source())) {
                    return raced.id();
                }
            }
            throw e;
        }

        log.info("反馈范例已新增：domain={} by={}", domainId, reviewer);
        return mapper.selectSqlExampleByQuestion(domainId, v.question()).id();
    }

    public void update(long id, SqlExampleInput input, String operator) {
        if (mapper.selectSqlExampleById(id) == null) {
            throw new AdminException("范例不存在：id=" + id);
        }
        SqlExampleInput v = input.validated();
        try {
            mapper.updateSqlExample(id, v.question(), v.sql(), v.note(), v.keywords(),
                    Boolean.TRUE.equals(v.enabled()), v.sortNo(), operator);
        } catch (RuntimeException e) {
            if (isDuplicateQuestion(e)) {
                throw new AdminException(
                        "这个问法已经被别的范例占用了：「" + v.question() + "」");
            }
            throw e;
        }
        log.info("SQL 范例已更新：id={} by={}", id, operator);
    }

    public void delete(long id, String operator) {
        if (mapper.selectSqlExampleById(id) == null) {
            throw new AdminException("范例不存在：id=" + id);
        }
        mapper.deleteSqlExample(id);
        log.info("SQL 范例已删除：id={} by={}", id, operator);
    }
}

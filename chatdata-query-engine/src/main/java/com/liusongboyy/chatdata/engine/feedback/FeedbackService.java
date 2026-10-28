package com.liusongboyy.chatdata.engine.feedback;

import com.liusongboyy.chatdata.engine.admin.AdminSqlExampleService;
import com.liusongboyy.chatdata.engine.admin.SqlExampleInput;
import com.liusongboyy.chatdata.engine.datasource.SqlTools;
import com.liusongboyy.chatdata.engine.feedback.mapper.FeedbackMapper;
import com.liusongboyy.chatdata.engine.semantic.DomainTableRow;
import com.liusongboyy.chatdata.engine.semantic.mapper.SemanticConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 反馈闭环：用户提交 → 管理员审核 → 沉淀为 SQL 范例。
 *
 * <h2>为什么闭环的另一端必须是人</h2>
 *
 * <p>用户改的 SQL 也可能改错——漏了条件但照样能执行、把口径理解反了但数字看着合理。
 * 自动收进范例库等于**把一个人的误解变成所有相似问题的模板**。
 * 所以这里的 {@link #submit} 只写 {@code PENDING}，真正进入检索库的唯一路径是
 * {@link #approve}，而它由管理台角色触发。
 *
 * <h2>审核时做两件提交时做过的事，再加一件</h2>
 *
 * <ol>
 *   <li>只读校验（与提交时同源）——防止库里的记录被直接改坏</li>
 *   <li>表白名单校验——范例会被注入检索，引用了域外表等于把别的表暴露给模型</li>
 *   <li>同问法冲突处理——手工范例不能被静默覆盖（见 {@code createFromFeedback}）</li>
 * </ol>
 */
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    /** 列表默认条数。管理台一次看太多也没有意义。 */
    private static final int DEFAULT_LIMIT = 100;
    /** 单次最多返回条数，避免一个参数把库拖垮。 */
    private static final int MAX_LIMIT = 500;

    private final FeedbackMapper mapper;
    private final SemanticConfigMapper configMapper;
    private final AdminSqlExampleService examples;

    public FeedbackService(FeedbackMapper mapper,
                           SemanticConfigMapper configMapper,
                           AdminSqlExampleService examples) {
        this.mapper = mapper;
        this.configMapper = configMapper;
        this.examples = examples;
    }

    /**
     * 提交反馈。**只落 PENDING，不碰范例库。**
     *
     * @return 反馈 id
     */
    public long submit(FeedbackInput input, String username) {
        FeedbackInput v = input.validated();
        if (username == null || username.isBlank()) {
            throw new FeedbackException("缺少提交人身份");
        }
        if (configMapper.selectDomainById(v.domainIdAsLong()) == null) {
            throw new FeedbackException("数据域不存在：id=" + v.domainId());
        }

        Map<String, Object> params = new HashMap<>();
        params.put("domainId", v.domainIdAsLong());
        params.put("assistantId", v.assistantId());
        params.put("username", username);
        params.put("question", v.question());
        params.put("feedbackType", v.type().name());
        params.put("generatedSql", v.generatedSql());
        params.put("correctedSql", v.correctedSql());
        params.put("note", v.note());

        mapper.insertFeedback(params);
        Object id = params.get("id");
        if (!(id instanceof Number n)) {
            // 自增主键没回填说明 mapper 配置坏了；这条记录已经插入，不能假装失败让用户重提
            throw new IllegalStateException("反馈已写入但未回填主键，请刷新管理台确认");
        }
        log.info("收到反馈：id={} domain={} type={} user={}",
                n.longValue(), v.domainId(), v.type(), username);
        return n.longValue();
    }

    /**
     * 管理台列表。{@code status} 为空表示全部（按时间倒序）。
     */
    public List<FeedbackRow> list(String status, int limit) {
        int n = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        if (status == null || status.isBlank()) {
            return mapper.selectRecent(n);
        }
        return mapper.selectByStatus(FeedbackStatus.parse(status).name(), n);
    }

    /**
     * 通过。修正 SQL 的反馈会写入/更新范例库。
     *
     * @return 写入或复用的范例 id；非修正类型返回 null
     */
    @Transactional(rollbackFor = Exception.class)
    public Long approve(long id, String reviewer) {
        if (reviewer == null || reviewer.isBlank()) {
            throw new FeedbackException("缺少审核人身份");
        }
        FeedbackRow row = requirePending(id);

        Long exampleId = null;
        if (FeedbackType.SQL_CORRECTION.name().equals(row.feedbackType())) {
            exampleId = writeExample(row, reviewer);
        }

        // 带条件的 update：并发审核时第二次影响 0 行，不能静默覆盖
        if (mapper.markReviewed(id, FeedbackStatus.APPROVED.name(), exampleId, reviewer) == 0) {
            throw new FeedbackException("这条反馈已被他人处理，请刷新后重试");
        }
        log.info("反馈审核通过：id={} example={} by={}", id, exampleId, reviewer);
        return exampleId;
    }

    /** 拒绝。不写范例库。 */
    public void reject(long id, String reviewer) {
        if (reviewer == null || reviewer.isBlank()) {
            throw new FeedbackException("缺少审核人身份");
        }
        requirePending(id);
        if (mapper.markReviewed(id, FeedbackStatus.REJECTED.name(), null, reviewer) == 0) {
            throw new FeedbackException("这条反馈已被他人处理，请刷新后重试");
        }
        log.info("反馈已拒绝：id={} by={}", id, reviewer);
    }

    private FeedbackRow requirePending(long id) {
        FeedbackRow row = mapper.selectById(id);
        if (row == null) {
            throw new FeedbackException("反馈不存在：id=" + id);
        }
        if (!FeedbackStatus.PENDING.name().equals(row.status())) {
            throw new FeedbackException("这条反馈已处理过（当前状态：" + row.status() + "）");
        }
        return row;
    }

    /**
     * 把反馈里的修正 SQL 写成范例。
     *
     * <p>**审核时重新校验**，不信任提交时的结果：提交接口与审核接口之间，
     * 数据库里的记录可能被别的方式改过。
     */
    private Long writeExample(FeedbackRow row, String reviewer) {
        String sql = row.correctedSql();
        if (sql == null || sql.isBlank()) {
            throw new FeedbackException("这条反馈没有修正 SQL，不能写入范例");
        }
        String unsafe = SqlTools.rejectUnsafe(sql);
        if (unsafe != null) {
            throw new FeedbackException("修正的 SQL 不合规：" + unsafe);
        }

        // 域内启用的表就是该域的授权边界。为空时不额外限制表——
        // 建模域可能没有登记表结构，但 SQL 仍然只读
        Set<String> tables = configMapper.selectTablesByDomain(row.domainId()).stream()
                .filter(DomainTableRow::enabled)
                .map(DomainTableRow::name)
                .filter(n -> n != null && !n.isBlank())
                .map(FeedbackService::normalizeTable)
                .collect(Collectors.toSet());
        if (!tables.isEmpty()) {
            String outside = SqlTools.rejectOutsideAllowlist(sql, tables);
            if (outside != null) {
                throw new FeedbackException("修正的 SQL 超出该域的可访问表：" + outside);
            }
        }

        return examples.createFromFeedback(row.domainId(),
                new SqlExampleInput(row.question(), sql, row.note(), null, true, 0),
                reviewer);
    }

    private static String normalizeTable(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replace("`", "").replace("\"", "");
    }
}

package com.liusongboyy.chatdata.engine.analysis;

import com.liusongboyy.chatdata.engine.datasource.QueryResult;
import com.liusongboyy.chatdata.engine.nlsql.AskDsl;
import com.liusongboyy.chatdata.engine.nlsql.RenderedSql;

import java.util.Set;

/**
 * 一个步骤的执行结果——**成功或失败都在这里**。
 *
 * <p>失败步骤不抛异常：一次分析里某一步查不出来，其余步骤的结果仍然有价值，
 * 结论里明说"这一步没查出来"比整次失败更有用。
 *
 * @param dsl           该步的 DSL（失败时也保留，便于排查"它到底想查什么"）
 * @param sql           渲染出的 SQL；权限谓词已注入。失败在渲染前时为 null
 * @param rows          结果数据；失败时为空
 * @param maskedColumns 本步被脱敏的列
 * @param error         失败原因（给用户看的措辞，不含表结构细节）
 * @param durationMs    这一步的耗时。审计要能回答"时间花在哪一步"
 */
public record StepResult(
        String id,
        String label,
        String purpose,
        AskDsl dsl,
        RenderedSql sql,
        QueryResult rows,
        Set<String> maskedColumns,
        StepStatus status,
        String error,
        long durationMs
) {

    public StepResult {
        rows = rows == null ? QueryResult.empty() : rows;
        maskedColumns = maskedColumns == null ? Set.of() : Set.copyOf(maskedColumns);
        status = status == null ? StepStatus.FAILED : status;
        if (status == StepStatus.FAILED && (error == null || error.isBlank())) {
            // 失败却没有原因，等于把"为什么没数据"藏起来——那正是这个项目最怕的形态
            throw new IllegalArgumentException("失败的分析步骤必须给出原因：" + id);
        }
    }

    public static StepResult ok(String id, String label, String purpose, AskDsl dsl,
                                RenderedSql sql, QueryResult rows, Set<String> maskedColumns) {
        return ok(id, label, purpose, dsl, sql, rows, maskedColumns, 0);
    }

    public static StepResult ok(String id, String label, String purpose, AskDsl dsl,
                                RenderedSql sql, QueryResult rows, Set<String> maskedColumns,
                                long durationMs) {
        return new StepResult(id, label, purpose, dsl, sql, rows, maskedColumns,
                StepStatus.OK, null, durationMs);
    }

    public static StepResult failed(String id, String label, String purpose, AskDsl dsl,
                                    RenderedSql sql, String error) {
        return failed(id, label, purpose, dsl, sql, error, 0);
    }

    public static StepResult failed(String id, String label, String purpose, AskDsl dsl,
                                    RenderedSql sql, String error, long durationMs) {
        return new StepResult(id, label, purpose, dsl, sql, QueryResult.empty(),
                Set.of(), StepStatus.FAILED, error, durationMs);
    }

    public boolean isOk() {
        return status == StepStatus.OK;
    }
}

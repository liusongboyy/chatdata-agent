package com.liusongboyy.chatdata.engine.analysis;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 上一轮分析留下的上下文，供多轮追问使用。
 *
 * <h2>为什么只存摘要，不存原始行</h2>
 *
 * <p>一次分析的步骤结果可能上千行，塞进会话状态会把上下文撑爆——而且模型
 * 也用不上那些行（它真正需要知道的是"上一轮查的是什么、发现了什么"）。
 *
 * <h2>为什么单独一个 state key</h2>
 *
 * <p>agentscope 自己的对话缓冲在它自己的 key 下；这里用
 * {@code analysis-memory} 独立存一份。分开的好处是：清空分析记忆不会影响
 * 多轮对话本身，反之亦然。
 *
 * <h2>与单查询的关系</h2>
 *
 * <p>单查询不写这份记忆。追问「那华南呢」只有在上一轮**做过分析**时才有额外上下文，
 * 其余情况下 agentscope 的对话缓冲已经够了。
 *
 * @param intent   上一轮想解决的问题
 * @param steps    每步的摘要：标签 + DSL 摘要 + 行数
 * @param findings 算出来的事实（文本形式的 few-shot，让模型知道上轮发现了什么）
 * @param narrative 上一轮的结论
 */
public record AnalysisMemory(
        String intent,
        List<String> steps,
        List<String> findings,
        String narrative
) implements State {

    private static final Logger log = LoggerFactory.getLogger(AnalysisMemory.class);
    /** 会话状态里的键。改它会让既有会话丢失分析记忆——不影响正确性。 */
    public static final String KEY = "analysis-memory";
    /** 保留的步骤与事实条数。太多会把 prompt 撑长，而越早的越没用。 */
    private static final int MAX_STEPS = 4;
    private static final int MAX_FINDINGS = 6;

    public AnalysisMemory {
        intent = intent == null ? "" : intent.trim();
        steps = steps == null ? List.of() : List.copyOf(steps);
        findings = findings == null ? List.of() : List.copyOf(findings);
        narrative = (narrative == null || narrative.isBlank()) ? null : narrative.trim();
    }

    /** 从一次分析结果里提炼。只留摘要——行数据不进状态。 */
    public static AnalysisMemory of(AnalysisResult result) {
        if (result == null) {
            return null;
        }
        List<String> steps = new ArrayList<>();
        for (StepResult s : result.steps()) {
            steps.add("%s（%s）：%s".formatted(
                    s.id(), s.label(),
                    s.isOk() ? s.rows().rowCount() + " 行" : "失败：" + s.error()));
        }
        List<String> findings = result.findings().stream()
                .map(f -> "[" + f.kind().name() + "] " + f.text())
                .toList();
        return new AnalysisMemory(result.intent(),
                steps.size() > MAX_STEPS ? steps.subList(0, MAX_STEPS) : steps,
                findings.size() > MAX_FINDINGS ? findings.subList(0, MAX_FINDINGS) : findings,
                result.narrative());
    }

    /**
     * 渲染成给模型看的一段说明。
     *
     * <p><strong>措辞里明确说"这是上一轮"</strong>，否则模型会把摘要当成
     * 当前这一轮已知的事实，直接复述出来。
     */
    public String toPromptBlock() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("\n\n── 上一轮分析（供你理解追问在问什么，**不要直接复述**） ──\n");
        if (!intent.isBlank()) {
            sb.append("上轮问题：").append(intent).append('\n');
        }
        if (!steps.isEmpty()) {
            sb.append("上轮的取数步骤：\n");
            steps.forEach(s -> sb.append("- ").append(s).append('\n'));
        }
        if (!findings.isEmpty()) {
            sb.append("上轮得出的结论要点：\n");
            findings.forEach(s -> sb.append("- ").append(s).append('\n'));
        }
        if (narrative != null) {
            sb.append("上轮的结论文本：").append(narrative).append('\n');
        }
        sb.append("追问时通常只需要改动一个维度或时间范围，不要重复上轮已经查过的内容。\n");
        return sb.toString();
    }

    // ── 存取 ──

    /** 读上一轮的记忆；没有或读失败都返回 null（追问退化成普通提问，不影响正确性）。 */
    public static AnalysisMemory load(AgentStateStore store, String userId, String sessionId) {
        if (store == null || sessionId == null) {
            return null;
        }
        try {
            return store.get(userId, sessionId, KEY, AnalysisMemory.class).orElse(null);
        } catch (Exception e) {
            log.warn("读取分析记忆失败（退化为无记忆追问）：user={} session={} 原因={}",
                    userId, sessionId, e.getMessage());
            return null;
        }
    }

    /** 写记忆。失败只记日志——**绝不能让问数因此失败**。 */
    public void save(AgentStateStore store, String userId, String sessionId) {
        if (store == null || sessionId == null) {
            return;
        }
        try {
            store.save(userId, sessionId, KEY, this);
        } catch (Exception e) {
            log.warn("写入分析记忆失败（不影响本次问数）：user={} session={} 原因={}",
                    userId, sessionId, e.getMessage());
        }
    }
}

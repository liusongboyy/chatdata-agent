package com.liusongboyy.chatdata.ask.health;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 把审计表里的原始错误信息归成可读的失败原因。
 *
 * <h2>为什么必须归类</h2>
 *
 * <p>{@code ask_audit_log.error_message} 是长文本且带具体列名
 * （「『评论数』配置了聚合方式（COUNT），不能作为维度…」）。
 * 直接按原文排行会得到几百个"只出现一次"的条目——每一行都不重复，
 * 排行等于没排。
 *
 * <h2>规则从真实数据里提炼</h2>
 *
 * <p>下面每一条都标注了它在 2026-11-04 那 267 条失败里的实际条数。
 * 规则不是拍脑袋定的：写完用数据回放过，落在 9 个桶。
 *
 * <h2>「环境问题」刻意单列</h2>
 *
 * <p>连接池耗尽、模型密钥缺失 <b>不是建模问题</b>。混进"建模缺口"会让管理员
 * 去改配置，而真正该做的是重启服务或补密钥。
 * <b>归类错了比不归类更糟——它会把排查方向引偏。</b>
 */
public final class FailureClassifier {

    private FailureClassifier() {
    }

    /**
     * 这个失败**能不能修**，以及该往哪修。
     *
     * <h2>为什么不是 boolean</h2>
     *
     * <p>早先用 {@code fixable:boolean}，但实测发现"不能修"里混着两类
     * 完全不同的事：
     *
     * <ul>
     *   <li>{@code NOT_CONFIG}：模型能力边界——改配置也解决不了，
     *       管理员该做的是换问法或接受它</li>
     *   <li>{@code ENVIRONMENT}：连接池耗尽、密钥缺失——该做的是重启服务</li>
     * </ul>
     *
     * <p>两者都返回 false 会让界面只能给一句"不能修"，而那对用户毫无帮助。
     * <b>说清「为什么不能修」比给一个笼统的"不能"有用得多。</b>
     */
    public enum Fixability {
        /** 改建模配置能解决，且能定位到改哪 */
        CONFIG,
        /** 改配置也解决不了：模型没理解、问法本身有歧义 */
        NOT_CONFIG,
        /** 环境问题：重启服务、补密钥 */
        ENVIRONMENT
    }

    /**
     * 一个失败桶。
     *
     * @param key           稳定的键，前端据此配色/排序
     * @param label         人话名称
     * @param count         条数
     * @param fixable       能不能靠改建模配置解决
     * @param sampleMessage 一条原文样例，管理员要判断"我这儿是不是这种"
     */
    public record Bucket(String key, String label, long count,
                         Fixability fixability, String sampleMessage) {

        /** 兼容旧调用方。 */
        public boolean fixable() {
            return fixability == Fixability.CONFIG;
        }
    }

    /**
     * 归类。
     *
     * @param raw 原始 {@code error_message}，可能为空
     * @return 归类结果。**永不返回 null**——空消息归到 OTHER，
     *         而不是让调用方到处判空
     */
    public static Classification classify(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Classification("OTHER", "其他", Fixability.NOT_CONFIG);
        }
        String m = raw.trim();

        // ① 引擎直接给的枚举值。这些已经是稳定的分类，不该被下面的关键词覆盖
        switch (m) {
            case "NOT_UNDERSTOOD":
                // 79 条：模型没把问题翻译成 DSL。这不是配置问题——
                // 改成建模配置也解决不了，属于模型能力边界
                return new Classification("NOT_UNDERSTOOD", "模型没理解问题", Fixability.NOT_CONFIG);
            case "DSL_FAILED":
                return new Classification("DSL_FAILED", "DSL 生成失败", Fixability.NOT_CONFIG);
            case "SEMANTIC_ERROR":
                return new Classification("SEMANTIC_ERROR", "语义错误", Fixability.CONFIG);
            case "PERMISSION_DENIED":
                return new Classification("PERMISSION_DENIED", "权限不足", Fixability.NOT_CONFIG);
            default:
                break;
        }

        String lower = m.toLowerCase(Locale.ROOT);

        // ② 环境问题——**必须排在建模缺口前面**。
        //    "Connection is not available" 里也可能出现别的关键词，
        //    而把它归成建模缺口会让管理员白改配置
        if (lower.contains("connection is not available")
                || lower.contains("connection refused")
                || lower.contains("模型未配置")
                || lower.contains("api-key")
                || lower.contains("communicate with")) {
            // 10 条
            return new Classification("ENVIRONMENT", "环境问题（连接/密钥）", Fixability.ENVIRONMENT);
        }

        // ③ 建模缺口：字段角色冲突。52 条，是最大的一类可修复问题
        if (m.contains("不能作为维度") || m.contains("不能作为指标")) {
            return new Classification("ROLE_CONFLICT", "字段角色冲突（缺双登记）", Fixability.CONFIG);
        }

        // ④ 建模缺口：缺时间口径。26 条
        if (m.contains("找不到可用的日期列") || m.contains("没有可用的日期列")) {
            return new Classification("NO_TIME_COLUMN", "缺时间口径（域内没有日期列）", Fixability.CONFIG);
        }

        // ⑤ 建模缺口：输出列解析失败。16 条
        if (m.contains("没有可渲染的输出列")) {
            return new Classification("NO_OUTPUT_COLUMN", "输出列解析失败", Fixability.CONFIG);
        }

        // ⑥ 建模缺口：模型编了不存在的列。16 条
        //    Unknown column / SQL syntax 都是这个原因的表象
        if (lower.contains("unknown column") || lower.contains("sql syntax")) {
            return new Classification("UNKNOWN_COLUMN", "模型编了不存在的列", Fixability.CONFIG);
        }

        // ⑦ 模型填错取值。30 条——不是建模问题，是模型把字符串当日期填了
        if (m.contains("不是合法日期")) {
            return new Classification("BAD_VALUE_TYPE", "取值类型错误", Fixability.NOT_CONFIG);
        }

        // ⑧ 时间口径不支持。8 条——改问法能绕过，但那是产品该支持的
        if (m.contains("无法解析的时间口径") || m.contains("同环比需要明确的时间范围")) {
            return new Classification("TIME_RANGE_UNSUPPORTED", "时间口径不支持", Fixability.NOT_CONFIG);
        }

        // ⑨ 兜底。30 条——碎片化的剩余部分。
        //    这个桶持续增长说明有新失败模式没被上面的规则覆盖，
        //    应当去提炼新规则而不是接受它
        return new Classification("OTHER", "其他", Fixability.NOT_CONFIG);
    }

    /**
     * 归类结果。
     *
     * @param key     稳定的键
     * @param label   人话名称
     * @param fixable 能不能靠改配置解决。**这个标记决定界面上给不给「去修」链接**——
     *                给了却点不动比不给更糟
     */
    public record Classification(String key, String label, Fixability fixability) {

        /** 便捷判断：是不是"改配置能解决"。 */
        public boolean fixable() {
            return fixability == Fixability.CONFIG;
        }
    }

    /** 供测试与文档用：所有已知的键。 */
    static Pattern allKeys() {
        return Pattern.compile(
                "NOT_UNDERSTOOD|DSL_FAILED|SEMANTIC_ERROR|PERMISSION_DENIED|"
                        + "ENVIRONMENT|ROLE_CONFLICT|NO_TIME_COLUMN|NO_OUTPUT_COLUMN|"
                        + "UNKNOWN_COLUMN|BAD_VALUE_TYPE|TIME_RANGE_UNSUPPORTED|OTHER");
    }
}

package com.liusongboyy.chatdata.engine.modeling;

import com.liusongboyy.chatdata.engine.datasource.MetaReader.ColumnMeta;
import com.liusongboyy.chatdata.engine.semantic.Aggregation;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 从列的元数据推断它在语义模型里的角色。
 *
 * <h2>它是纯函数</h2>
 *
 * <p>入参只有「列元数据 + 基数」，不碰数据库、不碰模型。这样推断规则能<b>单独测</b>——
 * 而这是这个功能最需要的：规则准不准，只有在能逐条断言的时候才看得出来。
 *
 * <h2>它不做的事</h2>
 *
 * <p><b>不生成业务别名。</b>「保费」而不是「premium」是业务知识，机器猜不出来。
 * 这里只给 {@code aliasConfirmed=false} 的候选，管理台高亮提示人工确认。
 *
 * <p><b>不推断派生指标与条件指标。</b>口径必须人来定——那是
 * {@code derived-metrics} 与 {@code conditional-metrics} 两轮反复强调的。
 */
public final class ColumnInferrer {

    private ColumnInferrer() {
    }

    /** 置信度。**三档必须分开标**，管理员据此决定看多仔细。 */
    public enum Confidence {
        /** 判据确定（主键、日期、注释明确）。可以直接用 */
        HIGH,
        /** 判据是启发式（基数、命名）。要人工看一眼 */
        MEDIUM,
        /** 只有弱信号。默认不启用，让管理员决定 */
        LOW
    }

    /**
     * 一条推断结果。
     *
     * @param role       推断出的角色
     * @param aggregations 建议的聚合（可能为空——纯维度列没有聚合）
     * @param confidence 置信度
     * @param reason     为什么这么推断。**必须写**，管理员要看懂才能判断
     */
    public record Inference(Role role, List<Aggregation> aggregations,
                            Confidence confidence, String reason) {
    }

    /** 列在语义模型里的角色。 */
    public enum Role {
        /** 分组维度 */
        DIMENSION,
        /** 聚合指标 */
        METRIC,
        /** 既是维度又是指标（金额列要双登记，见下） */
        DIMENSION_AND_METRIC,
        /** 时间口径候选 */
        TEMPORAL,
        /** 敏感列，默认关闭 */
        SENSITIVE
    }

    // ── 判据 ──

    /** 数值类型。用于判断"可能是指标" */
    private static final Pattern NUMERIC_TYPE = Pattern.compile(
            "(?i)^(int|integer|bigint|smallint|tinyint|mediumint|decimal|numeric|"
                    + "double|float|real|money|number)");

    /** 日期时间类型 */
    private static final Pattern TEMPORAL_TYPE = Pattern.compile(
            "(?i)^(date|datetime|timestamp|time|year)");

    /** 布尔类型 */
    private static final Pattern BOOLEAN_TYPE = Pattern.compile(
            "(?i)^(bit|bool|boolean)");

    /**
     * 金额/数量类列名。命中则建议 SUM + AVG。
     *
     * <p>中英双语——真实企业库两种列名都有，只认英文会漏掉一半。
     */
    /**
     * 度量词表。
     *
     * <p>英文词按子串匹配即可（{@code total_due}、{@code unit_price} 都该命中）。
     *
     * <p>中文词必须覆盖业务域的真实叫法：保险的「保费」、零售的「销售额」
     * 在列名里往往只有注释写着，列名是 {@code premium} / {@code sales}。
     * 漏掉它们，草稿里最核心的那个指标就不会被识别成指标。
     */
    private static final Pattern MEASURE_NAME = Pattern.compile(
            "(?i)(amount|price|fee|total|sum|qty|quantity|score|count|num|value"
                    + "|revenue|profit|tax|salary|balance|cost)"
                    + "|(金额|数量|价格|费用|次数|额|量|总数|合计"
                    + "|保费|销售额|收入|利润|税额|工资|余额|成本|单价)");

    /**
     * 名字像外键或标识的列：{@code SalesPersonID}、{@code RevisionNumber}。
     *
     * <p><b>它们不该被推断成指标。</b>实测踩到：{@code SalesPersonID} 里有 {@code sales}，
     * 命中度量词表后被建议 {@code SUM}——而「销售员 ID 求和」没有任何意义。
     *
     * <p>判据是「名字以 ID / NO / CODE / NUMBER 结尾」，而不是看类型：
     * 这些列常见是 int，与真正的度量同类型。
     */
    private static final Pattern IDENTIFIER_NAME = Pattern.compile(
            "(?i)(_?id|_?no|_?code|_?number|_?uuid|_?guid)$");

    /**
     * 比率类：{@code UnitPriceDiscount}、{@code TaxRate}。
     *
     * <p>它们的 SUM 没有意义（折扣率相加 = 什么？），但 AVG 有意义。
     * 实测踩到：{@code UnitPriceDiscount} 被建议 SUM + AVG。
     */
    private static final Pattern RATIO_NAME = Pattern.compile(
            "(?i)(rate|ratio|percent|pct|discount|margin)"
                    + "|(率|比例|占比|折扣)");

    /**
     * 一条列元数据 + 基数，推断角色。
     *
     * @param column       列元数据
     * @param cardinality  去重计数。<b>负值表示未采样</b>（数值列不采样，见 spec §4.4）
     */
    public static Inference infer(ColumnMeta column, int cardinality) {
        String name = column.name();
        String type = column.type() == null ? "" : column.type();
        String comment = column.comment() == null ? "" : column.comment();

        // ① 敏感列优先——它决定 enabled=false，必须先判
        String sensitiveHit = SensitiveColumns.match(name);
        if (sensitiveHit != null) {
            return new Inference(Role.SENSITIVE, List.of(), Confidence.HIGH,
                    "列名命中敏感词「" + sensitiveHit + "」，默认关闭（失败方向必须安全）");
        }

        // ② 主键：一定是维度，且一定是高置信
        if (column.primary()) {
            return new Inference(Role.DIMENSION, List.of(), Confidence.HIGH,
                    "主键列 → 维度");
        }

        // ③ 布尔：维度 + 一条 COUNT 条件指标
        //    「已关闭的工单数」这种问题靠的就是它
        if (BOOLEAN_TYPE.matcher(type).find()) {
            return new Inference(Role.DIMENSION, List.of(Aggregation.COUNT), Confidence.MEDIUM,
                    "布尔类型 → 维度；建议补一条 COUNT 便于统计「满足条件的行数」");
        }

        // ④ 日期：时间口径候选
        if (TEMPORAL_TYPE.matcher(type).find()) {
            return new Inference(Role.TEMPORAL, List.of(), Confidence.HIGH,
                    "日期/时间类型 → 时间口径候选");
        }

        boolean numeric = NUMERIC_TYPE.matcher(type).find();

        // **标识类列先排除**：SalesPersonID 含 "sales"，RevisionNumber 含 "number"，
        // 都会被度量词表误伤。ID/NO/CODE 结尾的一定不是度量
        boolean identifierName = IDENTIFIER_NAME.matcher(name).find();

        boolean measureName = !identifierName
                && (MEASURE_NAME.matcher(name).find() || MEASURE_NAME.matcher(comment).find());

        // 比率类：SUM 无意义（折扣率相加是什么？），只给 AVG
        boolean ratioName = !identifierName
                && (RATIO_NAME.matcher(name).find() || RATIO_NAME.matcher(comment).find());

        // ④.5 比率列：只给 AVG，不给 SUM
        //      实测：UnitPriceDiscount 被建议 SUM —— 折扣率求和没有意义
        if (numeric && ratioName) {
            return new Inference(Role.DIMENSION_AND_METRIC,
                    List.of(Aggregation.AVG), Confidence.HIGH,
                    "比率类列（名称含率/比例/折扣）→ 只给 AVG；"
                            + "求和没有意义，故同列补一条维度供筛选");
        }

        // ④.6 标识类列：ID/NO/CODE 结尾，即使类型是数值也**不当指标**
        //      实测：SalesPersonID 含 "sales" 被误判成 SUM 指标
        if (numeric && identifierName) {
            return new Inference(Role.DIMENSION, List.of(), Confidence.HIGH,
                    "列名以 ID/NO/CODE 结尾 → 标识类，仅作维度"
                            + "（对 ID 求和或求平均没有业务含义）");
        }

        // ⑤ 数值 + 名称像度量：维度与指标双登记
        //
        //    **为什么要双登记**：docs/modeling-coverage.md 里记的「50 岁以上的客户」
        //    就是栽在这——只有 AVG(age) 时没法写 age > 50。
        //    只登记成指标，这个字段就永远进不了筛选条件。
        if (numeric && measureName) {
            return new Inference(Role.DIMENSION_AND_METRIC,
                    List.of(Aggregation.SUM, Aggregation.AVG), Confidence.HIGH,
                    "数值列 + 名称/注释含度量词 → SUM 指标，并同列补一条维度"
                            + "（否则无法用作筛选条件，如「金额 > 1000」）");
        }

        // ⑥ 数值但名称不像度量：只当维度
        //    典型是 ID、年龄、年份这类。把它们默认成 SUM 指标会误导模型
        if (numeric) {
            return new Inference(Role.DIMENSION, List.of(), Confidence.MEDIUM,
                    "数值列但名称不含度量词 → 仅维度（默认当指标会误导，"
                            + "如「年龄求和」没有意义）");
        }

        // ⑦ 文本，基数低：维度 + COUNT_DISTINCT
        //    「一共涉及多少个省份」是最高频问题之一，没有它模型只能逐项列出
        if (cardinality > 0 && cardinality <= 12) {
            return new Inference(Role.DIMENSION_AND_METRIC,
                    List.of(Aggregation.COUNT_DISTINCT), Confidence.MEDIUM,
                    "文本列，去重基数 " + cardinality + " ≤ 12 → 维度，"
                            + "并补 COUNT_DISTINCT（支持「有多少种 X」这类问题）");
        }

        // ⑧ 基数不低但也有限：仍值得补 COUNT_DISTINCT
        if (cardinality > 12 && cardinality <= 200) {
            return new Inference(Role.DIMENSION_AND_METRIC,
                    List.of(Aggregation.COUNT_DISTINCT), Confidence.LOW,
                    "文本列，去重基数 " + cardinality + " → 维度；"
                            + "补 COUNT_DISTINCT 供「有多少种」类问题，"
                            + "但基数偏高，若该列实际是自由文本请关闭");
        }

        // ⑨ 其他文本 / 未采样
        String baseReason = cardinality < 0
                ? "未采样基数（数值列不采样）"
                : "文本列，去重基数 " + cardinality + " 偏高，不补聚合";
        return new Inference(Role.DIMENSION, List.of(), Confidence.HIGH,
                baseReason + " → 维度");
    }

    /**
     * 给业务名候选。**只是候选，必须人工确认。**
     *
     * <p>规则刻意保守：能给出意思就拿注释，否则原样返回列名。
     * 猜一个错的业务名比不给更糟——用户会照着错名字提问，然后查不出东西。
     */
    public static String aliasCandidate(ColumnMeta column) {
        String comment = column.comment();
        if (comment != null && !comment.isBlank()) {
            // 注释常常是「客户编码」「订单金额（元）」这种，直接用
            return comment.trim();
        }
        return column.name();
    }

    /** 业务名是否需要人工确认。注释为空时必然需要。 */
    public static boolean aliasNeedsConfirmation(ColumnMeta column) {
        return column.comment() == null || column.comment().isBlank();
    }
}

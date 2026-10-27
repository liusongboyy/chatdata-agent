package com.liusongboyy.chatdata.engine.startup;

import com.liusongboyy.chatdata.engine.semantic.Aggregation;
import com.liusongboyy.chatdata.engine.semantic.ColumnDef;
import com.liusongboyy.chatdata.engine.semantic.Domain;
import com.liusongboyy.chatdata.engine.semantic.RegisteredTable;
import com.liusongboyy.chatdata.engine.semantic.SemanticModel;
import com.liusongboyy.chatdata.engine.semantic.Term;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 建模覆盖度检查：这个域"够不够用"。
 *
 * <h2>它防的是哪种失败</h2>
 *
 * <p>这类问题**不会报错**。域加载成功、SQL 合法、执行成功——只是答案不对，
 * 因为模型的工具箱里少了某个常用件，它只好拿现有的凑。
 *
 * <p>实测（2026-10-26）：保险域补齐三条聚合登记后，基线从 74.2% 升到 83.3%，
 * <b>而代码一行未改</b>。缺的那三条是：
 * <ul>
 *   <li>{@code COUNT_DISTINCT}：「一共涉及多少个省份」——只能列出每个省</li>
 *   <li>{@code AVG}：「平均每张保单的保费」——只能出 SUM 和 COUNT 让用户自己除</li>
 *   <li>同一字段的维度登记：「保障年限为 10 年」——只有 AVG 那条，没法当条件</li>
 * </ul>
 *
 * <h2>为什么按"列形态"判而不是按"表"判</h2>
 *
 * <p>没有人会去配置"这个域需要 AVG"。管理员配的是列：这是一笔金额、那是一个状态。
 * 所以检查也按列做——"表里有金额列却没有 AVG"是能直接指导行动的提示，
 * 而"这个域缺 AVG"不是。
 *
 * <h2>严重度是 WARNING</h2>
 *
 * <p>域是可用的，只是不够顺手。一个只用来查总额的域不需要 AVG。
 * 把它做成 ERROR 会让"多配一层"变成"启不来"，代价与收益不成比例。
 */
public final class ModelingCoverageCheck {

    /**
     * 金额/数量类列的判定。
     *
     * <p>按**类型名**判而不是列名：类型来自元数据，是事实；列名是约定，会变。
     * 含这些子串的都算可度量——{@code decimal}、{@code int}、{@code bigint}、
     * {@code double}、{@code numeric}、{@code float}、{@code money}。
     */
    private static final List<String> NUMERIC_HINTS = List.of(
            "decimal", "numeric", "int", "double", "float", "real", "money", "number");

    /** 状态/类型类列的判定：这些列最需要 COUNT_DISTINCT（"有多少种"）。 */
    private static final List<String> TEXT_HINTS = List.of("char", "text", "enum");

    private ModelingCoverageCheck() {
    }

    /**
     * 检查一个域。
     *
     * @return 违规列表；空表示这个域的建模覆盖度没有明显短板
     */
    public static List<EngineConfigViolation> check(Domain domain, SemanticModel model) {
        if (domain.isRaw()) {
            // 直连模式没有列定义，这套检查不适用
            return List.of();
        }

        List<EngineConfigViolation> out = new ArrayList<>();
        out.addAll(checkAggregations(domain));
        out.addAll(checkDualRole(domain));
        out.addAll(checkTimeTerms(domain, model));
        out.addAll(checkContradictions(domain));
        out.addAll(checkNameCollisions(domain));
        return out;
    }

    /**
     * 聚合覆盖：数值列有没有 AVG？文本分类列有没有 COUNT_DISTINCT？
     */
    private static List<EngineConfigViolation> checkAggregations(Domain domain) {
        List<EngineConfigViolation> out = new ArrayList<>();

        boolean anyAvg = false;
        boolean anyDistinct = false;
        List<String> numericWithoutAvg = new ArrayList<>();
        List<String> textWithoutDistinct = new ArrayList<>();

        for (RegisteredTable t : domain.visibleTables()) {
            for (ColumnDef c : t.columns()) {
                if (!c.enabled()) {
                    continue;
                }
                if (c.agg() == Aggregation.AVG) {
                    anyAvg = true;
                }
                if (c.agg() == Aggregation.COUNT_DISTINCT) {
                    anyDistinct = true;
                }
                // "可度量"的列：任何非聚合维度、或已配了求和/计数的指标。
                // 已经在用 AVG 的列自然不算——那是已经覆盖了
                if (c.agg() != Aggregation.AVG && isNumeric(c.type())) {
                    numericWithoutAvg.add(label(t, c));
                }
                if (isTextual(c.type()) && c.agg() == Aggregation.NONE) {
                    textWithoutDistinct.add(label(t, c));
                }
            }
        }

        if (!anyAvg && !numericWithoutAvg.isEmpty()) {
            out.add(violation(EngineConfigViolation.Category.THIN_MODELING, domain,
                    "域内有数值列，但没有任何 AVG 指标（例如 %s）。"
                            .formatted(sample(numericWithoutAvg))
                            + "缺了它，「平均每张…是多少」这类问题只能被凑成 SUM + COUNT，"
                            + "用户得自己相除。建议为其中一列补一条 AVG 指标"));
        }
        if (!anyDistinct && !textWithoutDistinct.isEmpty()) {
            out.add(violation(EngineConfigViolation.Category.THIN_MODELING, domain,
                    "域内有分类列，但没有任何 COUNT_DISTINCT 指标（例如 %s）。"
                            .formatted(sample(textWithoutDistinct))
                            + "缺了它，「一共涉及多少种/多少个」这类问题只能被凑成逐项列出。"
                            + "建议为其中一列补一条去重计数指标"));
        }
        return out;
    }

    /**
     * 同一字段的"指标 + 维度"双登记。
     *
     * <p>只在**确实会有这种问法**的地方提示：数值列既被聚合（用于求和/求平均）、
     * 又可能被当作条件（"保障年限为 10 年"）。这里只做提示不强制——
     * 有些数值列永远只当指标。
     */
    private static List<EngineConfigViolation> checkDualRole(Domain domain) {
        List<EngineConfigViolation> out = new ArrayList<>();

        for (RegisteredTable t : domain.visibleTables()) {
            // 按物理列分组，看有没有同时存在"带聚合"与"不带聚合"两条
            var byColumn = new java.util.LinkedHashMap<String, List<ColumnDef>>();
            for (ColumnDef c : t.columns()) {
                if (!c.enabled() || c.derived() != null || c.col() == null || c.col().isBlank()) {
                    continue;
                }
                byColumn.computeIfAbsent(c.col(), k -> new ArrayList<>()).add(c);
            }
            byColumn.forEach((col, defs) -> {
                boolean metric = defs.stream().anyMatch(ColumnDef::isMetric);
                boolean dimension = defs.stream().anyMatch(d -> !d.isMetric());
                // 只对**像离散量**的数值列提示（年限、年龄、优先级、次数）。
                // 金额、数量这类连续值几乎不会被当成筛选条件——对它们提示等于制造噪音，
                // 而一个天天喊"你缺维度"的检查，很快就没人看了
                if (metric && !dimension && isDiscreteNumeric(col, defs.get(0).type())) {
                    out.add(violation(EngineConfigViolation.Category.THIN_MODELING, domain,
                            "「%s」里的列「%s」只登记了指标（别名「%s」），没有对应的维度。"
                                    .formatted(t.displayName(), col, defs.get(0).displayName())
                                    + "若有人会问「这个字段等于某个值的那些行」（如「保障年限为 10 年的保单」），"
                                    + "需要再登记一条**不聚合**的同名列"));
                }
            });
        }
        return out;
    }

    /**
     * 时间口径：有多个日期列时，有没有用术语显式声明？
     *
     * <p>没有声明时引擎按别名猜（生效日期/统计日期/…），域里有多个日期列就会一律落到第一个——
     * 「按报案日期查」拿回的是生效日期那段时间的数据，数字正常但错的。
     */
    private static List<EngineConfigViolation> checkTimeTerms(Domain domain, SemanticModel model) {
        List<String> dateColumns = new ArrayList<>();
        for (RegisteredTable t : domain.visibleTables()) {
            for (ColumnDef c : t.columns()) {
                if (c.enabled() && SemanticModel.isTemporalType(c.type())) {
                    dateColumns.add(label(t, c));
                }
            }
        }
        if (dateColumns.size() < 2) {
            return List.of();
        }

        Set<String> declared = new LinkedHashSet<>();
        for (Term term : domain.terms()) {
            if (term.applyKind() == Term.ApplyKind.TIME) {
                declared.add(term.name().toLowerCase(Locale.ROOT));
            }
        }
        if (!declared.isEmpty()) {
            return List.of();
        }
        return List.of(violation(EngineConfigViolation.Category.THIN_MODELING, domain,
                "域内有 %d 个日期列（%s），但没有用术语声明时间口径。"
                        .formatted(dateColumns.size(), sample(dateColumns))
                        + "没有声明时引擎按别名猜，会一律落到第一个匹配的日期列——"
                        + "「按报案日期查」拿回的是另一段时间的数据，且不报错。"
                        + "建议把每个要用的日期列登记成 apply_kind=time 的术语"));
    }

    /**
     * 自相矛盾的建模。
     *
     * <p>目前只查一种确有问题的组合：列的统计条件引用了**它自己**。
     * {@code 已解决工单数 = COUNT(ticket_no) WHERE 已解决工单数 = ...} 是循环定义，
     * 渲染出的 SQL 语法合法但语义为真/假之一，永远是错的。
     */
    private static List<EngineConfigViolation> checkContradictions(Domain domain) {
        List<EngineConfigViolation> out = new ArrayList<>();
        for (RegisteredTable t : domain.visibleTables()) {
            for (ColumnDef c : t.columns()) {
                if (c.condition() == null) {
                    continue;
                }
                for (var term : c.condition().terms()) {
                    if (term.field() != null && term.field().equalsIgnoreCase(c.displayName())) {
                        out.add(violation(EngineConfigViolation.Category.CONTRADICTORY_MODELING,
                                domain,
                                "「%s」的统计条件引用了它自己（%s）。这是循环定义，"
                                        .formatted(c.displayName(), c.condition().display())
                                        + "渲染出的条件恒为真或恒为假，结果永远不对"));
                    }
                }
            }
        }
        return out;
    }

    /**
     * 同一物理列在多张表上都注册了——同名但**含义不同**。
     *
     * <h2>为什么这是问题</h2>
     *
     * <p>实测：「出险省份」与「出单省份」是两回事（128 条理赔里 121 条不一致）。
     * 若两张表都把它叫「省份」，模型只能靠猜——猜错的 SQL 合法、数字合理，
     * 只是答的是另一个问题。
     *
     * <p>当前的防法是**把别名起得不一样**（出险省份 / 出单省份）。这是对的，
     * 但依赖管理员每次都想到。这条检查把它变成可发现的问题。
     *
     * <h2>判据：同名列，别名不同，但都没有可区分的描述</h2>
     *
     * <p>别名不同就已经能区分了——这时描述可有可无。真正危险的是
     * <b>别名也相同</b>（解析时会报歧义，那是另一种失败），或
     * <b>别名不同但都像"省份"这种通用词</b>，模型分不出谁是谁。
     */
    private static List<EngineConfigViolation> checkNameCollisions(Domain domain) {
        // 物理列名 → [(表, 列定义)]
        var byColumn = new java.util.LinkedHashMap<String, List<java.util.Map.Entry<RegisteredTable, ColumnDef>>>();
        for (RegisteredTable t : domain.visibleTables()) {
            for (ColumnDef c : t.columns()) {
                if (!c.enabled() || c.col() == null || c.col().isBlank() || c.derived() != null) {
                    continue;
                }
                byColumn.computeIfAbsent(c.col().toLowerCase(Locale.ROOT),
                                k -> new ArrayList<>())
                        .add(java.util.Map.entry(t, c));
            }
        }

        List<EngineConfigViolation> out = new ArrayList<>();
        byColumn.forEach((col, entries) -> {
            Set<String> tables = new LinkedHashSet<>();
            entries.forEach(e -> tables.add(e.getKey().id()));
            // 同一张表里多条（指标/维度双登记）不算跨表冲突
            if (tables.size() < 2) {
                return;
            }

            // 只看**跨表**的那些：同一张表内的双登记是刻意的，不该参与判定
            List<java.util.Map.Entry<RegisteredTable, ColumnDef>> cross = entries.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            e -> e.getKey().id(),
                            e -> e,
                            // 一张表只留一条：优先保留没描述的，那才是"说不清"的那条
                            (a, b) -> a.getValue().description() == null ? a : b,
                            java.util.LinkedHashMap::new))
                    .values().stream().toList();
            if (cross.size() < 2) {
                return;
            }

            // 都有描述 → 已经说清了，不提示
            if (cross.stream().allMatch(e -> e.getValue().description() != null)) {
                return;
            }
            // 别名互不相同 → 名字本身已经能区分，也不提示
            Set<String> aliases = new LinkedHashSet<>();
            cross.forEach(e -> aliases.add(e.getValue().displayName()));
            if (aliases.size() == cross.size()) {
                return;
            }

            List<String> where = cross.stream()
                    .map(e -> e.getKey().displayName() + "." + e.getValue().displayName())
                    .distinct()
                    .toList();
            out.add(violation(EngineConfigViolation.Category.THIN_MODELING, domain,
                    "列「%s」在 %d 张表上都注册了，但业务名相同或不足以区分（%s），"
                            .formatted(col, tables.size(), String.join("、", where))
                            + "而且没有描述说明各自含义。模型只能靠猜——猜错的 SQL 合法、"
                            + "数字合理，只是答的是另一个问题。"
                            + "请给它们起不同的业务名，或补上 description 说清区别"
                            + "（例如「出险地」与「出单地」是两回事）"));
        });
        return out;
    }

    // ── 工具 ──

    private static boolean isNumeric(String type) {
        if (type == null) {
            return false;
        }
        String t = type.toLowerCase(Locale.ROOT);
        return NUMERIC_HINTS.stream().anyMatch(t::contains);
    }

    /**
     * 是否像"离散量"——年限、年龄、优先级、次数这类，既会被求和求平均，
     * 也会被当成筛选条件（"保障年限为 10 年"）。
     *
     * <p>判据是**列名**而不是类型：类型上它们和金额一样都是 {@code int}。
     * 名字里带这些词的才算——宁可漏报，也不要天天对着金额列喊缺维度。
     */
    private static boolean isDiscreteNumeric(String column, String type) {
        if (!isNumeric(type) || column == null) {
            return false;
        }
        String c = column.toLowerCase(Locale.ROOT);
        return DISCRETE_HINTS.stream().anyMatch(c::contains);
    }

    /** 像离散量的列名。中英文都列，因为物理列名两种都可能。 */
    private static final List<String> DISCRETE_HINTS = List.of(
            "year", "age", "level", "priority", "count", "qty", "num", "times", "score",
            "年限", "年龄", "等级", "优先级", "次数", "数量", "评分");

    private static boolean isTextual(String type) {
        if (type == null) {
            return false;
        }
        String t = type.toLowerCase(Locale.ROOT);
        return TEXT_HINTS.stream().anyMatch(t::contains);
    }

    private static String label(RegisteredTable t, ColumnDef c) {
        return t.displayName() + "." + c.displayName();
    }

    /** 提示里最多列三个，多了反而看不清。 */
    private static String sample(List<String> items) {
        if (items.size() <= 3) {
            return String.join("、", items);
        }
        return String.join("、", items.subList(0, 3)) + " 等 " + items.size() + " 个";
    }

    private static EngineConfigViolation violation(
            EngineConfigViolation.Category category, Domain domain, String message) {
        return new EngineConfigViolation(category, domain.id(), domain.displayName(), message);
    }
}

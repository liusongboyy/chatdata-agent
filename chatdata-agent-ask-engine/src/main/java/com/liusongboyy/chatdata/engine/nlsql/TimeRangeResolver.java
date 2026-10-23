package com.liusongboyy.chatdata.engine.nlsql;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 DSL 里的时间范围解析成**具体日期**。
 *
 * <h2>为什么在 Java 侧解析，而不是生成 {@code CURDATE()} 之类的 SQL 函数</h2>
 *
 * <p>方言中立。SQL 里只出现 {@code >= ? AND < ?}，五种数据库写法完全一样——
 * 少一处方言差异就少一类 bug。见 PRD §8.1。
 *
 * <h2>区间是左闭右开的：{@code [start, end)}</h2>
 *
 * <p>渲染成 {@code >= start AND < end}，**不用 {@code BETWEEN}**。
 * 理由：日期列在库里可能是 {@code DATE} 也可能是 {@code DATETIME}/{@code TIMESTAMP}。
 * 若用 {@code BETWEEN '2026-09-01' AND '2026-09-30'}，{@code TIMESTAMP} 列上
 * {@code 2026-09-30 14:00} 会被算作 {@code > '2026-09-30 00:00:00'} 而**漏掉**——
 * 这是个经典的静默错数据问题。左闭右开对两种类型都正确。
 *
 * <h2>解析不了时必须失败，不能静默丢掉时间条件</h2>
 *
 * <p>模型可能产出「上个季度」「近三个月」这类我们没实现的表达。
 * 丢掉它的后果是**返回全时段数据**，数字看着正常，用户完全无从察觉——
 * 与 PRD §12 里「静默语义错误」同类。故一律抛异常。
 */
public final class TimeRangeResolver {

    /** 「近 N 天」的写法。 */
    private static final Pattern RECENT_DAYS = Pattern.compile("^(?:近|最近|过去)\\s*(\\d+)\\s*天$");

    /** 「近 N 个月」的写法，数字可以是阿拉伯数字或中文数字。 */
    private static final Pattern RECENT_MONTHS =
            Pattern.compile("^(?:近|最近|过去)\\s*(\\d+|[一二两三四五六七八九十]+)\\s*个?月$");

    /**
     * 已支持的时间口径词。
     *
     * <p><strong>只用于"这句话里提到的时间口径是什么"这类扫描</strong>（见
     * {@code ConstraintCheck}），不参与解析——解析的判据始终是 {@link #byKind} 里那个 switch。
     *
     * <p>两者可能漂移，故有一条测试逐个断言本表里的词都能解析成功
     * （{@code TimeRangeResolverTest}）：往 switch 里加了口径却忘了加进本表，
     * 或者本表里写了一个其实不支持的口径，都会直接报红。
     */
    public static final List<String> KIND_WORDS = List.of(
            "今天", "今日", "昨天", "昨日",
            "本月", "这个月", "当月", "上个月", "上月", "上一个月",
            "今年", "本年", "当年", "去年", "上年",
            "本季度", "这个季度", "当季", "上季度", "上个季度", "上一季度");

    /**
     * 词表里的词是否真的能解析。
     *
     * <p>判据**就是 {@link #resolve}**，不是另写一遍——另写一遍就等于有了两份真相。
     */
    public static boolean isSupportedKindWord(String word) {
        if (word == null || word.isBlank()) {
            return false;
        }
        try {
            return resolve(word, null, null, LocalDate.now()) != null;
        } catch (TimeRangeException e) {
            return false;
        }
    }

    /** 「近 N 天」在自由文本里的写法。用于从提问里扫出这类相对时间。 */
    public static Pattern recentDaysPattern() {
        return RECENT_DAYS;
    }

    /** 「近 N 个月」在自由文本里的写法。 */
    public static Pattern recentMonthsPattern() {
        return RECENT_MONTHS;
    }

    private TimeRangeResolver() {
    }

    /**
     * 解析结果。左闭右开。
     *
     * @param startInclusive 起始日期（含）
     * @param endExclusive   结束日期（不含）
     */
    public record ResolvedRange(LocalDate startInclusive, LocalDate endExclusive) {
        public ResolvedRange {
            if (startInclusive == null || endExclusive == null) {
                throw new IllegalArgumentException("时间范围两端都不能为空");
            }
            if (!startInclusive.isBefore(endExclusive)) {
                throw new IllegalArgumentException(
                        "时间范围起点必须早于终点：[" + startInclusive + ", " + endExclusive + ")");
            }
        }
    }

    /**
     * 解析。
     *
     * @param kind  口径名称，如「上个月」「近10天」。可为空——此时必须能从 start/end 解析出来
     * @param start 起始日期 {@code yyyy-MM-dd}，可为空
     * @param end   结束日期 {@code yyyy-MM-dd}，**含**（会转成右开）
     * @param today 参考日期。传入以便测试——不要在这里直接调 {@code LocalDate.now()}
     * @return 解析结果；{@code null} 表示**没有时间条件**（DSL 的 timeRange 整体为空）
     * @throws TimeRangeException 有表达但解析不了
     */
    public static ResolvedRange resolve(String kind, String start, String end, LocalDate today) {
        // ① 显式日期优先：模型直接给了 yyyy-MM-dd 就用它，比口径名更精确
        LocalDate s = parseIsoOrNull(start);
        LocalDate e = parseIsoOrNull(end);
        if (s != null && e != null) {
            // end 是**含**的，转成右开
            return new ResolvedRange(s, e.plusDays(1));
        }
        if (s != null) {
            // 只给了起点，终点按口径名或「至今」处理
            return new ResolvedRange(s, today.plusDays(1));
        }

        if (kind == null || kind.isBlank()) {
            // 既没有可解析的日期，也没有口径名 —— 视为无时间条件，由调用方决定
            return null;
        }

        String k = kind.trim().replace(" ", "").toLowerCase(Locale.ROOT);
        return byKind(k, today, kind);
    }

    private static ResolvedRange byKind(String k, LocalDate today, String original) {
        LocalDate firstOfMonth = today.withDayOfMonth(1);

        return switch (k) {
            case "今天", "今日" -> new ResolvedRange(today, today.plusDays(1));
            case "昨天", "昨日" -> new ResolvedRange(today.minusDays(1), today);

            case "本月", "这个月", "当月" -> new ResolvedRange(firstOfMonth, firstOfMonth.plusMonths(1));
            case "上个月", "上月", "上一个月" ->
                    new ResolvedRange(firstOfMonth.minusMonths(1), firstOfMonth);

            case "今年", "本年", "当年" -> new ResolvedRange(today.withDayOfYear(1), today.withDayOfYear(1).plusYears(1));
            case "去年", "上年" -> {
                LocalDate y = today.withDayOfYear(1).minusYears(1);
                yield new ResolvedRange(y, y.plusYears(1));
            }

            case "本季度", "这个季度", "当季" -> {
                LocalDate q = quarterStart(today);
                yield new ResolvedRange(q, q.plusMonths(3));
            }
            case "上季度", "上个季度", "上一季度" -> {
                LocalDate q = quarterStart(today).minusMonths(3);
                yield new ResolvedRange(q, q.plusMonths(3));
            }

            default -> resolveRecent(k, today, original);
        };
    }

    private static ResolvedRange resolveRecent(String k, LocalDate today, String original) {
        Matcher days = RECENT_DAYS.matcher(k);
        if (days.matches()) {
            int n = Integer.parseInt(days.group(1));
            if (n <= 0) {
                throw new TimeRangeException("「" + original + "」的天数必须为正数");
            }
            return new ResolvedRange(today.minusDays(n - 1L), today.plusDays(1));
        }

        Matcher months = RECENT_MONTHS.matcher(k);
        if (months.matches()) {
            int n = parseCount(months.group(1));
            if (n <= 0) {
                throw new TimeRangeException("「" + original + "」的月数必须为正数");
            }
            // 含本月在内的 N 个自然月，与本月的处理方式一致
            LocalDate firstOfMonth = today.withDayOfMonth(1);
            return new ResolvedRange(firstOfMonth.minusMonths(n - 1L), firstOfMonth.plusMonths(1));
        }

        // 解析不了就抛——**绝不能返回 null 让调用方当成"没有时间条件"**
        throw new TimeRangeException(
                "无法解析的时间口径：「" + original + "」。"
                        + "已支持：今天/昨天、本月/上个月、今年/去年、本季度/上季度、近N天、近N个月，"
                        + "或直接给出 yyyy-MM-dd 的起止日期");
    }

    private static int parseCount(String raw) {
        if (raw.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(raw);
        }
        int ten = raw.indexOf('十');
        if (ten < 0) {
            return chineseDigit(raw);
        }
        int tens = ten == 0 ? 1 : chineseDigit(raw.substring(0, ten));
        int ones = ten == raw.length() - 1 ? 0 : chineseDigit(raw.substring(ten + 1));
        return tens * 10 + ones;
    }

    private static int chineseDigit(String s) {
        if (s.length() != 1) {
            throw new TimeRangeException("无法识别的数字：" + s);
        }
        return switch (s.charAt(0)) {
            case '一' -> 1;
            case '二', '两' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> throw new TimeRangeException("无法识别的数字：" + s);
        };
    }

    private static LocalDate quarterStart(LocalDate d) {
        int q = d.get(IsoFields.QUARTER_OF_YEAR);
        return LocalDate.of(d.getYear(), (q - 1) * 3 + 1, 1);
    }

    private static LocalDate parseIsoOrNull(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(v.trim());
        } catch (Exception e) {
            // 模型可能往这个字段塞了一整句话（spike 实测过）。这里只是"不是日期"，
            // 由上层用口径名兜底；两者都解析不了才报错。
            return null;
        }
    }

    /** 时间口径解析失败。**不要捕获它去降级成"无时间条件"。** */
    public static class TimeRangeException extends RuntimeException {
        public TimeRangeException(String message) {
            super(message);
        }
    }
}

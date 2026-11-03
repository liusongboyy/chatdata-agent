package com.liusongboyy.chatdata.engine.modeling;

import com.liusongboyy.chatdata.engine.semantic.Aggregation;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 敏感列名单。
 *
 * <h2>为什么是常量表而不是配置</h2>
 *
 * <p>这份名单是<b>失败方向安全</b>的一环：漏关一个敏感列 = 数据泄露，
 * 误关一个普通列 = 模型少一个字段、用户看得见（"这个字段问不出来"）。
 * 前者不可接受，后者可接受，所以名单宁宽勿窄，且不该让管理员在别处配 override。
 *
 * <p>做成常量而不是配置项，是因为配置项会被"为了跑通先全开"改掉，
 * 而这一项被改掉的代价是静默的数据暴露。
 */
public final class SensitiveColumns {

    private SensitiveColumns() {
    }

    /**
     * 命中即默认关闭的关键词。**全部小写**，比对前把列名转小写。
     *
     * <p>刻意包含中英文两套：真实企业库的列名两种都有。
     */
    private static final Set<String> KEYWORDS = Set.of(
            // 证件与联系方式
            "idcard", "id_card", "identity", "passport",
            "phone", "mobile", "tel", "email", "mail", "address",
            // 凭据
            "password", "pwd", "secret", "token", "credential",
            // 金融
            "bank", "cardno", "card_no", "account_no", "iban", "cvv",
            // 中文
            "身份证", "手机", "电话", "邮箱", "密码", "银行卡", "地址",
            "姓名", "住址", "通讯");

    /**
     * 整词匹配用：列名拆成 token 后逐个比对。
     *
     * <p>为什么不用 {@code contains}：{@code email_verified} 该命中，
     * 但 {@code tel} 用 contains 会误伤 {@code hotel}、{@code telephone_number}
     * 倒是该命中。拆词后 {@code hotel} 是单个 token，不会等于 {@code tel}。
     */
    private static final Pattern SPLIT = Pattern.compile("[^a-z0-9\\u4e00-\\u9fa5]+");

    /**
     * 判断一个列名是否命中敏感规则。
     *
     * @param column 物理列名或业务名
     * @return 命中的关键词；未命中返回 {@code null}
     */
    public static String match(String column) {
        if (column == null || column.isBlank()) {
            return null;
        }
        String lower = column.toLowerCase(Locale.ROOT);

        // 中文关键词直接 contains：中文不分词，「客户手机号」含「手机」就该命中
        for (String kw : KEYWORDS) {
            if (isCjk(kw) && lower.contains(kw)) {
                return kw;
            }
        }

        // 英文关键词按 token 精确比对，避免 tel 误伤 hotel
        for (String token : SPLIT.split(lower)) {
            if (!token.isEmpty() && KEYWORDS.contains(token)) {
                return token;
            }
        }

        // 驼峰合并写法兜底：idcard / cardno 这类没有分隔符的复合词，
        // 上面的 token 切分只剩一个整体，用 startsWith/endsWith 再兜一层
        for (String kw : KEYWORDS) {
            if (isCjk(kw) || kw.length() < 4) {
                continue;   // 短词（tel、pwd）走精确匹配，避免误伤
            }
            if (lower.startsWith(kw) || lower.endsWith(kw)) {
                return kw;
            }
        }
        return null;
    }

    public static boolean isSensitive(String column) {
        return match(column) != null;
    }

    private static boolean isCjk(String s) {
        return s.codePoints().anyMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF);
    }

    /** 供测试与文档使用。 */
    static List<String> keywords() {
        return KEYWORDS.stream().sorted().toList();
    }

    /** 建议的关闭原因，写进草稿让管理员知道为什么。 */
    public static String reason(String column) {
        String hit = match(column);
        return hit == null ? null : "列名命中敏感词「" + hit + "」，默认关闭（失败方向必须安全）";
    }
}

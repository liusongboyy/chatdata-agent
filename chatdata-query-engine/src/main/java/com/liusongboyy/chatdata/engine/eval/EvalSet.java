package com.liusongboyy.chatdata.engine.eval;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 一个语义域的评测集。
 *
 * <p>身份（userId / roles）写在评测集上而不是每条用例上：同一批评测必须以同一个视角跑，
 * 否则两条结果不可比——权限不同会改变行级过滤与列脱敏，结果集本来就该不同。
 * 需要测多种角色时，建多个评测集文件，而不是在同一个文件里混着跑。
 *
 * @param name        评测集名称（进报告）
 * @param domainId    语义域 id。**评测按域走**，不经过助理路由——
 *                    助理层只决定"这个助理可不可用"，不改变 SQL
 * @param userId      评测身份。默认 {@code eval}
 * @param roles       该身份的角色快照，必须能通过域授权，否则每条都会是 DENIED
 * @param assistantId 可选。留空表示直接按域问数
 * @param primaryMetric 主指标：`strict`（默认）或 `contains`。见 {@link PrimaryMetric}
 * @param cases       用例列表
 */
public record EvalSet(
        String name,
        String domainId,
        String userId,
        Set<String> roles,
        String assistantId,
        PrimaryMetric primaryMetric,
        List<EvalCase> cases
) {

    /**
     * 该数据集用哪个 EX 口径作为**验收主指标**。
     *
     * <p>默认 {@link #STRICT}：列数、行数、值全一致才算对，与 BIRD / Spider 可比。
     *
     * <p>{@link #CONTAINS} 是给**直连模式**这类场景用的：模型自己写 SQL，
     * 没有语义层收敛字段范围，于是常顺手多带一列上下文（id、名称）。
     * 那是设计使然，不是答错。用严格口径衡量它，失败里会混进大量"数据正确但话多"，
     * 数字随之失去指导意义。
     *
     * <p><strong>由数据集自己声明，而不是按 mode 自动推断。</strong>
     * mode 是运行时结果（同一批用例可能 MIXED），而"用什么口径验收"是评审决策——
     * 把它藏进推断逻辑里，改口径就成了一个看不见的副作用。
     */
    public enum PrimaryMetric {
        /** 严格 EX：列数、行数、值全一致。 */
        STRICT,
        /** 包含式 EX：gold 的列都能匹配上即可，多返回的列不算错。 */
        CONTAINS;

        /**
         * JSON 反序列化入口。
         *
         * <p>不标 {@code @JsonCreator} 的话，Jackson 只认枚举常量名（{@code STRICT}），
         * 而评测集文件里写小写 {@code "strict"} 更自然——也得允许中文口径名。
         * 两条路径最终都走 {@link #parse}，不会出现"文件能读、代码里解析不了"的分叉。
         */
        @com.fasterxml.jackson.annotation.JsonCreator
        public static PrimaryMetric parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return STRICT;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "contains", "包含式", "contains_ex", "contains-ex" -> CONTAINS;
                case "strict", "严格", "strict_ex", "strict-ex" -> STRICT;
                default -> throw new IllegalArgumentException(
                        "未知的 primaryMetric：" + raw + "（只支持 strict / contains）");
            };
        }

        /** 该口径对应的通过数与通过率。 */
        public int passedOf(int strictPassed, int containsPassed) {
            return this == STRICT ? strictPassed : containsPassed;
        }
    }

    public EvalSet {
        if (domainId == null || domainId.isBlank()) {
            throw new IllegalArgumentException("评测集缺少 domainId");
        }
        name = (name == null || name.isBlank()) ? "domain-" + domainId : name.trim();
        domainId = domainId.trim();
        userId = (userId == null || userId.isBlank()) ? "eval" : userId.trim();
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        if (roles.isEmpty()) {
            throw new IllegalArgumentException(
                    "评测集 " + name + " 没有配置 roles——权限判定会把每条都拒掉");
        }
        assistantId = (assistantId == null || assistantId.isBlank()) ? null : assistantId.trim();
        // 老文件不写这个字段，走默认口径
        primaryMetric = primaryMetric == null ? PrimaryMetric.STRICT : primaryMetric;
        // 显式置空与不写等价；下面那个兼容构造器会用到
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("评测集 " + name + " 没有用例");
        }
        cases = List.copyOf(cases);

        // id 重复会让报告里的失败定位指向错误的那一条
        Set<String> seen = new HashSet<>();
        for (EvalCase c : cases) {
            if (!seen.add(c.id())) {
                throw new IllegalArgumentException("评测集 " + name + " 存在重复用例 id：" + c.id());
            }
        }
    }

    /**
     * 兼容构造器：不声明主指标时按**严格 EX**（与 BIRD / Spider 可比）。
     *
     * <p>保留它是因为绝大多数数据集就该用严格口径；要求每个都写一遍
     * {@code PrimaryMetric.STRICT} 只会制造噪音，并让"这里是不是特意选过口径"
     * 变得看不出来。
     */
    public EvalSet(String name, String domainId, String userId, Set<String> roles,
                   String assistantId, List<EvalCase> cases) {
        this(name, domainId, userId, roles, assistantId, PrimaryMetric.STRICT, cases);
    }

    /** 只保留前 {@code limit} 条；{@code limit <= 0} 表示全量。 */
    public EvalSet limitedTo(int limit) {
        if (limit <= 0 || limit >= cases.size()) {
            return this;
        }
        return new EvalSet(name, domainId, userId, roles, assistantId,
                primaryMetric, cases.subList(0, limit));
    }
}

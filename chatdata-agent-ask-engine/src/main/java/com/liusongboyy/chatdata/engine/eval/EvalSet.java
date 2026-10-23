package com.liusongboyy.chatdata.engine.eval;

import java.util.HashSet;
import java.util.List;
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
 * @param cases       用例列表
 */
public record EvalSet(
        String name,
        String domainId,
        String userId,
        Set<String> roles,
        String assistantId,
        List<EvalCase> cases
) {

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

    /** 只保留前 {@code limit} 条；{@code limit <= 0} 表示全量。 */
    public EvalSet limitedTo(int limit) {
        if (limit <= 0 || limit >= cases.size()) {
            return this;
        }
        return new EvalSet(name, domainId, userId, roles, assistantId,
                cases.subList(0, limit));
    }
}

package com.liusongboyy.chatdata.iam.policy;

import com.liusongboyy.chatdata.iam.config.IamProperties;
import com.liusongboyy.chatdata.iam.user.UserMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户目录：服务间只读接口，供业务模块的策略编辑器选择用户。
 *
 * <p>权限配置里的 {@code grantUsers} 存的是 <b>username</b>，不是显示名也不是主键。
 * 让管理员手打 username 与手打角色码是同一类风险：写错时不报错，规则只是永远
 * 匹配不上。这里返回 username 与显示名，让调用方做"选而不是敲"。
 *
 * <p>与 {@code /iam/policy/roles} 同级：走客户端凭证，由
 * {@link com.liusongboyy.chatdata.iam.config.PolicyClientAuthInterceptor}
 * 统一鉴权，本类里看不到鉴权代码是故意的。
 *
 * <p><b>包含停用用户。</b>已保存的策略可能引用他们，界面需要能标出
 * "已停用，规则不会生效"，而不是让引用对象从列表里消失。
 */
@RestController
public class PolicyUserDirectoryController {

    /** 一次最多返回多少条。选项列表不需要全量，搜索由关键词负责。 */
    private static final int MAX_LIMIT = 50;

    private final UserMapper users;
    private final IamProperties properties;

    public PolicyUserDirectoryController(UserMapper users, IamProperties properties) {
        this.users = users;
        this.properties = properties;
    }

    @GetMapping("/iam/policy/users")
    public Map<String, Object> users(@RequestParam(required = false) String keyword,
                                     @RequestParam(defaultValue = "20") int limit,
                                     @RequestParam(required = false) List<String> usernames) {
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<String> names = normalize(usernames);
        List<com.liusongboyy.chatdata.iam.user.UserMapper.UserOptionRow> rows =
                names.isEmpty()
                        ? users.selectOptions(properties.getTenant(), likePattern(keyword), size)
                        : users.selectByUsernames(properties.getTenant(), names);
        List<Map<String, Object>> items = rows
                .stream()
                .map(u -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("username", u.username());
                    item.put("displayName", u.displayName());
                    item.put("status", u.status());
                    return item;
                })
                .toList();
        return Map.of("items", items);
    }

    /** 批量校验用：去空、去重，保留原样（用户名逐字符匹配）。 */
    private static List<String> normalize(List<String> usernames) {
        if (usernames == null) {
            return List.of();
        }
        return usernames.stream()
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
    }

    /** 与 {@code AdminUserService} 的转义规则一致：{@code % _ !} 按字面量处理。 */
    private static String likePattern(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String escaped = keyword.trim()
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }
}

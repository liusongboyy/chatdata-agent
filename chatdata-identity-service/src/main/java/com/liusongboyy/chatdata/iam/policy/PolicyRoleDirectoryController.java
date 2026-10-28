package com.liusongboyy.chatdata.iam.policy;

import com.liusongboyy.chatdata.iam.config.IamProperties;
import com.liusongboyy.chatdata.iam.role.RoleMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色目录：服务间只读接口，供资源服务器把「可用的角色码」取回去。
 *
 * <h2>为什么需要它，而不是复用 {@code /iam/admin/roles}</h2>
 *
 * <p>{@code /iam/admin/roles} 认的是**用户令牌**，且要求 {@code iam.roles} 权限。
 * 而调用方要的是两件与管理权限无关的事：
 *
 * <ul>
 *   <li>启动校验：比对权限配置里写的角色码是否真的存在。那时**没有任何用户**，
 *       它代表的是服务本身</li>
 *   <li>管理台的角色点选：管理员填的是角色码，让他手打就是在制造静默失效</li>
 * </ul>
 *
 * <p>为了这两件事给资源服务器发一份 {@code iam.roles} 管理权限，等于把"读角色清单"
 * 和"改角色"绑成同一把钥匙。所以这里与 {@code /iam/policy/**} 同级：
 * 客户端凭证鉴权，只读，由 {@link PolicyClientAuthInterceptor} 在整个前缀上统一把关
 * ——<b>本类里看不到鉴权代码是故意的</b>。
 *
 * <h2>为什么含已停用的角色</h2>
 *
 * <p>「不存在」与「存在但已停用」要分开报：前者多半是角色码打错了，
 * 后者是有人主动停用了角色而没清理引用它的规则。两者的处理方式完全不同。
 */
@RestController
public class PolicyRoleDirectoryController {

    private final RoleMapper roles;
    private final IamProperties properties;

    public PolicyRoleDirectoryController(RoleMapper roles, IamProperties properties) {
        this.roles = roles;
        this.properties = properties;
    }

    @GetMapping("/iam/policy/roles")
    public Map<String, Object> roles() {
        // 直接复用管理面那条查询：角色表很小，而两份 SELECT 迟早会漂移
        // （比如一处加了排序、另一处没加），读的是同一份事实就不必各写一条
        List<Map<String, Object>> items = roles.selectAll(properties.getTenant()).stream()
                .map(r -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    // **原样返回，不 trim、不改大小写。**运行时是逐字符匹配的，
                    // 这里做归一化会造出"校验通过、运行时匹配不上"这种最坏组合
                    item.put("code", r.code());
                    item.put("name", r.name());
                    item.put("category", r.category());
                    item.put("enabled", r.enabled());
                    return item;
                })
                .toList();
        return Map.of("items", items);
    }
}

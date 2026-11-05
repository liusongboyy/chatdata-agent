package com.liusongboyy.chatdata.iam.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账号中心 SPA 深链接的覆盖测试。
 *
 * <h2>与 ask 那边同一条约束</h2>
 *
 * <p>前端用 history 路由，服务器上不存在那些路径对应的文件。页内导航能进，
 * **刷新就 404**——而开发时用 Vite dev server（自带 fallback），发现不了。
 *
 * <p>ask 那边已经漏过两次（`/admin/models`、`/admin/cost`），
 * 这里从一开始就把检查装上。
 *
 * <p>读前端的 {@code router.ts} 而不是维护一份清单：维护清单等于把同一件事
 * 写两遍，而漏的永远是后写的那一遍。
 */
class IamWebRouteCoverageTest {

    /** 前端 router.ts。相对模块目录，故从 identity-service 往上找。 */
    private static final Path ROUTER_TS =
            Path.of("..", "chatdata-identity-web", "src", "router.ts");

    private static final Pattern PATH_LITERAL =
            Pattern.compile("path:\\s*'([^']+)'");

    @Test
    @DisplayName("★ 前端每条静态路由都有 SPA 转发——否则刷新即 404")
    void everyStaticRouteIsForwarded() throws IOException {
        assertThat(ROUTER_TS)
                .as("找不到前端 router.ts，本测试失去意义。路径变了要同步改这里")
                .exists();

        Set<String> frontend = staticAppPaths(Files.readString(ROUTER_TS));
        assertThat(frontend).as("从 router.ts 一条路径都没解析出来，正则可能失效了")
                .isNotEmpty();

        Set<String> forwarded = forwardedPaths();
        List<String> missing = new ArrayList<>();
        for (String p : frontend) {
            if (!forwarded.contains(p)) {
                missing.add(p);
            }
        }

        assertThat(missing)
                .as("以下前端路由没有 SPA 转发，刷新会 404。"
                        + "在 WebAppForwardController 的 @GetMapping 里补上")
                .isEmpty();
    }

    /**
     * 提取 `/iam/app/` 下的静态路径。
     *
     * <p>与 ask 那边同样的父子解析：顶层是绝对路径（`/admin`），
     * 子路由是相对路径（`users`），实际地址是拼接结果。
     * 只加前缀会得到 `/iam/app/users`，与真实的 `/iam/app/admin/users` 对不上，
     * 于是报出一堆**假的缺失**，而真正缺的那条被淹没。
     */
    private static Set<String> staticAppPaths(String routerTs) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = PATH_LITERAL.matcher(routerTs);
        String parent = "";
        while (m.find()) {
            String raw = m.group(1);
            if (raw.startsWith("http")) {
                continue;
            }
            String resolved;
            if (raw.startsWith("/")) {
                resolved = raw;
                parent = raw;
            } else if (raw.isEmpty()) {
                continue;
            } else {
                resolved = parent.isEmpty() ? "/" + raw : parent + "/" + raw;
            }
            if (resolved.contains(":")) {
                continue;
            }
            out.add("/iam/app" + (resolved.startsWith("/") ? resolved : "/" + resolved));
        }
        return out;
    }

    /** 转发控制器上声明的全部路径。`@GetMapping` 挂在方法上。 */
    private static Set<String> forwardedPaths() {
        Set<String> out = new LinkedHashSet<>();
        for (var method : WebAppForwardController.class.getDeclaredMethods()) {
            GetMapping mapping = method.getAnnotation(GetMapping.class);
            if (mapping != null) {
                out.addAll(List.of(mapping.value()));
            }
        }
        assertThat(out).as("转发控制器上一条路径都没读到，反射取法可能错了").isNotEmpty();
        return out;
    }
}

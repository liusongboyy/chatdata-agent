package com.liusongboyy.chatdata.ask.config;

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
 * SPA 深链接的**覆盖测试**：前端每条静态路由，后端都必须有对应的转发。
 *
 * <h2>它挡的是什么</h2>
 *
 * <p>前端用 history 路由，服务器上并不存在那些路径对应的文件。页内导航能进，
 * **刷新就 404**——而开发时用 Vite dev server（自带 fallback），几乎发现不了。
 *
 * <p>实测已经漏过两次：加了 `/admin/models` 和 `/admin/cost` 两页，
 * 转发清单没跟上。表现是「点进去正常、刷新报 404」，而报的还是
 * 我们自己的 JSON 错误体（{@code {"code":404,...}}），看起来像接口挂了。
 *
 * <h2>为什么读源码而不是维护一份清单</h2>
 *
 * <p>维护清单等于把同一件事写两遍，而漏的永远是后写的那一遍。
 * 这里直接解析 {@code router.ts}——它是前端路由的**唯一事实**。
 *
 * <p>只提取**静态**路径：带参数的（如 {@code domains/:id/tables}）在
 * 转发控制器里用 `{id}` 通配表达，不能直接比对；这类由人工维护，
 * 数量少且不会随功能增加而膨胀。
 */
class WebAppRouteCoverageTest {

    /** 前端 router.ts 的位置。相对模块目录，故从 query-service 往上找。 */
    private static final Path ROUTER_TS =
            Path.of("..", "chatdata-web", "src", "router.ts");

    /**
     * 前端路由 path 的写法：`path: '/admin/cost'`。
     *
     * 只匹配引号包起来的字面量；带冒号的动态段在下面单独排除。
     */
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
     * 从 router.ts 提取 `/app/` 下的静态路径。
     *
     * <h2>子路由是相对路径</h2>
     *
     * <p>路由表分两层：顶层 `{ path: '/ask' }` 是绝对路径，而管理面那些
     * 嵌在 `/admin` 下的子路由写的是 `{ path: 'models' }`——**没有前导斜杠**。
     * 实际访问地址是父路径与子路径的拼接：`/app` + `/admin` + `/models`。
     *
     * <p>只加 `/app` 前缀会得到 `/app/models`，与真实的 `/app/admin/models`
     * 对不上——于是这个测试会报出一堆**假的缺失**，而真正缺的那条被淹没在里面。
     * 这里按"遇到绝对路径就换父路径，遇到相对路径就拼在父路径后"解析。
     *
     * <p>排除三类：
     * <ul>
     *   <li>带 `:` 的动态段——转发里用 `{id}` 通配，不能直接比</li>
     *   <li>空路径 `''`（重定向项）</li>
     *   <li>外部链接（`http` 开头）</li>
     * </ul>
     */
    private static Set<String> staticAppPaths(String routerTs) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = PATH_LITERAL.matcher(routerTs);
        // 当前父路径。遇到绝对路径（以 / 开头）时更新它
        String parent = "";
        while (m.find()) {
            String raw = m.group(1);
            if (raw.startsWith("http")) {
                continue;
            }
            String resolved;
            if (raw.startsWith("/")) {
                resolved = raw;
                // 顶层路径成为后续相对路径的父级。
                // `/admin` 是个例外：它的子路由要拼在它后面，而它自身也要能转发
                parent = raw;
            } else if (raw.isEmpty()) {
                continue;
            } else {
                resolved = parent.isEmpty() ? "/" + raw : parent + "/" + raw;
            }
            if (resolved.contains(":")) {
                continue;
            }
            out.add("/app" + (resolved.startsWith("/") ? resolved : "/" + resolved));
        }
        return out;
    }

    /**
     * 转发控制器上声明的全部路径。
     *
     * <p>{@code @GetMapping} 挂在**方法**上而不是类上——类上只有 {@code @Controller}。
     * 反射要遍历方法去找，不能直接读类注解。
     */
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

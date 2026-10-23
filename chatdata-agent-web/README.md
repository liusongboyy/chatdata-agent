# chatdata-agent-web

问数 Agent 的前端。见 [`.scratch/self-built-chatbi/spec.md`](../.scratch/self-built-chatbi/spec.md) §8「前端」。

Vue 3 + Vite + TypeScript。**独立工程，不是 Maven module**——让 Java 构建依赖 node
是得不偿失的耦合。

## 跑起来

需要后端在 `127.0.0.1:8080`。

```bash
npm install
npm run dev          # http://localhost:5173
```

开发时 Vite 把 `/sys` 与 `/api` 代理到后端（见 `vite.config.ts`），
所以浏览器看到的是**同源**——Cookie 能正常带上，也不需要后端配 CORS。

## 构建

```bash
npm run build        # typecheck + 打包
```

产物**直接输出到** `../chatdata-agent-ask/src/main/resources/static/app/`，
不需要额外拷贝。之后：

```bash
cd .. && mvn -pl chatdata-agent-ask spring-boot:run
# 打开 http://127.0.0.1:8080/app/
```

## 其他命令

```bash
npm run typecheck    # 只做类型检查，不打包
npm run preview      # 本地预览构建产物（注意：不带后端代理）
```

## 目录

```
src/
├── api/
│   ├── http.ts           fetch 封装。凭据只走 Cookie
│   ├── auth.ts           /sys/** 契约（登录、验证码、当前用户）
│   └── ask.ts            问数 SSE 客户端
├── composables/
│   └── useSession.ts     当前会话（不引状态库，只有一份跨页状态）
├── views/
│   ├── LoginView.vue
│   └── AskView.vue
├── styles/
│   ├── tokens.css        设计令牌
│   └── global.css        全局样式与通用件
├── router.ts
├── App.vue
└── main.ts
```

## 几个刻意的取舍

**凭据只走 Cookie，不存 localStorage。** 登录接口下发了 HttpOnly + SameSite=Lax 的
Cookie，而 localStorage 里的任何东西都能被 XSS 读走。代价是跨域部署时 Cookie 不带——
本项目前后端同源，不构成问题。

**不用 `EventSource`。** 它只支持 GET，而问数端点是 POST（问题可能很长，
放 URL 里也会进访问日志）。改用 fetch + ReadableStream 手工解析 SSE 帧。

**不引中文 webfont。** 思源黑体之类动辄数 MB，换来的只是"好看一点"，
与 Core Web Vitals 的代价不成比例。性格靠字阶、字距与网格做。
构建产物：JS gzip ≈ 41 kB、CSS gzip ≈ 3 kB。

**阶段步进器而非转圈 spinner。** 用户能看到卡在哪一步（校验权限 / 理解问题 /
生成查询 / 查询数据），比一个转圈的圆点信息量大得多。

## 加新路由时注意

前端用 history 模式路由。**新增路由必须同步在**
`chatdata-agent-ask/.../config/WebAppForwardController.java` 里补一行转发，
否则该路由刷新即 404。

不要图省事改成 `/app/**` 通配——那会让真实存在的 JS/CSS 也被替换成 index.html，
表现为白屏 + 控制台 MIME 类型错误。

import { defineConfig } from 'vite'

/**
 * `chatdata-embed.js` 的构建配置。
 *
 * <h2>为什么单独一个配置文件</h2>
 *
 * <p>主应用是 Vue SPA，产物是 `index.html` + 若干 chunk，挂在 `/app/` 下。
 * 而 embed.js 是**给宿主页面直接引的**一个自包含脚本：
 *
 * <ul>
 *   <li>必须是单个文件——宿主不该为了嵌一个框去处理 chunk 依赖</li>
 *   <li>不能依赖 Vue——那会让它从 5KB 变成 100KB+</li>
 *   <li>产物路径不同：`/embed/v1/chatdata-embed.js`</li>
 * </ul>
 *
 * <p>两个构建用两个配置文件，而不是一个配置里的 `rollupOptions.input` 多入口：
 * 多入口共享同一份 `base`、`outDir`、插件，而这两个产物的每一项都不同。
 * 硬塞在一起要靠一堆条件分支，比两个文件难读得多。
 *
 * <h2>版本号在路径里</h2>
 *
 * <p>`/embed/v1/` 而不是 `/embed/`：宿主的页面引了这个脚本之后，
 * 我们升级时**不能让它静默坏掉**。带版本号意味着 v1 的语义冻结，
 * 破坏性变更走 v2，而宿主可以自己决定什么时候迁。
 */
export default defineConfig({
  build: {
    // 关掉代码分割：产物必须是一个文件
    lib: {
      entry: 'src/embed/chatdata-embed.ts',
      name: 'ChatdataEmbed',
      formats: ['iife'],
      fileName: () => 'chatdata-embed.js',
    },
    // IIFE 不需要单独的 CSS 文件（组件用内联样式）
    cssCodeSplit: false,
    // 产物落到问数服务的静态目录下，与主应用并列
    outDir: '../chatdata-query-service/src/main/resources/static/embed/v1',
    emptyOutDir: true,
    // 宿主页面里跑的东西，体积优先
    minify: 'esbuild',
    sourcemap: false,
    target: 'es2020',
  },
})
